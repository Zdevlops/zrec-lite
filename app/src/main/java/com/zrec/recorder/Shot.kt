package com.zrec.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Grabs a single frame off the projection and saves it to Pictures/Zrec. */
object Shot {

    private const val TIMEOUT_MS = 3000L

    /**
     * Screenshot with its own projection, used when nothing is being recorded.
     * The caller must have registered a MediaProjection.Callback first: Android 14
     * throws IllegalStateException from createVirtualDisplay without one.
     */
    fun capture(
        ctx: Context,
        projection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int,
        done: (String?, String?) -> Unit
    ) {
        val size = aligned(width, height)
        if (size == null) {
            done(null, "bad size")
            return
        }
        val (w, h) = size

        val main = Handler(Looper.getMainLooper())
        val worker = HandlerThread("zrec-shot").apply { start() }
        val workerHandler = Handler(worker.looper)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        var display: VirtualDisplay? = null
        val finished = AtomicBoolean(false)

        // The callback always lands on the main thread, and always fires exactly once,
        // even if the worker handler has already been told to quit.
        fun finish(name: String?, reason: String?) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { display?.release() }
            display = null
            runCatching { reader.close() }
            runCatching { worker.quitSafely() }
            main.post { done(name, reason) }
        }

        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
            if (image == null) {
                finish(null, "no frame")
                return@setOnImageAvailableListener
            }
            val result = runCatching { save(ctx, image, w, h) }
            runCatching { image.close() }
            val name = result.getOrNull()
            val why = result.exceptionOrNull()?.message
                ?: if (name == null) "could not save" else null
            finish(name, why)
        }, workerHandler)

        display = runCatching {
            projection.createVirtualDisplay(
                "ZrecShot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, workerHandler
            )
        }.getOrElse { t ->
            finish(null, t.message ?: t.javaClass.simpleName)
            null
        }

        if (display == null) return
        main.postDelayed({ finish(null, "timed out") }, TIMEOUT_MS)
    }

    /**
     * Screenshot during a recording. Android 14 allows only one
     * createVirtualDisplay() per MediaProjection, so this cannot open a second
     * capture session. Instead the live display is pointed at an ImageReader for
     * a single frame and then handed straight back to the encoder. The take keeps
     * going; it just loses the frames produced during the swap.
     */
    fun captureFromDisplay(
        ctx: Context,
        display: VirtualDisplay,
        encoderSurface: Surface,
        width: Int,
        height: Int,
        done: (String?, String?) -> Unit
    ) {
        val size = aligned(width, height)
        if (size == null) {
            done(null, "bad size")
            return
        }
        val (w, h) = size

        val main = Handler(Looper.getMainLooper())
        val worker = HandlerThread("zrec-shot").apply { start() }
        val workerHandler = Handler(worker.looper)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        val finished = AtomicBoolean(false)
        val restored = AtomicBoolean(false)

        fun restore() {
            if (restored.compareAndSet(false, true)) {
                runCatching { display.setSurface(encoderSurface) }
            }
        }

        fun finish(name: String?, reason: String?) {
            if (!finished.compareAndSet(false, true)) return
            restore()
            runCatching { reader.close() }
            runCatching { worker.quitSafely() }
            main.post { done(name, reason) }
        }

        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
            // Give the display back to the encoder before doing any work on the frame.
            restore()
            if (image == null) {
                finish(null, "no frame")
                return@setOnImageAvailableListener
            }
            val result = runCatching { save(ctx, image, w, h) }
            runCatching { image.close() }
            val name = result.getOrNull()
            val why = result.exceptionOrNull()?.message
                ?: if (name == null) "could not save" else null
            finish(name, why)
        }, workerHandler)

        runCatching { display.setSurface(reader.surface) }.onFailure { t ->
            finish(null, t.message ?: t.javaClass.simpleName)
            return
        }
        main.postDelayed({ finish(null, "timed out") }, TIMEOUT_MS)
    }

    private fun aligned(width: Int, height: Int): Pair<Int, Int>? {
        val w = (width / 16) * 16
        val h = (height / 16) * 16
        return if (w <= 0 || h <= 0) null else w to h
    }

    private fun fileName(): String =
        "Zrec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"

    private fun save(ctx: Context, image: Image, w: Int, h: Int): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride
        val padded = w + (rowStride - pixelStride * w).coerceAtLeast(0) / pixelStride

        // One bitmap only. A 4K frame is 33 MB, so the old int-array copy doubled
        // peak memory for no reason and could be killed on a full phone.
        val raw = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        raw.copyPixelsFromBuffer(buffer)

        val bmp = if (padded == w) raw else {
            val cropped = Bitmap.createBitmap(raw, 0, 0, w, h)
            raw.recycle()
            cropped
        }

        val name = fileName()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zrec")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                bmp.recycle()
                return null
            }
        val out = ctx.contentResolver.openOutputStream(uri)
        if (out == null) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            bmp.recycle()
            return null
        }
        out.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null
            )
        }
        bmp.recycle()
        return name
    }
}
