package br.com.movingdots

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.provider.Settings
import android.util.Log
import br.com.movingdots.auto.DetectionLog
import br.com.movingdots.auto.TransitionRegistrar

/**
 * The single source of truth for what the app is doing:
 *  - OFF: no dots, no vehicle detection.
 *  - ON: dots shown until the user turns them off.
 *  - AUTO: vehicle detection registered; dots shown only while in a vehicle.
 */
object CueModes {
    const val OFF = 0
    const val ON = 1
    const val AUTO = 2

    fun icon(mode: Int) = if (mode == AUTO) R.drawable.ic_mode_auto else R.drawable.ic_mode_on

    /** Applies [mode]. Returns false (and falls back to OFF) if the dots couldn't be started. */
    fun set(context: Context, mode: Int): Boolean {
        val prefs = Prefs(context)
        if (mode != OFF && !Settings.canDrawOverlays(context)) return false
        if (mode == AUTO || prefs.mode == AUTO) {
            DetectionLog(context).add("Mode -> ${arrayOf("Off", "On", "Automatic")[mode]}")
        }
        prefs.mode = mode
        if (mode == AUTO) {
            TransitionRegistrar.register(context)
            // Sample fast for the first minutes, so enabling Automatic inside a vehicle reacts quickly.
            TransitionRegistrar.boostSampling(context)
        } else {
            TransitionRegistrar.unregister(context)
            TransitionRegistrar.syncSampling(context)
        }
        when (mode) {
            OFF -> MotionCueService.stop(context)
            ON -> try {
                MotionCueService.start(context, auto = false)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w("MotionCue", "Not allowed to start cues now", e)
                prefs.mode = OFF
                return false
            }
            // The dots wait for vehicle detection. A session showing from On mode is ended rather
            // than made automatic, so it can't outlive a ride it never detected.
            AUTO -> MotionCueService.stop(context)
        }
        return true
    }
}
