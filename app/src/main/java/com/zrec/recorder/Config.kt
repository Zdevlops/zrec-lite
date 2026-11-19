package com.zrec.recorder

import android.content.Context

data class Config(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int,
    val bitrate: Int,
    val mime: String,
    val audio: Int,
    val countdown: Int
) {
    companion object {
        const val AUDIO_OFF = 0
        const val AUDIO_MIC = 1
        const val AUDIO_INTERNAL = 2
        const val AUDIO_BOTH = 3
    }
}

/** Everything the user can change, persisted in one small prefs file. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("zrec", Context.MODE_PRIVATE)

    var quality: Int
        get() = sp.getInt("quality", 1080)
        set(v) = sp.edit().putInt("quality", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 60)
        set(v) = sp.edit().putInt("fps", v).apply()

    var mime: String
        get() = sp.getString("mime", "video/avc")!!
        set(v) = sp.edit().putString("mime", v).apply()

    /** 0 = auto (computed from resolution and fps). */
    var bitrate: Int
        get() = sp.getInt("bitrate", 0)
        set(v) = sp.edit().putInt("bitrate", v).apply()

    var audio: Int
        get() = sp.getInt("audio", Config.AUDIO_MIC)
        set(v) = sp.edit().putInt("audio", v).apply()

    var countdown: Boolean
        get() = sp.getBoolean("countdown", true)
        set(v) = sp.edit().putBoolean("countdown", v).apply()

    var overlay: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var hideOverlayOnRecord: Boolean
        get() = sp.getBoolean("hideOnRecord", true)
        set(v) = sp.edit().putBoolean("hideOnRecord", v).apply()

    var shake: Boolean
        get() = sp.getBoolean("shake", true)
        set(v) = sp.edit().putBoolean("shake", v).apply()
}
