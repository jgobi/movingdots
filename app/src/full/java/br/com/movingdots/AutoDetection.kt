package br.com.movingdots

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import br.com.movingdots.auto.ActivitySampler

/**
 * "Turn off when I leave the vehicle" for the "full" build, backed by Google Play services
 * activity recognition. The "foss" build has an object with the same API that reports the feature
 * as unsupported, so common code never touches Play services directly.
 */
object AutoDetection {
    /** Play services is present, so the option can be offered. */
    fun isSupported(context: Context) = ActivitySampler.isAvailable(context)

    fun hasPermission(context: Context) = ActivitySampler.hasPermission(context)

    /** The option is on and can work right now. */
    fun isEnabled(context: Context) =
        Prefs(context).autoStop && isSupported(context) && hasPermission(context)

    /** A session started (or the option was enabled during one): sample until it ends. */
    fun startWatching(context: Context) = ActivitySampler.start(context)

    fun stopWatching(context: Context) = ActivitySampler.stop(context)

    /**
     * Every process start: logs why the previous process ended (OEM "cleaners" kill the app
     * often), and switches the option off if its permission was revoked.
     */
    fun onProcessStart(context: Context) {
        val prefs = Prefs(context)
        val log = DetectionLog(context)
        // Diagnostics for test builds; release builds skip the exit-reason lookup.
        if (BuildConfig.DEBUG) log.add("Process started" + previousExit(context, prefs).orEmpty())
        if (prefs.autoStop && !hasPermission(context)) {
            log.add("Activity recognition permission missing -> turn-off-when-leaving disabled")
            prefs.autoStop = false
        }
    }

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
