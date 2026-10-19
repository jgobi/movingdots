package br.com.movingdots

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * The single source of truth for whether the dots are on. Sessions are always started by hand
 * (app or Quick Settings tile); with "turn off when I leave the vehicle" they may end by themselves.
 */
object CueModes {
    const val OFF = 0
    const val ON = 1

    /** The tile and notification show a car while the dots can turn themselves off. */
    fun icon(context: Context) =
        if (AutoDetection.isEnabled(context)) R.drawable.ic_mode_auto else R.drawable.ic_mode_on

    /** Applies [mode]. Returns false (and falls back to OFF) if the dots couldn't be started. */
    fun set(context: Context, mode: Int): Boolean {
        val prefs = Prefs(context)
        if (mode == ON && !Settings.canDrawOverlays(context)) return false
        prefs.mode = mode
        if (mode == OFF) {
            MotionCueService.stop(context)
            return true
        }
        return try {
            MotionCueService.start(context)
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w("MotionCue", "Not allowed to start cues now", e)
            prefs.mode = OFF
            false
        }
    }
}
