package br.com.movingdots

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import br.com.movingdots.auto.DetectionLog
import br.com.movingdots.auto.TransitionRegistrar

/**
 * Logs every process start with the reason the previous process ended, and repairs the vehicle
 * detection registration if it was lost. A force-stop (which some OEM "cleaners" do) cancels the
 * app's PendingIntents, silently ending automatic mode until the app runs again.
 */
class MovingDotsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        val log = DetectionLog(this)
        log.add("Process started" + previousExit(prefs).orEmpty())
        if (prefs.mode != CueModes.AUTO) return
        if (!TransitionRegistrar.hasPermission(this)) {
            // Revoking the permission kills the process, so this is where it's noticed: without it
            // nothing is detected, and Automatic would wait forever.
            log.add("Activity recognition permission missing -> mode Off")
            CueModes.set(this, CueModes.OFF)
            return
        }
        // Subscriptions don't survive a reboot; both calls are no-ops when nothing was lost.
        TransitionRegistrar.ensureRegistered(this)
        TransitionRegistrar.syncSampling(this)
    }

    private fun previousExit(prefs: Prefs): String? {
        val info = getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return null
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
