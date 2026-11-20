package com.zrec.recorder

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs

/**
 * Draggable bubble that floats over other apps. It hides itself while a recording
 * is running so the video stays clean, and the notification takes over as the
 * control surface during that time.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.zrec.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.zrec.OVERLAY_HIDE"
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var root: View? = null
    private var panel: LinearLayout? = null
    private var btnToggle: ImageButton? = null
    private var btnPause: ImageButton? = null
    private var dot: View? = null
    private var params: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
    private var expanded = false

    private val sync = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 600)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
        running = true
        handler.post(sync)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (root != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        root = v
        panel = v.findViewById(R.id.panel)
        btnToggle = v.findViewById(R.id.btnToggle)
        btnPause = v.findViewById(R.id.btnPause)
        dot = v.findViewById(R.id.dot)

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = dp(140)
        }
        params = lp

        v.findViewById<ImageButton>(R.id.btnShot).setOnClickListener { shot() }
        btnToggle?.setOnClickListener { toggleRecord() }
        btnPause?.setOnClickListener { togglePause() }
        v.findViewById<View>(R.id.bubble).setOnTouchListener { _, e -> drag(e) }

        runCatching { wm.addView(v, lp) }
        refresh()
    }

    private fun drag(e: MotionEvent): Boolean {
        val lp = params ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                startX = lp.x
                startY = lp.y
                dragged = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                if (dragged) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    val v = root
                    if (v != null) runCatching { wm.updateViewLayout(v, lp) }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    expand(!expanded)
                } else {
                    snap()
                }
                return true
            }
        }
        return false
    }

    private fun snap() {
        val lp = params ?: return
        val v = root ?: return
        val screenW = resources.displayMetrics.widthPixels
        val w = if (v.width > 0) v.width else dp(60)
        lp.x = if (lp.x + w / 2 < screenW / 2) dp(4) else screenW - w - dp(4)
        val maxY = resources.displayMetrics.heightPixels - dp(120)
        lp.y = lp.y.coerceIn(dp(40), maxY)
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun expand(open: Boolean) {
        expanded = open
        panel?.visibility = if (open) View.VISIBLE else View.GONE
        handler.postDelayed({ snap() }, 60)
    }

    private fun refresh() {
        val v = root ?: return
        val lp = params ?: return
        val recording = RecorderService.active

        // Clean video: the bubble disappears the moment recording starts.
        val hide = recording && prefs.hideOverlayOnRecord
        if (hide) {
            if (expanded) expand(false)
            if (v.visibility != View.GONE) v.visibility = View.GONE
            return
        }
        if (v.visibility != View.VISIBLE) {
            v.visibility = View.VISIBLE
            runCatching { wm.updateViewLayout(v, lp) }
        }

        dot?.visibility = if (recording) View.VISIBLE else View.GONE
        btnToggle?.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_rec)
        btnPause?.alpha = if (recording) 1f else 0.35f
    }

    private fun toggleRecord() {
        expand(false)
        if (RecorderService.active) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true)
            )
        }
    }

    private fun togglePause() {
        if (!RecorderService.active) {
            toast("Not recording yet")
            return
        }
        val action = if (RecorderService.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE
        startService(Intent(this, RecorderService::class.java).setAction(action))
        handler.postDelayed({ refresh() }, 250)
    }

    private fun shot() {
        expand(false)
        if (RecorderService.active) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_SHOT))
            toast("Screenshot saved")
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_SHOT, true)
            )
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { root?.let { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }
}
