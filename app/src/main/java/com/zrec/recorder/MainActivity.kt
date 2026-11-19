package com.zrec.recorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var tvLabel: TextView
    private lateinit var tvMain: TextView
    private lateinit var tvSub: TextView
    private lateinit var btnRecord: TextView
    private lateinit var tvBitrate: TextView
    private lateinit var tvFpsNote: TextView
    private lateinit var tvAudioNote: TextView
    private lateinit var seek: SeekBar
    private lateinit var swCount: Switch

    private var codecs: List<Caps.Codec> = emptyList()
    private var codec: Caps.Codec? = null
    private var fpsChips: List<TextView> = emptyList()
    private var resChips: List<TextView> = emptyList()
    private var pending: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvLabel = findViewById(R.id.tvStatusLabel)
        tvMain = findViewById(R.id.tvStatusMain)
        tvSub = findViewById(R.id.tvStatusSub)
        btnRecord = findViewById(R.id.btnRecord)
        tvBitrate = findViewById(R.id.tvBitrate)
        tvFpsNote = findViewById(R.id.tvFpsNote)
        tvAudioNote = findViewById(R.id.tvAudioNote)
        seek = findViewById(R.id.seekBitrate)
        swCount = findViewById(R.id.swCountdown)

        codecs = Caps.probe()
        codec = codecs.firstOrNull { it.mime == prefs.mime } ?: codecs.firstOrNull()

        if (codec == null) {
            tvMain.text = "Encoder tidak didukung"
            tvSub.text = "Perangkat ini tidak punya hardware encoder H.264/H.265."
            btnRecord.isEnabled = false
            return
        }

        buildRes()
        buildCodec()
        buildAudio()
        buildFps()
        buildBitrate()

        swCount.isChecked = prefs.countdown
        swCount.setOnCheckedChangeListener { _, v -> prefs.countdown = v }

        btnRecord.setOnClickListener {
            if (RecorderService.active) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            } else {
                beginRecord()
            }
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        RecorderService.onEvent = { runOnUiThread { refreshUi() } }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        RecorderService.onEvent = null
    }

    // ---------- chips ----------

    private fun chips(container: LinearLayout, labels: List<String>, pick: (Int) -> Unit): List<TextView> {
        container.removeAllViews()
        return labels.mapIndexed { i, s ->
            val tv = TextView(this).apply {
                text = s
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = getDrawable(R.drawable.bg_chip)
                setTextColor(getColorStateList(R.color.chip_text))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { pick(i) }
            }
            container.addView(tv)
            tv
        }
    }

    private fun select(list: List<TextView>, index: Int) {
        list.forEachIndexed { i, v -> v.isSelected = i == index }
    }

    private fun buildRes() {
        val c = codec ?: return
        val options = c.supported.map { it.first }
        val idx = options.indexOf(prefs.quality).let { if (it < 0) 0 else it }
        prefs.quality = options[idx]
        val views = chips(findViewById(R.id.chipsRes), options.map { "${it}p" }) { i ->
            prefs.quality = options[i]
            select(resChips, i)
            buildFps()
            refreshUi()
        }
        resChips = views
        select(views, idx)
    }

    private fun buildFps() {
        val c = codec ?: return
        val (dw, dh) = Caps.displaySize(this)
        val opts = Caps.fpsOptions(c, prefs.quality, dw, dh)
        if (prefs.fps !in opts) prefs.fps = opts.last()
        val idx = opts.indexOf(prefs.fps)
        fpsChips = chips(findViewById(R.id.chipsFps), opts.map { "$it" }) { i ->
            prefs.fps = opts[i]
            select(fpsChips, i)
            refreshUi()
        }
        select(fpsChips, idx)
        tvFpsNote.text = if (c.maxFps < 120) {
            "Encoder HP ini mendukung sampai ${c.maxFps} fps."
        } else {
            "Pilih 60 untuk file kecil, 90-120 untuk gerakan paling halus."
        }
    }

    private fun buildCodec() {
        val views = chips(findViewById(R.id.chipsCodec), codecs.map { it.label }) { i ->
            codec = codecs[i]
            prefs.mime = codecs[i].mime
            buildCodec()
            buildRes()
            buildFps()
            refreshUi()
        }
        select(views, codecs.indexOfFirst { it.mime == codec?.mime }.coerceAtLeast(0))
    }

    private fun buildAudio() {
        val labels = if (Build.VERSION.SDK_INT >= 29) {
            listOf("Off", "Mic", "Internal", "Mix")
        } else {
            listOf("Off", "Mic")
        }
        if (prefs.audio >= labels.size) prefs.audio = Config.AUDIO_MIC
        val views = chips(findViewById(R.id.chipsAudio), labels) { i ->
            prefs.audio = i
            buildAudio()
            refreshUi()
        }
        select(views, prefs.audio.coerceIn(0, labels.size - 1))
        tvAudioNote.text = when (prefs.audio) {
            Config.AUDIO_OFF -> "Tanpa suara."
            Config.AUDIO_MIC -> "Suara sekitar lewat mikrofon."
            Config.AUDIO_INTERNAL -> "Suara aplikasi/game. Sebagian aplikasi memblokir perekaman."
            else -> "Mikrofon dan suara aplikasi digabung."
        }
    }

    private fun buildBitrate() {
        val auto = autoBitrate()
        val stored = prefs.bitrate
        val progress = if (stored <= 0) 0 else ((stored / 1_000_000) - 1).coerceIn(1, 38)
        seek.progress = progress
        tvBitrate.text = if (progress == 0) {
            String.format(Locale.US, "Auto · %d Mbps", auto / 1_000_000)
        } else {
            String.format(Locale.US, "%d Mbps", (progress + 1))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                prefs.bitrate = if (value == 0) 0 else (value + 1) * 1_000_000
                tvBitrate.text = if (value == 0) {
                    String.format(Locale.US, "Auto · %d Mbps", autoBitrate() / 1_000_000)
                } else {
                    String.format(Locale.US, "%d Mbps", value + 1)
                }
                refreshUi()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ---------- state ----------

    private fun autoBitrate(): Int {
        val (dw, dh) = Caps.displaySize(this)
        val (w, h) = Caps.sizeFor(prefs.quality, dw, dh)
        return Caps.autoBitrate(w, h, prefs.fps).coerceAtMost(codec?.bitrateMax ?: Int.MAX_VALUE)
    }

    private fun refreshUi() {
        if (RecorderService.active) {
            tvLabel.text = if (RecorderService.paused) "DIJEDA" else "MEREKAM"
            tvLabel.setTextColor(getColor(R.color.accent))
            tvMain.text = String.format(
                Locale.US, "%02d:%02d", RecorderService.lastSeconds / 60, RecorderService.lastSeconds % 60
            )
            tvSub.text = "${RecorderService.lastFps} fps · ${prefs.quality}p"
            btnRecord.text = "Stop"
            btnRecord.setBackgroundResource(R.drawable.bg_btn_ghost)
            btnRecord.setTextColor(getColor(R.color.accent))
            return
        }
        tvLabel.text = "SIAP"
        tvLabel.setTextColor(getColor(R.color.accent))
        tvMain.text = "Siap merekam"
        val (dw, dh) = Caps.displaySize(this)
        val (w, h) = Caps.sizeFor(prefs.quality, dw, dh)
        val bit = if (prefs.bitrate > 0) prefs.bitrate else autoBitrate()
        val aud = when (prefs.audio) {
            Config.AUDIO_OFF -> "tanpa audio"
            Config.AUDIO_MIC -> "mic"
            Config.AUDIO_INTERNAL -> "audio internal"
            else -> "mic + internal"
        }
        tvSub.text = String.format(
            Locale.US, "%dx%d · %d fps · %s · %d Mbps · %s",
            w, h, prefs.fps, codec?.label ?: "-", bit / 1_000_000, aud
        )
        btnRecord.text = getString(R.string.btn_start)
        btnRecord.setBackgroundResource(R.drawable.bg_btn_record)
        btnRecord.setTextColor(Color.WHITE)
    }

    // ---------- start ----------

    private fun beginRecord() {
        val need = mutableListOf<String>()
        if (prefs.audio != Config.AUDIO_OFF &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS

        if (need.isNotEmpty()) {
            pending = { requestProjection() }
            requestPermissions(need.toTypedArray(), REQ_PERM)
            return
        }
        requestProjection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        val audioIdx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (audioIdx >= 0 && grantResults.getOrNull(audioIdx) != PackageManager.PERMISSION_GRANTED) {
            prefs.audio = Config.AUDIO_OFF
            buildAudio()
        }
        val action = pending
        pending = null
        action?.invoke()
    }

    private fun requestProjection() {
        val action = { startActivityForResult(projectionManager().createScreenCaptureIntent(), REQ_PROJ) }
        if (prefs.countdown) {
            countdown(action)
        } else {
            action()
        }
    }

    private fun countdown(then: () -> Unit) {
        var n = 3
        btnRecord.isEnabled = false
        val runnable = object : Runnable {
            override fun run() {
                if (n > 0) {
                    tvLabel.text = "MULAI"
                    tvMain.text = "Mulai dalam $n"
                    n--
                    handler.postDelayed(this, 1000)
                } else {
                    btnRecord.isEnabled = true
                    then()
                }
            }
        }
        runnable.run()
    }

    private fun projectionManager() = getSystemService(MediaProjectionManager::class.java)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJ || resultCode != RESULT_OK || data == null) {
            refreshUi()
            return
        }
        val (dw, dh) = Caps.displaySize(this)
        val (w, h) = Caps.sizeFor(prefs.quality, dw, dh)
        val bit = if (prefs.bitrate > 0) prefs.bitrate else autoBitrate()
        val i = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
            putExtra(RecorderService.EXTRA_RESULT, resultCode)
            putExtra(RecorderService.EXTRA_DATA, data)
            putExtra(RecorderService.EXTRA_W, w)
            putExtra(RecorderService.EXTRA_H, h)
            putExtra(RecorderService.EXTRA_DPI, resources.displayMetrics.densityDpi)
            putExtra(RecorderService.EXTRA_FPS, prefs.fps)
            putExtra(RecorderService.EXTRA_BITRATE, bit)
            putExtra(RecorderService.EXTRA_MIME, codec?.mime ?: "video/avc")
            putExtra(RecorderService.EXTRA_AUDIO, prefs.audio)
            putExtra(RecorderService.EXTRA_QUALITY, prefs.quality)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        handler.postDelayed({ refreshUi() }, 400)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PROJ = 1001
        private const val REQ_PERM = 1002
    }
}
