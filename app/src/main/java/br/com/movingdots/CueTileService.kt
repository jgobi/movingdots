package br.com.movingdots

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.graphics.drawable.Icon
import android.service.quicksettings.TileService

/** Quick Settings tile: switches between Off and the last active mode (On or Automatic). */
class CueTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val prefs = Prefs(this)
        if (prefs.mode != CueModes.OFF) {
            CueModes.set(this, CueModes.OFF)
        } else {
            val target = prefs.lastActiveMode
            val ready = Settings.canDrawOverlays(this) &&
                (target != CueModes.AUTO || AutoDetection.hasPermission(this))
            // Permissions are granted in the app; a refused background start also falls back to it.
            if (!ready || !CueModes.set(this, target)) {
                openApp()
                return
            }
        }
        refresh()
    }

    private fun modeName(mode: Int) = when (mode) {
        CueModes.ON -> R.string.mode_on
        CueModes.AUTO -> R.string.mode_auto
        else -> R.string.mode_off
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val mode = Prefs(this).mode
        tile.state = if (mode == CueModes.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        val name = getString(modeName(mode))
        // Icon-only panels (e.g. Infinix XOS) show no text, so the icon tells On and Automatic apart;
        // Off shows the last active mode's icon, greyed out by the inactive state.
        val iconMode = if (mode == CueModes.OFF) Prefs(this).lastActiveMode else mode
        tile.icon = Icon.createWithResource(this, CueModes.icon(iconMode))
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
