package com.zrec.recorder

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile.
 *
 * A take cannot be started from here: Android only hands out a screen capture
 * session after the system consent dialog, and a tile cannot show it. So an idle
 * tap opens the app at its start path, and a running take is stopped in place.
 */
class RecorderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (RecorderService.active) {
            startService(
                Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
            )
        } else {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_AUTOSTART, true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = RecorderService.active
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(if (on) R.string.tile_stop else R.string.tile_start)
        t.icon = Icon.createWithResource(this, R.drawable.ic_notif)
        t.updateTile()
    }
}
