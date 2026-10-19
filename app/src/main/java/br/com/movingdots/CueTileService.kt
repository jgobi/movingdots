package br.com.movingdots

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.graphics.drawable.Icon
import android.service.quicksettings.TileService

/** Quick Settings tile: turns the dots on and off. */
class CueTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (Prefs(this).mode == CueModes.ON) {
            CueModes.set(this, CueModes.OFF)
        } else if (!Settings.canDrawOverlays(this) || !CueModes.set(this, CueModes.ON)) {
            // The overlay permission is granted in the app; a refused background start also falls back to it.
            openApp()
            return
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Prefs(this).mode == CueModes.ON
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        // Icon-only panels (e.g. Infinix XOS) show no text: a car marks "turns off when I leave the vehicle".
        tile.icon = Icon.createWithResource(this, CueModes.icon(this))
        val name = getString(if (on) R.string.mode_on else R.string.mode_off)
        tile.subtitle = name
        tile.stateDescription = name
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
