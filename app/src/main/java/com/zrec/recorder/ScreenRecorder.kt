package com.zrec.recorder

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One recording session: hardware encoder fed straight from the display surface
 * (no re-encode, no software scaling) plus optional AAC audio.
 */
class ScreenRecorder(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val cfg: Config,
    private val onStats: (fps: Int, seconds: Long) -> Unit,
    private val onFatal: (String) -> Unit
) {
    private companion object {
        /** A take rolls to a new file every five minutes, on a keyframe. */
        const val SEGMENT_US = 5 * 60 * 1_000_000L
    }

    private val lock = Any()
    private val sampleRate = 48_000

    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    /** The newest file this take produced. Read by the service after stop. */
    @Volatile var lastUri: Uri? = null
        private set

    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null

    private var audioCodec: MediaCodec? = null
    private var mic: AudioRecord? = null
    private var internalRec: AudioRecord? = null

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var statsThread: Thread? = null

    @Volatile private var looping = true
    @Volatile private var paused = false
    @Volatile private var stopping = false

    /** True only when an audio source really opened; the muxer waits for it. */
    @Volatile private var audioOn = false

    /** Set in start(); used to stop waiting for an audio track that never arrives. */
    @Volatile private var beganNs = 0L

    @Volatile private var pausedUs = 0L
    @Volatile private var pauseStartNs = 0L

    @Volatile private var muxStarted = false
    private var vTrack = -1
    private var aTrack = -1

    /** Track formats, kept so a new segment can be opened without re-encoding. */
    private var vFormat: MediaFormat? = null
    private var aFormat: MediaFormat? = null

    /**
     * Segments. A long take is one file until this many microseconds have gone by,
     * then the file is closed and a new one is opened at the next keyframe. One
     * corrupt or refused file therefore costs a segment, not the whole recording.
     */
    @Volatile private var segmentUs = 0L

    /** Raw timestamp the current file starts at, for video and audio separately. */
    @Volatile private var segBaseUs = 0L
    @Volatile private var segAudioBaseUs = 0L

    /** Audio frames queued so far, used to place the audio base at a split. */
    @Volatile private var audioFrames = 0L

    /** Read by the stats thread, written by the video thread. */
    @Volatile private var frames = 0L

    val isRunning: Boolean get() = looping
    val isPaused: Boolean get() = paused

    /** False when audio was asked for but no source could be opened. */
    val audioStarted: Boolean get() = audioOn

    fun start() {
        try {
            beganNs = System.nanoTime()
            segmentUs = SEGMENT_US
            openOutput()
            startVideo()
            if (cfg.audio != Config.AUDIO_OFF) {
                // A refused mic, or a phone that will not share internal audio, used to
                // throw here and kill the whole take. Video-only beats no recording.
                audioOn = runCatching { startAudio() }.getOrDefault(false)
            }
            videoThread = thread("zrec-video") { videoLoop() }
            if (audioOn) audioThread = thread("zrec-audio") { audioLoop() }
            createVirtualDisplay()
            statsThread = thread("zrec-stats") { statsLoop() }
        } catch (t: Throwable) {
            onFatal(t.message ?: t.javaClass.simpleName)
            stop()
        }
    }

    fun pause() {
        if (!paused && looping) {
            paused = true
            pauseStartNs = System.nanoTime()
        }
    }

    fun resume() {
        if (paused) {
            pausedUs += (System.nanoTime() - pauseStartNs) / 1000
            paused = false
            // The frames dropped while paused break the GOP, so restart cleanly.
            runCatching {
                videoCodec?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }
    }

    /**
     * Screenshot from the live session. Android 14 allows only one
     * createVirtualDisplay() per MediaProjection, so the frame is taken from the
     * running display instead of opening a second capture session.
     */
    fun grabFrame(ctx: Context, done: (String?, String?) -> Unit) {
        val d = display
        val enc = inputSurface
        if (d == null || enc == null) {
            done(null, "not capturing")
            return
        }
        Shot.captureFromDisplay(ctx, d, enc, cfg.width, cfg.height, done)
    }

    fun stop() {
        if (stopping) return
        stopping = true
        runCatching { display?.release() }
        display = null
        runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { videoThread?.join(1500) }
        looping = false
        runCatching { audioThread?.join(1200) }
        runCatching { statsThread?.join(400) }

        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { mic?.stop() }
        runCatching { mic?.release() }
        runCatching { internalRec?.stop() }
        runCatching { internalRec?.release() }
        runCatching { inputSurface?.release() }

        synchronized(lock) {
            if (muxStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
        }
        runCatching { pfd?.close() }
        finalizeOutput(muxStarted)
    }

    // ---------- output ----------

    private class Out(val uri: Uri, val pfd: ParcelFileDescriptor, val muxer: MediaMuxer)

    /** Creates one MediaStore video plus a muxer on it, without touching live state. */
    private fun makeOut(): Out {
        val name = "Zrec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Zrec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val u = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Tidak bisa membuat file di galeri")
        val d = ctx.contentResolver.openFileDescriptor(u, "rw")
            ?: throw IllegalStateException("Tidak bisa membuka file")
        return Out(u, d, MediaMuxer(d.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))
    }

    private fun openOutput() {
        val o = makeOut()
        uri = o.uri
        pfd = o.pfd
        muxer = o.muxer
        lastUri = o.uri
    }

    private fun finalizeUri(u: Uri?, ok: Boolean) {
        if (u == null) return
        runCatching {
            if (ok) {
                if (Build.VERSION.SDK_INT >= 29) {
                    ctx.contentResolver.update(
                        u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null
                    )
                }
            } else {
                ctx.contentResolver.delete(u, null, null)
            }
        }
    }

    private fun finalizeOutput(ok: Boolean) = finalizeUri(uri, ok)

    /**
     * Closes the current file and opens the next one starting at [baseUs]. Called with
     * [lock] held. If the new file cannot be opened the old one keeps being written
     * and splitting is turned off: losing a boundary beats losing the recording.
     */
    private fun rollSegment(baseUs: Long) {
        val old = muxer ?: return
        val oldUri = uri
        val oldPfd = pfd
        val vf = vFormat
        val af = aFormat
        val withAudio = aTrack >= 0

        val fresh = if (vf == null) null else runCatching { makeOut() }.getOrNull()
        if (fresh == null) {
            segmentUs = 0L
            return
        }

        val started = runCatching {
            val vt = fresh.muxer.addTrack(vf)
            val at = if (withAudio && af != null) fresh.muxer.addTrack(af) else -1
            fresh.muxer.start()
            vTrack = vt
            aTrack = at
            if (withAudio && at < 0) audioOn = false
            true
        }.getOrDefault(false)

        if (!started) {
            runCatching { fresh.muxer.release() }
            runCatching { fresh.pfd.close() }
            finalizeUri(fresh.uri, false)
            segmentUs = 0L
            return
        }

        muxer = fresh.muxer
        uri = fresh.uri
        pfd = fresh.pfd
        lastUri = fresh.uri
        muxStarted = true

        runCatching { old.stop() }
        runCatching { old.release() }
        runCatching { oldPfd?.close() }
        finalizeUri(oldUri, true)

        segBaseUs = baseUs
        segAudioBaseUs = audioFrames * 1_000_000L / sampleRate
    }

    // ---------- video ----------

    private fun startVideo() {
        val format = MediaFormat.createVideoFormat(cfg.mime, cfg.width, cfg.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(cfg.mime)
        val enc = runCatching { codec.codecInfo.getCapabilitiesForType(cfg.mime).encoderCapabilities }.getOrNull()
        if (enc != null && enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
    }

    private fun createVirtualDisplay() {
        display = projection.createVirtualDisplay(
            "Zrec",
            cfg.width, cfg.height, cfg.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, Handler(Looper.getMainLooper())
        )
    }

    private fun videoLoop() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (looping) {
            // An audio track that never reports its format used to hold the muxer
            // closed for the whole take: nothing was written and the file was deleted
            // on stop. After 2.5 s the take is written video-only instead.
            if (!muxStarted && vTrack >= 0 && beganNs > 0 &&
                System.nanoTime() - beganNs > 2_500_000_000L
            ) {
                synchronized(lock) {
                    audioOn = false
                    maybeStartMuxer()
                }
            }
            val idx = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (_: Throwable) {
                return
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    vFormat = codec.outputFormat
                    if (muxer != null) vTrack = muxer!!.addTrack(codec.outputFormat)
                    maybeStartMuxer()
                }

                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (buf != null && info.size > 0 && !config && !paused) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val pts = (info.presentationTimeUs - pausedUs).coerceAtLeast(0)
                        // Split on a keyframe, so the next file opens with a clean GOP
                        // instead of waiting for the encoder's next one.
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        synchronized(lock) {
                            if (segmentUs > 0 && muxStarted && key && pts - segBaseUs >= segmentUs) {
                                rollSegment(pts)
                            }
                            if (muxStarted && vTrack >= 0) {
                                info.presentationTimeUs = (pts - segBaseUs).coerceAtLeast(0)
                                runCatching { muxer!!.writeSampleData(vTrack, buf, info) }
                                frames++
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) return
                }
            }
        }
    }

    // ---------- audio ----------

    /**
     * Opens the audio sources and the AAC encoder.
     *
     * Returns false when no source could be opened, so the caller can carry on with
     * video only. Each step is guarded on its own: one refused source must not take
     * the other one down with it.
     */
    private fun startAudio(): Boolean {
        val stereo = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val mono = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val bufBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192) * 2

        if (cfg.audio == Config.AUDIO_MIC || cfg.audio == Config.AUDIO_BOTH) {
            mic = openMic(mono, bufBytes)
        }
        if (cfg.audio == Config.AUDIO_INTERNAL || cfg.audio == Config.AUDIO_BOTH) {
            internalRec = runCatching {
                val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(stereo)
                    .setBufferSizeInBytes(bufBytes)
                    .setAudioPlaybackCaptureConfig(capture)
                    .build()
            }.getOrNull()
        }

        // A recorder that never reached STATE_INITIALIZED throws on startRecording().
        if (mic?.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { mic?.release() }
            mic = null
        }
        if (internalRec?.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { internalRec?.release() }
            internalRec = null
        }
        if (mic == null && internalRec == null) return false

        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec

        runCatching { mic?.startRecording() }
        runCatching { internalRec?.startRecording() }
        return true
    }

    /**
     * Opens the microphone without the phone's speech processing.
     *
     * AudioSource.MIC turns on automatic gain control and noise suppression, which
     * pumps the voice level and lifts room hiss. UNPROCESSED is the raw capsule;
     * VOICE_RECOGNITION is the next best thing when a vendor refuses it. MIC stays
     * as the last resort rather than failing the take.
     */
    private fun openMic(format: AudioFormat, buffer: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            val r = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(buffer)
                    .build()
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            runCatching { r?.release() }
        }
        return null
    }

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val framesPerBuf = 1024
        val micBuf = ShortArray(framesPerBuf)
        val capBuf = ShortArray(framesPerBuf * 2)
        val mixBuf = ShortArray(framesPerBuf * 2)
        // Allocated once: this loop runs about 47 times a second.
        val bytes = ByteArray(framesPerBuf * 4)
        val info = MediaCodec.BufferInfo()
        var wasPaused = false

        while (looping) {
            if (paused) {
                wasPaused = true
                Thread.sleep(25)
                continue
            }
            if (wasPaused) {
                // The recorders kept filling their buffers while nothing was read, so
                // the first read after a pause returned sound from the pause itself.
                // Stopping and restarting drops that buffer.
                wasPaused = false
                runCatching { mic?.stop(); mic?.startRecording() }
                runCatching { internalRec?.stop(); internalRec?.startRecording() }
            }
            val micN = try { mic?.read(micBuf, 0, framesPerBuf, AudioRecord.READ_BLOCKING) ?: 0 } catch (_: Throwable) { 0 }
            val capN = try { internalRec?.read(capBuf, 0, framesPerBuf * 2, AudioRecord.READ_BLOCKING) ?: 0 } catch (_: Throwable) { 0 }
            if (micN <= 0 && capN <= 0) {
                Thread.sleep(10)
                continue
            }
            val n = maxOf(if (micN > 0) micN else 0, if (capN > 0) capN / 2 else 0)
            if (n <= 0) continue

            // Gain staging. Summed 1:1 the game track buries the voice and the sum
            // clips, so when both sources are live the game is pulled down and the
            // microphone lifted. A single source is left at its own level.
            val both = micN > 0 && capN > 0
            val micGain = if (both) 140 else 100
            val capGain = if (both) 70 else 100
            for (k in 0 until n) {
                val m = if (micN > k) micBuf[k].toInt() * micGain / 100 else 0
                val l = if (capN > 2 * k) capBuf[2 * k].toInt() * capGain / 100 else 0
                val r = if (capN > 2 * k + 1) capBuf[2 * k + 1].toInt() * capGain / 100 else 0
                mixBuf[2 * k] = clamp(m + l)
                mixBuf[2 * k + 1] = clamp(m + r)
            }

            var bi = 0
            for (k in 0 until n * 2) {
                val s = mixBuf[k].toInt()
                bytes[bi++] = (s and 0xFF).toByte()
                bytes[bi++] = ((s shr 8) and 0xFF).toByte()
            }

            val inIdx = try { codec.dequeueInputBuffer(10_000) } catch (_: Throwable) { -1 }
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx) ?: continue
                inBuf.clear()
                inBuf.put(bytes, 0, n * 4)
                val ptsUs = audioFrames * 1_000_000L / sampleRate
                codec.queueInputBuffer(inIdx, 0, n * 4, ptsUs, 0)
                audioFrames += n
            }
            drainAudio(codec, info)
        }
    }

    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIdx = try { codec.dequeueOutputBuffer(info, 0) } catch (_: Throwable) { return }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    aFormat = codec.outputFormat
                    // Adding a track to an already started muxer throws, and by then the
                    // take is being written video-only.
                    if (muxer != null && !muxStarted && audioOn) {
                        aTrack = muxer!!.addTrack(codec.outputFormat)
                    }
                    maybeStartMuxer()
                }
                continue
            }
            if (outIdx < 0) return
            val buf = codec.getOutputBuffer(outIdx)
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (buf != null && info.size > 0 && !config) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                synchronized(lock) {
                    if (muxStarted && aTrack >= 0) {
                        // Each file starts at zero, so the audio base moves with the split.
                        info.presentationTimeUs =
                            (info.presentationTimeUs - segAudioBaseUs).coerceAtLeast(0)
                        runCatching { muxer!!.writeSampleData(aTrack, buf, info) }
                    }
                }
            }
            codec.releaseOutputBuffer(outIdx, false)
        }
    }

    private fun clamp(v: Int): Short = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    // ---------- mux + stats ----------

    private fun maybeStartMuxer() {
        if (muxStarted) return
        val m = muxer ?: return
        if (vTrack < 0) return
        if (audioOn && aTrack < 0) return
        runCatching { m.start() }
        muxStarted = true
    }

    private fun statsLoop() {
        val startNs = System.nanoTime()
        var lastFrames = 0L
        var lastNs = startNs
        while (looping) {
            Thread.sleep(1000)
            val now = System.nanoTime()
            val f = frames
            val fps = if (now > lastNs) ((f - lastFrames) * 1_000_000_000L / (now - lastNs)).toInt() else 0
            lastFrames = f
            lastNs = now
            // Paused time has no frames in the file, so counting it made the clock show
            // more than the video actually contains. Subtract it, including the pause
            // that is still running.
            val pauseNs = pausedUs * 1000L + (if (paused) now - pauseStartNs else 0L)
            onStats(fps, ((now - startNs - pauseNs) / 1_000_000_000L).coerceAtLeast(0L))
        }
    }

    private fun thread(name: String, body: () -> Unit) = Thread({
        runCatching { body() }
    }, name).apply { start() }
}
