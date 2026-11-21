package com.zrec.recorder

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
import kotlin.math.hypot

/**
 * Draggable bubble that floats over other apps. It hides itself while a recording
 * is running so the video stays clean, and the notification takes over as the
 * control surface during that time.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.zrec.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.zrec.OVERLAY_HIDE"
        /** Munculkan bola sebentar saat lagi disembunyikan (volume bawah 2x / tombol notifikasi). */
        const val ACTION_PEEK = "com.zrec.OVERLAY_PEEK"
        /** Berapa lama bola kelihatan setelah dipanggil, dalam milidetik. */
        private const val PEEK_MS = 5000L
        /** Tampil sebentar begitu rekaman mulai, biar ketahuan bolanya ada. */
        private const val AUTO_PEEK_MS = 2500L
        /** Dua penurunan volume dalam rentang ini dianggap "tekan volume bawah 2x". */
        private const val VOL_GAP_MS = 900L
        /** Diameter jendela target close, dalam dp. */
        private const val CLOSE_SIZE = 96
        /** Jarak tepi bawah target close dari tepi bawah layar, dalam dp. */
        private const val CLOSE_GAP = 96
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var root: View? = null
    private var panel: LinearLayout? = null
    private var btnToggle: ImageButton? = null
    private var btnPause: ImageButton? = null
    private var btnAudio: ImageButton? = null
    private var dot: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** Drop target shown while dragging; letting go over it closes the control. */
    private var closeZone: View? = null
    private var closeRing: View? = null
    private var closeHot = false

    /** Wall clock until which the bubble stays visible while a recording hides it. */
    private var peekUntil = 0L

    /**
     * Last level of every stream a volume key can move. The active stream is not
     * always music - with nothing playing the keys move the ringer - so watching
     * only STREAM_MUSIC missed most presses.
     */
    private val volStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_SYSTEM
    )
    private val lastVol = HashMap<Int, Int>()

    /** Steps dropped inside one gesture, and when that gesture started. */
    private var dropSum = 0
    private var dropSince = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
    private var expanded = false

    /** Set by the sync loop when a take starts, so the bubble shows itself once. */
    private var wasRecording = false

    private val sync = object : Runnable {
        override fun run() {
            val recording = RecorderService.active
            // A take that just started shows the bubble briefly, then it hides again.
            if (recording && !wasRecording) peek(AUTO_PEEK_MS)
            wasRecording = recording
            refresh()
            handler.postDelayed(this, 600)
        }
    }

    /**
     * Two volume-down presses inside this window call the bubble back.
     *
     * Both presses are counted by how far a stream fell, not by how many callbacks
     * arrived: the settings provider coalesces two quick changes into one callback,
     * which is why the old "two callbacks" test never fired on a fast double tap.
     */
    private val volObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val am = getSystemService(AudioManager::class.java) ?: return
            var dropped = 0
            for (s in volStreams) {
                val v = runCatching { am.getStreamVolume(s) }.getOrDefault(-1)
                if (v < 0) continue
                val prev = lastVol[s]
                if (prev != null && v < prev) dropped += prev - v
                lastVol[s] = v
            }
            if (dropped == 0) return
            val now = SystemClock.elapsedRealtime()
            if (now - dropSince > VOL_GAP_MS) {
                dropSince = now
                dropSum = 0
            }
            dropSum += dropped
            if (dropSum >= 2) {
                dropSum = 0
                peek()
            }
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
        val am = getSystemService(AudioManager::class.java)
        if (am != null) {
            for (s in volStreams) {
                runCatching { am.getStreamVolume(s) }.getOrNull()?.let { lastVol[s] = it }
            }
        }
        runCatching {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volObserver)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PEEK -> peek()
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
        btnAudio = v.findViewById(R.id.btnAudio)
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
            // FLAG_SECURE keeps the bubble and its buttons out of our own screen
            // capture. Without it the control is recorded whenever it is visible,
            // which is why the bubble used to hide for the whole take.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SECURE or
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
        btnAudio?.setOnClickListener { cycleAudio() }
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
                    if (closeZone == null) addCloseZone()
                    setCloseHot(overCloseZone())
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    expand(!expanded)
                } else if (overCloseZone()) {
                    removeCloseZone()
                    closeControl()
                    return true
                } else {
                    snap()
                }
                removeCloseZone()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCloseZone()
                return true
            }
        }
        return false
    }

    // ---------- close area ----------

    /** Drop target: a fixed circle centred horizontally, closeGap above the bottom edge. */
    private fun addCloseZone() {
        if (closeZone != null) return
        val z = LayoutInflater.from(this).inflate(R.layout.overlay_close, null)
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            dp(CLOSE_SIZE),
            dp(CLOSE_SIZE),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(CLOSE_GAP)
        }
        closeZone = z
        closeRing = z.findViewById(R.id.closeRing)
        closeHot = false
        runCatching { wm.addView(z, lp) }
    }

    private fun removeCloseZone() {
        closeZone?.let { runCatching { wm.removeView(it) } }
        closeZone = null
        closeRing = null
        closeHot = false
    }

    private fun setCloseHot(hot: Boolean) {
        if (hot == closeHot) return
        closeHot = hot
        closeRing?.apply {
            setBackgroundResource(
                if (hot) R.drawable.bg_close_zone_hot else R.drawable.bg_close_zone
            )
            animate().scaleX(if (hot) 1.12f else 1f)
                .scaleY(if (hot) 1.12f else 1f)
                .setDuration(120)
                .start()
        }
    }

    /**
     * Where the drop target is, worked out from the display rather than from the
     * view. Used as the fallback while the window has not been measured yet.
     */
    private fun closeCentreX(): Float = resources.displayMetrics.widthPixels / 2f

    private fun closeCentreY(): Float =
        resources.displayMetrics.heightPixels - dp(CLOSE_GAP) - dp(CLOSE_SIZE) / 2f

    /**
     * True when the bubble sits on top of the close target. Uses the target's real
     * bounds once it has been laid out, and falls back to the geometry the layout
     * params describe while the window is still unmeasured.
     */
    private fun overCloseZone(): Boolean {
        val z = closeZone ?: return false
        val b = root?.findViewById<View>(R.id.bubble) ?: return false
        val bl = IntArray(2)
        b.getLocationOnScreen(bl)
        val zl = IntArray(2)
        z.getLocationOnScreen(zl)
        val cx = if (z.width > 0) zl[0] + z.width / 2f else closeCentreX()
        val cy = if (z.height > 0) zl[1] + z.height / 2f else closeCentreY()
        val dx = (bl[0] + b.width / 2f) - cx
        val dy = (bl[1] + b.height / 2f) - cy
        return hypot(dx, dy) <= dp(CLOSE_SIZE) * 0.62f
    }

    private fun closeControl() {
        prefs.overlay = false
        toast("Floating control closed")
        stopSelf()
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

        // Clean video: the bubble disappears the moment recording starts, but it
        // comes back for a few seconds when called (volume down twice).
        val hide = recording && prefs.hideOverlayOnRecord && !peeking()
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

    private fun peeking(): Boolean = SystemClock.elapsedRealtime() < peekUntil

    /** Show the bubble for [ms] even though the recording is hiding it. */
    private fun peek(ms: Long = PEEK_MS) {
        // Someone who closed the control on purpose does not get it back.
        if (root == null || !prefs.overlay) return
        peekUntil = SystemClock.elapsedRealtime() + ms
        refresh()
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
            // The recorder reports the real result; saying "saved" here first meant two
            // toasts, and the first one was a guess that could be wrong.
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_SHOT))
        } else if (ShotAccessibilityService.available(this)) {
            // No consent dialog and no trip through the app: grab the frame right here.
            val started = ShotAccessibilityService.grabNow { name, why ->
                handler.post {
                    toast(if (name != null) "Screenshot saved" else "Screenshot failed: ${why ?: "unknown"}")
                }
            }
            if (!started) toast("Screen capture is starting, try again")
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_SHOT, true)
            )
        }
    }

    /**
     * Steps through the audio sources. Internal capture only exists from Android 10,
     * so older phones are offered Off and Mic only instead of a choice that would be
     * silently downgraded at start.
     */
    private fun cycleAudio() {
        val choices = if (Build.VERSION.SDK_INT >= 29) {
            intArrayOf(Config.AUDIO_OFF, Config.AUDIO_MIC, Config.AUDIO_INTERNAL, Config.AUDIO_BOTH)
        } else {
            intArrayOf(Config.AUDIO_OFF, Config.AUDIO_MIC)
        }
        val at = choices.indexOf(prefs.audio)
        val next = choices[if (at < 0) 0 else (at + 1) % choices.size]
        prefs.audio = next
        // The audio track is opened once, when the file is created, so a change now
        // takes effect on the next take. Saying "next recording" beats a silent no-op.
        val name = audioName(next)
        toast(
            if (RecorderService.active) "Audio: $name (next recording)"
            else "Audio: $name"
        )
    }

    private fun audioName(audio: Int): String = when (audio) {
        Config.AUDIO_OFF -> "Off"
        Config.AUDIO_MIC -> "Mic"
        Config.AUDIO_INTERNAL -> "Internal"
        else -> "Mic + internal"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { contentResolver.unregisterContentObserver(volObserver) }
        removeCloseZone()
        runCatching { root?.let { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }
}
