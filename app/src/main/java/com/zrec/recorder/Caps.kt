package com.zrec.recorder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.DisplayMetrics

/**
 * Reads what the phone's hardware encoder can actually do, so the UI never
 * offers a resolution or frame rate this device cannot encode.
 */
object Caps {

    data class Codec(
        val mime: String,
        val label: String,
        val maxFps: Int,
        val bitrateMax: Int,
        val supported: List<Pair<Int, Int>>
    )

    val FPS_CHOICES = listOf(30, 60, 90, 120)
    val QUALITY_CHOICES = listOf(1080, 720, 480)

    fun probe(): List<Codec> {
        val out = mutableListOf<Codec>()
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (mime in listOf("video/avc", "video/hevc")) {
            for (ci in infos) {
                if (!ci.isEncoder || mime !in ci.supportedTypes) continue
                if (!ci.isHardwareAccelerated) continue
                val vc = runCatching { ci.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: continue
                val sizes = QUALITY_CHOICES.mapNotNull { short ->
                    val (w, h) = sizeFor(short, 1080, 2400)
                    if (runCatching { vc.isSizeSupported(w, h) }.getOrDefault(false)) short to short else null
                }.map { it.first }
                if (sizes.isEmpty()) continue
                val maxFps = runCatching { vc.supportedFrameRates.upper.toInt() }.getOrDefault(60)
                out += Codec(
                    mime = mime,
                    label = if (mime == "video/hevc") "H.265" else "H.264",
                    maxFps = maxFps.coerceAtMost(240),
                    bitrateMax = runCatching { vc.bitrateRange.upper }.getOrDefault(50_000_000),
                    supported = sizes.map { it to it }
                )
                break
            }
        }
        return out
    }

    fun fpsOptions(codec: Codec, quality: Int, displayW: Int, displayH: Int): List<Int> {
        val size = sizeFor(quality, displayW, displayH)
        val vc = videoCaps(codec.mime)
        val out = ArrayList<Int>()
        for (fps in FPS_CHOICES) {
            if (fps > codec.maxFps) continue
            if (vc == null) {
                out.add(fps)
                continue
            }
            val ok = try {
                vc.areSizeAndRateSupported(size.first, size.second, fps.toDouble())
            } catch (_: Throwable) {
                false
            }
            if (ok) out.add(fps)
        }
        return if (out.isEmpty()) listOf(30) else out
    }

    private fun videoCaps(mime: String): MediaCodecInfo.VideoCapabilities? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (ci in infos) {
            if (!ci.isEncoder || mime !in ci.supportedTypes || !ci.isHardwareAccelerated) continue
            return runCatching { ci.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
        }
        return null
    }

    /** Target short side, keeping the display aspect, aligned for the encoder. */
    fun sizeFor(quality: Int, displayW: Int, displayH: Int): Pair<Int, Int> {
        val long = maxOf(displayW, displayH).toFloat()
        val short = minOf(displayW, displayH).toFloat()
        val scale = quality / short
        return align((long * scale).toInt()) to align((short * scale).toInt())
    }

    private fun align(v: Int): Int = (v / 16) * 16

    fun autoBitrate(w: Int, h: Int, fps: Int): Int {
        // ~0.10 bit per pixel per frame: 1080p60 lands near 12 Mbps.
        val raw = (w.toLong() * h * fps * 0.10).toLong()
        return raw.coerceIn(2_000_000L, 40_000_000L).toInt()
    }

    fun displaySize(ctx: android.content.Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }
}
