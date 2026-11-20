package com.zrec.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.widget.Toast
import java.util.Locale
import kotlin.math.sqrt

class RecorderService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.zrec.START"
        const val ACTION_STOP = "com.zrec.STOP"
        const val ACTION_PAUSE = "com.zrec.PAUSE"
        const val ACTION_RESUME = "com.zrec.RESUME"
        const val ACTION_SHOT = "com.zrec.SHOT"
        const val ACTION_SHOT_ONLY = "com.zrec.SHOT_ONLY"

        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_MIME = "mime"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_QUALITY = "quality"

        private const val CHANNEL = "zrec"
        private const val NOTIF = 71

        /** A shake peak counts above 2.4 g; two peaks inside 1.5 s stop the take. */
        private const val SHAKE_G = 2.4f
        private const val SHAKE_GAP_MS = 250L
        private const val SHAKE_WINDOW_MS = 1500L

        @Volatile var active = false
        @Volatile var paused = false
        @Volatile var lastFps = 0
        @Volatile var lastSeconds = 0L

        /** Called whenever recording state or stats change. */
        @Volatile var onEvent: (() -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var cfg: Config? = null
    private var quality = 1080
    private var finishing = false

    private var sensors: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastPeakAt = 0L
    private var peaks = 0

    private val main = Handler(Looper.getMainLooper())

    private val stats: (Int, Long) -> Unit = { fps, secs ->
        lastFps = fps
        lastSeconds = secs
        publish()
    }
    private val fatal: (String) -> Unit = { reason ->
        main.post {
            toast("Recording failed: $reason")
            finishNow()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            main.postDelayed({ maybeRotate() }, 600)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        sensors = getSystemService(SensorManager::class.java)
        accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishNow()
            ACTION_SHOT -> screenshot()
            ACTION_PAUSE -> {
                recorder?.pause()
                paused = true
                publish()
            }
            ACTION_RESUME -> {
                recorder?.resume()
                paused = false
                publish()
            }
            ACTION_START -> startRecording(intent)
            ACTION_SHOT_ONLY -> startShotOnly(intent)
        }
        return START_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recorder != null) return
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT, 0)
        if (data == null || resultCode == 0) {
            stopSelf()
            return
        }
        quality = intent.getIntExtra(EXTRA_QUALITY, 1080)
        val asked = intent.getIntExtra(EXTRA_AUDIO, Config.AUDIO_MIC)
        // Playback capture needs Android 10; older phones fall back to the mic.
        val audio = if (Build.VERSION.SDK_INT < 29 && asked > Config.AUDIO_MIC) Config.AUDIO_MIC else asked
        val c = Config(
            width = intent.getIntExtra(EXTRA_W, 1080),
            height = intent.getIntExtra(EXTRA_H, 1920),
            dpi = intent.getIntExtra(EXTRA_DPI, 420),
            fps = intent.getIntExtra(EXTRA_FPS, 60),
            bitrate = intent.getIntExtra(EXTRA_BITRATE, 12_000_000),
            mime = intent.getStringExtra(EXTRA_MIME) ?: "video/avc",
            audio = audio,
            countdown = 0
        )

        // Foreground first: Android 14 refuses a projection created before this.
        startForegroundCompat(buildNotification("Preparing", "Capturing the screen", false))

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            stopSelf()
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                finishNow()
            }
        }, main)

        val rec = ScreenRecorder(this, proj, c, stats, fatal)
        cfg = c
        recorder = rec
        active = true
        paused = false
        rec.start()
        startShake()
        publish()
    }

    /** One-shot screenshot: borrows the projection, grabs a frame, then lets go. */
    private fun startShotOnly(intent: Intent) {
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT, 0)
        if (data == null || resultCode == 0) {
            stopSelf()
            return
        }
        startForegroundCompat(buildNotification("Screenshot", "Capturing a frame", false))
        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return
        }
        projection = proj
        Shot.capture(
            this, proj,
            intent.getIntExtra(EXTRA_W, 1080),
            intent.getIntExtra(EXTRA_H, 1920),
            intent.getIntExtra(EXTRA_DPI, 420)
        ) { name ->
            main.post {
                toast(if (name != null) "Screenshot saved" else "Screenshot failed")
                runCatching { proj.stop() }
                projection = null
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun screenshot() {
        val proj = projection ?: run {
            toast("Not recording yet")
            return
        }
        val c = cfg ?: return
        Shot.capture(this, proj, c.width, c.height, c.dpi) { name ->
            main.post {
                toast(if (name != null) "Screenshot saved" else "Screenshot failed")
            }
        }
    }

    // ---------- shake to stop ----------

    private fun startShake() {
        if (!Prefs(this).shake) return
        val s = accelerometer ?: return
        lastPeakAt = 0L
        peaks = 0
        sensors?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopShake() {
        runCatching { sensors?.unregisterListener(this) }
        peaks = 0
        lastPeakAt = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()
        // Forget stale peaks first, otherwise two shakes a minute apart would add up.
        if (now - lastPeakAt > SHAKE_WINDOW_MS) peaks = 0

        val gx = e.values[0] / SensorManager.GRAVITY_EARTH
        val gy = e.values[1] / SensorManager.GRAVITY_EARTH
        val gz = e.values[2] / SensorManager.GRAVITY_EARTH
        if (sqrt(gx * gx + gy * gy + gz * gz) < SHAKE_G) return

        // One shake produces a burst of samples; only the first one counts.
        if (now - lastPeakAt < SHAKE_GAP_MS) return
        lastPeakAt = now
        peaks++
        if (peaks < 2) return

        peaks = 0
        main.post {
            toast("Recording stopped")
            finishNow()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- rotation ----------

    private fun maybeRotate() {
        val proj = projection ?: return
        val cur = cfg ?: return
        val old = recorder ?: return
        val (dw, dh) = Caps.displaySize(this)
        val (nw, nh) = Caps.sizeFor(quality, dw, dh)
        if (nw == cur.width && nh == cur.height) return

        // A virtual display cannot resize, so the take is split at the rotation point.
        recorder = null
        runCatching { old.stop() }
        val nc = cur.copy(width = nw, height = nh, bitrate = Caps.autoBitrate(nw, nh, cur.fps))
        val fresh = ScreenRecorder(this, proj, nc, stats, fatal)
        cfg = nc
        recorder = fresh
        fresh.start()
        publish()
    }

    private fun publish() {
        if (finishing) return
        val title = when {
            !active -> "Zrec"
            paused -> "Paused"
            else -> "Recording"
        }
        val body = if (active) {
            String.format(Locale.US, "%d fps  ·  %02d:%02d", lastFps, lastSeconds / 60, lastSeconds % 60)
        } else "Ready"
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF, buildNotification(title, body, paused))
        }
        onEvent?.invoke()
    }

    private fun buildNotification(title: String, body: String, isPaused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(id: Int, label: String, act: String) = Notification.Action.Builder(
            null, label,
            PendingIntent.getService(
                this, id, Intent(this, RecorderService::class.java).setAction(act),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(action(1, "Shot", ACTION_SHOT))
            .addAction(action(2, if (isPaused) "Resume" else "Pause", if (isPaused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(action(3, "Stop", ACTION_STOP))
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF, n)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun finishNow() {
        if (finishing) return
        finishing = true
        stopShake()
        runCatching { recorder?.stop() }
        recorder = null
        runCatching { projection?.stop() }
        projection = null
        active = false
        paused = false
        lastFps = 0
        lastSeconds = 0
        onEvent?.invoke()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        finishing = false
    }

    override fun onDestroy() {
        stopShake()
        runCatching {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        }
        runCatching { recorder?.stop() }
        recorder = null
        runCatching { projection?.stop() }
        projection = null
        active = false
        onEvent?.invoke()
        super.onDestroy()
    }
}
