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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Grabs a single frame off the projection and saves it to Pictures/Zrec. */
object Shot {

    private const val TIMEOUT_MS = 3000L

    fun capture(
        ctx: Context,
        projection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int,
        done: (String?) -> Unit
    ) {
        val w = (width / 16) * 16
        val h = (height / 16) * 16
        if (w <= 0 || h <= 0) {
            done(null)
            return
        }

        val main = Handler(Looper.getMainLooper())
        val worker = HandlerThread("zrec-shot").apply { start() }
        val workerHandler = Handler(worker.looper)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        var display: VirtualDisplay? = null
        var finished = false

        // The callback always lands on the main thread, and always fires exactly once,
        // even if the worker handler has already been told to quit.
        fun finish(msg: String?) {
            if (finished) return
            finished = true
            runCatching { display?.release() }
            display = null
            runCatching { reader.close() }
            runCatching { worker.quitSafely() }
            main.post { done(msg) }
        }

        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val name = "Zrec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            val saved = runCatching { save(ctx, image, w, h, name) }.getOrNull()
            runCatching { image.close() }
            finish(saved)
        }, workerHandler)

        display = runCatching {
            projection.createVirtualDisplay(
                "ZrecShot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, workerHandler
            )
        }.getOrNull()

        if (display == null) {
            finish(null)
            return
        }
        main.postDelayed({ finish(null) }, TIMEOUT_MS)
    }

    private fun save(ctx: Context, image: Image, w: Int, h: Int, name: String): String? {
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

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zrec")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return null
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null
            )
        }
        bmp.recycle()
        return name
    }
}
