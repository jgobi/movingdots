package br.com.movingdots

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Restores the dots after a reboot or app update if they were on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs(context).mode != CueModes.ON) return
        if (!Settings.canDrawOverlays(context)) {
            // Permission revoked meanwhile: nothing can be shown, so don't claim to be on.
            CueModes.set(context, CueModes.OFF)
            return
        }
        try {
            MotionCueService.start(context)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            DetectionLog(context).add("Could not restore the dots after ${intent.action}: $e -> Off")
            CueModes.set(context, CueModes.OFF)
        }
    }
}
