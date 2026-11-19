package com.zrec.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import java.util.Locale

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.zrec.START"
        const val ACTION_STOP = "com.zrec.STOP"
        const val ACTION_PAUSE = "com.zrec.PAUSE"
        const val ACTION_RESUME = "com.zrec.RESUME"

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

        @Volatile var active = false
        @Volatile var paused = false
        @Volatile var lastFps = 0
        @Volatile var lastSeconds = 0L

        /** Called on the main thread whenever recording state or stats change. */
        @Volatile var onEvent: (() -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var cfg: Config? = null
    private var quality = 1080
    private var finishing = false

    private val main = Handler(Looper.getMainLooper())

    private val stats: (Int, Long) -> Unit = { fps, secs ->
        lastFps = fps
        lastSeconds = secs
        publish()
    }
    private val fatal: (String) -> Unit = { finishNow() }

    /**
     * Rotating the device mid-recording would otherwise leave the frame at the old
     * orientation, so the session is closed and a new file is opened at the new size.
     */
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
                NotificationChannel(CHANNEL, "Rekaman", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishNow()
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
        startForegroundCompat(buildNotification("Menyiapkan...", "Merekam layar", false))

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
        publish()
    }

    private fun maybeRotate() {
        val proj = projection ?: return
        val cur = cfg ?: return
        if (recorder == null) return
        val (dw, dh) = Caps.displaySize(this)
        val (nw, nh) = Caps.sizeFor(quality, dw, dh)
        if (nw == cur.width && nh == cur.height) return
        val old = recorder ?: return
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
            paused -> "Dijeda"
            else -> "Merekam"
        }
        val body = if (active) {
            String.format(Locale.US, "%d fps  -  %02d:%02d", lastFps, lastSeconds / 60, lastSeconds % 60)
        } else "Siap"
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
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(if (isPaused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, if (isPaused) "Lanjut" else "Jeda", toggle).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF, n)
        }
    }

    private fun finishNow() {
        if (finishing) return
        finishing = true
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
