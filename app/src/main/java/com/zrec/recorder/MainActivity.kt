package com.zrec.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_SHOT = "shot"
        private const val REQ_PROJ = 1001
        private const val REQ_PERM = 1002
    }

    private lateinit var prefs: Prefs
    private lateinit var tvVersion: TextView
    private lateinit var tvState: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvNote: TextView
    private lateinit var btnRecord: TextView
    private lateinit var tvResValue: TextView
    private lateinit var tvFpsValue: TextView
    private lateinit var tvCodecValue: TextView
    private lateinit var tvBitrateValue: TextView
    private lateinit var tvAudioValue: TextView
    private lateinit var dot: View
    private lateinit var swOverlay: Switch
    private lateinit var swHide: Switch
    private lateinit var swShake: Switch
    private lateinit var swCount: Switch

    // Empat tab: satu layar satu tugas, jadi halaman Record isinya cuma record.
    private lateinit var pages: List<View>
    private lateinit var tabs: List<LinearLayout>
    private lateinit var tabIcons: List<ImageView>
    private lateinit var tabLabels: List<TextView>
    private var tabIndex = 0
    private lateinit var tvVersionValue: TextView
    private lateinit var tvBuildValue: TextView

    private var codecs: List<Caps.Codec> = emptyList()
    private var codec: Caps.Codec? = null
    private var resOptions: List<Int> = emptyList()
    private var fpsOptions: List<Int> = emptyList()
    private var pending: (() -> Unit)? = null
    private var shotMode = false

    /** Bound once, so refreshUi() does not churn listeners on every stats tick. */
    private var overlayBound = false
    private val overlayListener = CompoundButton.OnCheckedChangeListener { _, v ->
        if (v) {
            askOverlay()
        } else {
            prefs.overlay = false
            startService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvVersion = findViewById(R.id.tvVersion)
        tvState = findViewById(R.id.tvState)
        tvTime = findViewById(R.id.tvTime)
        tvFps = findViewById(R.id.tvFps)
        tvNote = findViewById(R.id.tvNote)
        btnRecord = findViewById(R.id.btnRecord)
        tvResValue = findViewById(R.id.tvResValue)
        tvFpsValue = findViewById(R.id.tvFpsValue)
        tvCodecValue = findViewById(R.id.tvCodecValue)
        tvBitrateValue = findViewById(R.id.tvBitrateValue)
        tvAudioValue = findViewById(R.id.tvAudioValue)
        dot = findViewById(R.id.dot)
        swOverlay = findViewById(R.id.swOverlay)
        swHide = findViewById(R.id.swHide)
        swShake = findViewById(R.id.swShake)
        swCount = findViewById(R.id.swCount)
        tvVersionValue = findViewById(R.id.tvVersionValue)
        tvBuildValue = findViewById(R.id.tvBuildValue)

        bindTabs()

        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val versionName = info?.versionName.orEmpty()
        val build = when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= 28 -> info.longVersionCode
            else -> info.versionCode.toLong()
        }
        tvVersion.text = versionName
        tvVersionValue.text = versionName
        tvBuildValue.text = build.toString()

        val (dw, dh) = Caps.displaySize(this)
        codecs = Caps.probe(dw, dh)
        codec = codecs.firstOrNull { it.mime == prefs.mime } ?: codecs.firstOrNull()

        if (codec == null) {
            tvState.text = getString(R.string.no_encoder)
            btnRecord.isEnabled = false
            btnRecord.alpha = 0.4f
            return
        }

        syncOptions()
        buildRows()
        buildSwitches()

        btnRecord.setOnClickListener {
            if (RecorderService.active) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            } else {
                beginRecord()
            }
        }

        refreshUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra(EXTRA_AUTOSTART, false) && !RecorderService.active) {
            i.removeExtra(EXTRA_AUTOSTART)
            handler.postDelayed({ beginRecord() }, 350)
        }
        if (i.getBooleanExtra(EXTRA_SHOT, false)) {
            i.removeExtra(EXTRA_SHOT)
            shotMode = true
            handler.postDelayed({ beginShot() }, 350)
        }
    }

    // ---------- tabs ----------

    private fun bindTabs() {
        pages = listOf(
            findViewById(R.id.pageRecord),
            findViewById(R.id.pageCapture),
            findViewById(R.id.pageControls),
            findViewById(R.id.pageInfo)
        )
        tabs = listOf(
            findViewById(R.id.tabRecord),
            findViewById(R.id.tabCapture),
            findViewById(R.id.tabControls),
            findViewById(R.id.tabInfo)
        )
        tabIcons = listOf(
            findViewById(R.id.icTabRecord),
            findViewById(R.id.icTabCapture),
            findViewById(R.id.icTabControls),
            findViewById(R.id.icTabInfo)
        )
        tabLabels = listOf(
            findViewById(R.id.tvTabRecord),
            findViewById(R.id.tvTabCapture),
            findViewById(R.id.tvTabControls),
            findViewById(R.id.tvTabInfo)
        )
        tabs.forEachIndexed { i, tab -> tab.setOnClickListener { selectTab(i) } }
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        tabIndex = index.coerceIn(0, pages.lastIndex)
        pages.forEachIndexed { i, p ->
            p.visibility = if (i == tabIndex) View.VISIBLE else View.GONE
        }
        tabs.forEachIndexed { i, tab ->
            val on = i == tabIndex
            tab.setBackgroundResource(if (on) R.drawable.bg_nav_active else 0)
            val tint = getColor(if (on) R.color.txt else R.color.txtFaint)
            tabIcons[i].setColorFilter(tint)
            tabLabels[i].setTextColor(tint)
        }
    }

    override fun onResume() {
        super.onResume()
        RecorderService.onEvent = { runOnUiThread { refreshUi() } }
        // Dragging the bubble onto the close target clears the preference, so sync
        // the switch first. Reading the stale switch here used to bring it back.
        if (prefs.overlay && !hasOverlayPermission()) prefs.overlay = false
        syncOverlaySwitch()
        if (swOverlay.isChecked) startOverlay()
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        RecorderService.onEvent = null
    }

    // ---------- settings rows ----------

    private fun onRow(id: Int, block: () -> Unit) {
        findViewById<View>(id).setOnClickListener { block() }
    }

    private fun buildRows() {
        onRow(R.id.rowRes) {
            val idx = resOptions.indexOf(prefs.quality).coerceAtLeast(0)
            pick(getString(R.string.label_resolution), resOptions.map { label(it) }, idx) { i ->
                prefs.quality = resOptions[i]
                syncOptions()
                refreshUi()
            }
        }

        onRow(R.id.rowFps) {
            val cap = fpsCeiling()
            val idx = fpsOptions.indexOf(prefs.fps).coerceAtLeast(0)
            pick(getString(R.string.dialog_fps_title, label(prefs.quality), cap), fpsOptions.map { "$it fps" }, idx) { i ->
                prefs.fps = fpsOptions[i]
                refreshUi()
            }
        }

        onRow(R.id.rowCodec) {
            val idx = codecs.indexOfFirst { it.mime == codec?.mime }.coerceAtLeast(0)
            pick(getString(R.string.label_codec), codecs.map { it.label }, idx) { i ->
                codec = codecs[i]
                prefs.mime = codecs[i].mime
                syncOptions()
                refreshUi()
            }
        }

        onRow(R.id.rowBitrate) { pickBitrate() }

        onRow(R.id.rowAudio) {
            val labels = audioLabels()
            val idx = prefs.audio.coerceIn(0, labels.size - 1)
            pick(getString(R.string.label_audio), labels, idx) { i ->
                prefs.audio = i
                refreshUi()
            }
        }
    }

    private fun pick(title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        if (items.isEmpty()) return
        AlertDialog.Builder(this, R.style.Theme_Zrec_Dialog)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selected.coerceIn(0, items.size - 1)) { d, i ->
                d.dismiss()
                onPick(i)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun pickBitrate() {
        val view = layoutInflater.inflate(R.layout.dialog_bitrate, null)
        val seek = view.findViewById<SeekBar>(R.id.seekBitrate)
        val value = view.findViewById<TextView>(R.id.tvBitrateValue)
        seek.progress = if (prefs.bitrate <= 0) 0 else ((prefs.bitrate / 1_000_000) - 1).coerceIn(1, 78)
        value.text = bitrateLabel(seek.progress, autoBitrate())
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.bitrate = if (progress == 0) 0 else (progress + 1) * 1_000_000
                value.text = bitrateLabel(progress, autoBitrate())
                refreshUi()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        AlertDialog.Builder(this, R.style.Theme_Zrec_Dialog)
            .setTitle(getString(R.string.label_bitrate))
            .setView(view)
            .setPositiveButton(getString(R.string.dialog_done), null)
            .show()
    }

    private fun buildSwitches() {
        syncOverlaySwitch()

        swHide.isChecked = prefs.hideOverlayOnRecord
        swHide.setOnCheckedChangeListener { _, v -> prefs.hideOverlayOnRecord = v }

        swShake.isChecked = prefs.shake
        swShake.setOnCheckedChangeListener { _, v -> prefs.shake = v }

        swCount.isChecked = prefs.countdown
        swCount.setOnCheckedChangeListener { _, v -> prefs.countdown = v }
    }

    /** The bubble can be closed by dropping it on the close target, so the switch
     *  has to follow the saved preference instead of assuming it stays on. */
    private fun syncOverlaySwitch() {
        if (!overlayBound) {
            overlayBound = true
            swOverlay.setOnCheckedChangeListener(overlayListener)
        }
        val want = prefs.overlay && hasOverlayPermission()
        if (swOverlay.isChecked == want) return
        // Programmatic correction: do not run the listener's side effects for it.
        swOverlay.setOnCheckedChangeListener(null)
        swOverlay.isChecked = want
        swOverlay.setOnCheckedChangeListener(overlayListener)
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun askOverlay() {
        if (hasOverlayPermission()) {
            prefs.overlay = true
            startOverlay()
            return
        }
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startOverlay() {
        runCatching { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW)) }
    }

    // ---------- values ----------

    private fun syncOptions() {
        val c = codec ?: return
        resOptions = c.supported
        if (prefs.quality !in resOptions) {
            prefs.quality = if (1080 in resOptions) 1080 else resOptions.first()
        }
        val (dw, dh) = Caps.displaySize(this)
        fpsOptions = Caps.fpsOptions(c, prefs.quality, dw, dh)
        if (prefs.fps !in fpsOptions) prefs.fps = fpsOptions.last()
        if (prefs.audio >= audioLabels().size) prefs.audio = Config.AUDIO_MIC
    }

    private fun audioLabels(): List<String> =
        if (Build.VERSION.SDK_INT >= 29) listOf("Off", "Mic", "Internal", "Mic + internal")
        else listOf("Off", "Mic")

    private fun label(quality: Int): String = when (quality) {
        2160 -> "4K"
        1440 -> "1440p"
        else -> "${quality}p"
    }

    /** The lower of the app's per-resolution ceiling and what the encoder reports. */
    private fun fpsCeiling(): Int {
        val allowed = Caps.FPS_CEILING[prefs.quality] ?: 60
        return minOf(allowed, codec?.maxFps ?: 60)
    }

    private fun autoBitrate(): Int {
        val (dw, dh) = Caps.displaySize(this)
        val (w, h) = Caps.sizeFor(prefs.quality, dw, dh)
        return Caps.autoBitrate(w, h, prefs.fps).coerceAtMost(codec?.bitrateMax ?: Int.MAX_VALUE)
    }

    private fun bitrateLabel(progress: Int, auto: Int): String =
        if (progress == 0) "Auto · ${auto / 1_000_000} Mbps" else "${progress + 1} Mbps"

    private fun time(secs: Long): String =
        String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60)

    private fun refreshUi() {
        tvResValue.text = label(prefs.quality)
        tvFpsValue.text = "${prefs.fps} fps"
        tvCodecValue.text = codec?.label ?: "-"
        tvBitrateValue.text = if (prefs.bitrate > 0) {
            "${prefs.bitrate / 1_000_000} Mbps"
        } else {
            "Auto · ${autoBitrate() / 1_000_000} Mbps"
        }
        val labels = audioLabels()
        tvAudioValue.text = labels.getOrElse(prefs.audio) { labels.first() }

        val notes = mutableListOf<String>()
        if (prefs.audio == Config.AUDIO_INTERNAL || prefs.audio == Config.AUDIO_BOTH) {
            notes += getString(R.string.note_internal)
        }
        val allowed = Caps.FPS_CEILING[prefs.quality] ?: 60
        val encoderMax = codec?.maxFps ?: 0
        if (encoderMax < allowed) {
            notes += getString(R.string.note_fps, encoderMax)
        }
        if (codec?.hardware == false) {
            notes += getString(R.string.note_software)
        }
        tvNote.text = notes.joinToString("\n")
        tvNote.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE

        if (RecorderService.active) {
            dot.visibility = View.VISIBLE
            tvState.text = getString(
                if (RecorderService.paused) R.string.state_paused else R.string.state_recording
            )
            tvState.setTextColor(getColor(R.color.txt))
            tvTime.setTextColor(getColor(R.color.txt))
            tvTime.text = time(RecorderService.lastSeconds)
            tvFps.visibility = View.VISIBLE
            tvFps.text = "${RecorderService.lastFps} fps"
            btnRecord.text = getString(R.string.btn_stop)
            btnRecord.setBackgroundResource(R.drawable.bg_btn_stop)
            btnRecord.setTextColor(getColor(R.color.onAccent))
            return
        }

        dot.visibility = View.GONE
        tvState.text = getString(R.string.state_ready)
        tvState.setTextColor(getColor(R.color.txtDim))
        tvTime.setTextColor(getColor(R.color.txtFaint))
        tvTime.text = "00:00"
        tvFps.visibility = View.GONE
        btnRecord.text = getString(R.string.btn_start)
        btnRecord.setBackgroundResource(R.drawable.bg_btn_record)
        btnRecord.setTextColor(getColor(R.color.bg))

        syncOverlaySwitch()
    }

    // ---------- start ----------

    private fun needPermissions(): Array<String> {
        val need = mutableListOf<String>()
        if (prefs.audio != Config.AUDIO_OFF &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.RECORD_AUDIO
        // Android 8 and 9 still need this to write into MediaStore.
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        return need.toTypedArray()
    }

    private fun beginRecord() {
        val need = needPermissions()
        if (need.isNotEmpty()) {
            pending = { requestProjection() }
            requestPermissions(need, REQ_PERM)
            return
        }
        requestProjection()
    }

    private fun beginShot() {
        val need = needPermissions()
        if (need.isNotEmpty()) {
            pending = { requestProjection() }
            requestPermissions(need, REQ_PERM)
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
        }
        val action = pending
        pending = null
        action?.invoke()
        refreshUi()
    }

    private fun requestProjection() {
        val go = {
            val pm = projectionManager()
            // Android 14+ menampilkan pilihan "satu aplikasi / seluruh layar". Zrec selalu
            // merekam seluruh layar, jadi pilihannya dibuang lewat createConfigForDefaultDisplay():
            // dialog yang muncul cuma izin mulai merekam, tanpa langkah pilih aplikasi.
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                pm.createScreenCaptureIntent()
            }
            startActivityForResult(intent, REQ_PROJ)
        }
        if (prefs.countdown && !shotMode) countdown(go) else go()
    }

    private fun countdown(then: () -> Unit) {
        var n = 3
        btnRecord.isEnabled = false
        val runnable = object : Runnable {
            override fun run() {
                if (n > 0) {
                    tvState.text = getString(R.string.state_countdown, n)
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
            shotMode = false
            refreshUi()
            return
        }
        val (dw, dh) = Caps.displaySize(this)
        val (w, h) = Caps.sizeFor(prefs.quality, dw, dh)
        val bit = if (prefs.bitrate > 0) prefs.bitrate else autoBitrate()
        val action = if (shotMode) RecorderService.ACTION_SHOT_ONLY else RecorderService.ACTION_START
        shotMode = false
        val i = Intent(this, RecorderService::class.java).apply {
            this.action = action
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
}
