package com.zrec.recorder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.DisplayMetrics

/**
 * Reads what the phone's hardware encoder can actually do, so the UI never
 * offers a resolution or frame rate this device cannot encode.
 *
 * Two ceilings apply and the lower one wins:
 *  - what the app allows per resolution (FPS_CEILING)
 *  - what the encoder reports through areSizeAndRateSupported()
 */
object Caps {

    data class Codec(
        val mime: String,
        val label: String,
        val hardware: Boolean,
        val maxFps: Int,
        val bitrateMax: Int,
        val supported: List<Int>,
        val video: MediaCodecInfo.VideoCapabilities?
    )

    /** 720p tops out at 144, 1080p at 120, 1440p at 90, 4K at 60. */
    val FPS_CEILING = linkedMapOf(
        2160 to 60,
        1440 to 90,
        1080 to 120,
        720 to 144
    )

    /** No 480p: below 720p the text on screen stops being readable. */
    val QUALITY_CHOICES = listOf(2160, 1440, 1080, 720)

    val FPS_CHOICES = listOf(30, 60, 90, 120, 144)

    fun probe(displayW: Int, displayH: Int): List<Codec> {
        val hardware = mutableListOf<Codec>()
        val software = mutableListOf<Codec>()
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (mime in listOf("video/avc", "video/hevc")) {
            for (ci in infos) {
                if (!ci.isEncoder || mime !in ci.supportedTypes) continue
                val vc = runCatching {
                    ci.getCapabilitiesForType(mime).videoCapabilities
                }.getOrNull() ?: continue

                val sizes = QUALITY_CHOICES.filter { short ->
                    val (w, h) = sizeFor(short, displayW, displayH)
                    runCatching { vc.isSizeSupported(w, h) }.getOrDefault(false)
                }
                if (sizes.isEmpty()) continue

                val codec = Codec(
                    mime = mime,
                    label = if (mime == "video/hevc") "H.265" else "H.264",
                    hardware = ci.isHardwareAccelerated,
                    maxFps = runCatching { vc.supportedFrameRates.upper.toInt() }
                        .getOrDefault(60).coerceAtLeast(30),
                    bitrateMax = runCatching { vc.bitrateRange.upper }
                        .getOrDefault(50_000_000),
                    supported = sizes,
                    video = vc
                )
                // Hardware first; a software encoder is still better than refusing to record.
                if (ci.isHardwareAccelerated) hardware += codec else software += codec
                break
            }
        }
        return hardware + software
    }

    fun fpsOptions(codec: Codec, quality: Int, displayW: Int, displayH: Int): List<Int> {
        val size = sizeFor(quality, displayW, displayH)
        val ceiling = FPS_CEILING[quality] ?: 60
        val out = ArrayList<Int>()
        for (fps in FPS_CHOICES) {
            if (fps > ceiling) continue
            if (fps > codec.maxFps) continue
            val vc = codec.video
            if (vc == null) {
                out.add(fps)
                continue
            }
            val ok = runCatching {
                vc.areSizeAndRateSupported(size.first, size.second, fps.toDouble())
            }.getOrDefault(false)
            if (ok) out.add(fps)
        }
        return if (out.isEmpty()) listOf(30) else out
    }

    /**
     * Target short side, keeping the display aspect. Never upscales past the
     * panel, and stays 16-aligned because some encoders refuse odd sizes.
     */
    fun sizeFor(quality: Int, displayW: Int, displayH: Int): Pair<Int, Int> {
        val longSide = maxOf(displayW, displayH).toFloat()
        val shortSide = minOf(displayW, displayH).toFloat()
        val target = minOf(quality.toFloat(), shortSide)
        val scale = if (shortSide <= 0f) 1f else target / shortSide
        return align((longSide * scale).toInt()) to align((shortSide * scale).toInt())
    }

    private fun align(v: Int): Int = (v / 16) * 16

    fun autoBitrate(w: Int, h: Int, fps: Int): Int {
        // ~0.10 bit per pixel per frame: 1080p60 lands near 12 Mbps.
        val raw = (w.toLong() * h * fps * 0.10).toLong()
        return raw.coerceIn(2_000_000L, 80_000_000L).toInt()
    }

    fun displaySize(ctx: android.content.Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }
}
