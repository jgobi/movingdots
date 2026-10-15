package br.com.movingdots

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Restores the current mode after a reboot or app update. Automatic mode's subscriptions are
 * already repaired by MovingDotsApp.onCreate, which runs first in the same process.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!Settings.canDrawOverlays(context)) {
            // Permission revoked meanwhile: nothing can be shown, so don't claim to be on.
            if (prefs.mode != CueModes.OFF) CueModes.set(context, CueModes.OFF)
            return
        }
        when (prefs.mode) {
            CueModes.AUTO -> if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AutoDetection.onAppUpdated(context)
            CueModes.ON -> try {
                MotionCueService.start(context)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                DetectionLog(context).add("Could not restore On mode after ${intent.action}: $e -> mode Off")
                CueModes.set(context, CueModes.OFF)
            }
        }
    }
}
