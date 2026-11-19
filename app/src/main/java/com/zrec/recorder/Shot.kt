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
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Grabs a single frame off the projection and saves it to Pictures/Zrec. */
object Shot {

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
        val thread = HandlerThread("zrec-shot").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null
        var finished = false

        fun finish(msg: String?) {
            if (finished) return
            finished = true
            runCatching { display?.release() }
            runCatching { reader.close() }
            runCatching { thread.quitSafely() }
            handler.post { done(msg) }
        }

        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val name = "Zrec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            val saved = runCatching { save(ctx, image, w, h, name) }.getOrDefault(null)
            runCatching { image.close() }
            finish(saved)
        }, handler)

        display = runCatching {
            projection.createVirtualDisplay(
                "ZrecShot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        }.getOrNull()

        if (display == null) finish(null)
        handler.postDelayed({ finish(null) }, 3000)
    }

    private fun save(ctx: Context, image: Image, w: Int, h: Int, name: String): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val stride = plane.rowStride
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = ByteArray(stride)
        val pixels = IntArray(w * h)
        var offset = 0
        for (y in 0 until h) {
            buffer.position(y * stride)
            val n = minOf(stride, buffer.remaining())
            buffer.get(row, 0, n)
            var px = 0
            for (x in 0 until w) {
                val b = row[px].toInt() and 0xFF
                val g = row[px + 1].toInt() and 0xFF
                val r = row[px + 2].toInt() and 0xFF
                pixels[offset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                px += 4
            }
            offset += w
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zrec")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
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
