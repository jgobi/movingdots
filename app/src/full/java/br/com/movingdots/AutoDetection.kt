package br.com.movingdots

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import br.com.movingdots.auto.TransitionRegistrar

/**
 * Automatic mode for the "full" build, backed by Google Play services activity recognition.
 * The "foss" build has an object with the same API that disables the feature, so common code
 * calls only this facade and never Play services directly.
 */
object AutoDetection {
    /** Play services is present, so Automatic mode can be offered. */
    fun isSupported(context: Context) = TransitionRegistrar.isAvailable(context)

    fun hasPermission(context: Context) = TransitionRegistrar.hasPermission(context)

    /** The trigger activity's name, for logs ("IN_VEHICLE", or "WALKING" in a debug test). */
    fun targetName(context: Context) = TransitionRegistrar.name(TransitionRegistrar.target(context))

    /** Called by [CueModes.set] after the new mode was stored. */
    fun onModeChanged(context: Context, mode: Int) {
        if (mode == CueModes.AUTO) {
            TransitionRegistrar.register(context)
            // Sample fast for the first minutes, so enabling Automatic inside a vehicle reacts quickly.
            TransitionRegistrar.boostSampling(context)
        } else {
            TransitionRegistrar.unregister(context)
            TransitionRegistrar.syncSampling(context)
        }
    }

    /**
     * Every process start: logs why the previous process ended (OEM "cleaners" kill the app
     * often) and repairs Automatic mode's subscriptions, which a reboot clears.
     */
    fun onProcessStart(context: Context) {
        val prefs = Prefs(context)
        val log = DetectionLog(context)
        log.add("Process started" + previousExit(context, prefs).orEmpty())
        if (prefs.mode != CueModes.AUTO) return
        if (!hasPermission(context)) {
            // Revoking the permission kills the process, so this is where it's noticed: without it
            // nothing is detected, and Automatic would wait forever.
            log.add("Activity recognition permission missing -> mode Off")
            CueModes.set(context, CueModes.OFF)
            return
        }
        // Both calls are no-ops when nothing was lost.
        TransitionRegistrar.ensureRegistered(context)
        TransitionRegistrar.syncSampling(context)
    }

    /** After an app update the transition registration is renewed, as cheap insurance. */
    fun onAppUpdated(context: Context) {
        if (Prefs(context).mode == CueModes.AUTO) TransitionRegistrar.register(context)
    }

    /** A session started, ended or changed type: sampling follows (fast during automatic sessions). */
    fun onSessionChanged(context: Context) = TransitionRegistrar.syncSampling(context)

    /**
     * Samples showed the vehicle was left. A fresh transition registration keeps Play services'
     * stuck "in vehicle" state from swallowing the next ride's ENTER.
     */
    fun onVehicleLeft(context: Context) = TransitionRegistrar.register(context)

    private fun previousExit(context: Context, prefs: Prefs): String? {
        val info = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        if (info.timestamp <= prefs.lastExitLogged) return null
        prefs.lastExitLogged = info.timestamp
        return "; previous process ended ${DetectionLog.time(info.timestamp)}: ${reasonName(info.reason)}" +
            (info.description?.let { " ($it)" } ?: "")
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (force-stop)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }
}
