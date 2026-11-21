package com.zrec.recorder

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Grabs a screenshot without the screen-sharing prompt.
 *
 * Android requires a MediaProjection consent dialog for every capture session, which
 * made "take a screenshot" a three-tap chore. From Android 11 an accessibility service
 * can call takeScreenshot() instead: the user turns the service on once, and after that
 * a shot is a single tap.
 *
 * It reads no screen content - canRetrieveWindowContent is off, events are ignored, and
 * the only thing that ever leaves this class is a bitmap of the frame.
 */
class ShotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var live: ShotAccessibilityService? = null

        /** True when a shot can be taken right now without the consent dialog. */
        fun available(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 30) return false
            return live != null || enabledInSettings(ctx)
        }

        /**
         * Takes the shot through the live service. Returns false when the system has not
         * bound the service yet, so the caller can say so instead of showing a dialog.
         */
        fun grabNow(done: (String?, String?) -> Unit): Boolean {
            val s = live ?: return false
            s.grab(done)
            return true
        }

        /**
         * Reads the same list Settings uses. The service also reports itself through
         * [live], but that flips after the system binds it, which can lag the toggle.
         */
        fun enabledInSettings(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 30) return false
            val flat = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val name = ComponentName(ctx, ShotAccessibilityService::class.java)
            val long = name.flattenToString()
            val short = name.flattenToShortString()
            return flat.split(':').any { it.equals(long, true) || it.equals(short, true) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        live = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        live = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun grab(done: (String?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            done(null, "needs Android 11")
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bmp = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    runCatching { buffer.close() }
                    if (bmp == null) {
                        done(null, "could not read the frame")
                        return
                    }
                    val saved = runCatching { Shot.saveBitmap(applicationContext, bmp) }
                    bmp.recycle()
                    done(saved.getOrNull(), saved.exceptionOrNull()?.message)
                }

                override fun onFailure(errorCode: Int) {
                    done(null, "error $errorCode")
                }
            }
        )
    }
}
