package br.com.movingdots.auto

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import br.com.movingdots.CueModes
import br.com.movingdots.DetectionLog
import br.com.movingdots.MotionCueService
import br.com.movingdots.Prefs
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Play services subscriptions for Automatic mode:
 *  - Raw activity sampling, the main detector: on a real ride it reported IN_VEHICLE at 88-98%
 *    while transition events never arrived. Every 2 min while waiting, every 30 s during an
 *    automatic session or a short boost.
 *  - IN_VEHICLE enter/exit transitions, a shortcut when they arrive in time. Play services keeps
 *    transition state per registration and it can get stuck "in vehicle", so each [register]
 *    creates a fresh registration with a new PendingIntent, resetting it the way reinstalling does.
 */
object TransitionRegistrar {
    private const val TAG = DetectionLog.TAG
    const val ACTION_TRANSITION = "br.com.movingdots.TRANSITION"
    const val ACTION_SAMPLE = "br.com.movingdots.SAMPLE"
    private const val EXTRA_GENERATION = "generation"
    private const val SAMPLE_REQUEST_CODE = -1
    private const val FAST_SAMPLE_MS = 30_000L
    private const val IDLE_SAMPLE_MS = 120_000L
    private const val BOOST_MS = 3 * 60_000L

    /** The activity that triggers the cues: IN_VEHICLE, or WALKING in a debug test. */
    fun target(context: Context): Int =
        if (Prefs(context).testWalking) DetectedActivity.WALKING else DetectedActivity.IN_VEHICLE

    fun name(type: Int): String = when (type) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.UNKNOWN -> "UNKNOWN"
        DetectedActivity.TILTING -> "TILTING"
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.RUNNING -> "RUNNING"
        else -> "TYPE_$type"
    }

    fun isAvailable(context: Context) =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun intent(context: Context, requestCode: Int, action: String) =
        Intent(context, VehicleTransitionReceiver::class.java).setAction(action).putExtra(EXTRA_GENERATION, requestCode)

    private fun pendingIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, intent(context, requestCode, action),
            // Mutable: Play services fills in the result extras.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /** The PendingIntent still exists: a reboot or a cancel removes it (and its registration). */
    private fun exists(context: Context, requestCode: Int, action: String) = PendingIntent.getBroadcast(
        context, requestCode, intent(context, requestCode, action),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE,
    ) != null

    /** Replaces any existing transition registration with a fresh one. */
    fun register(context: Context) {
        if (!hasPermission(context) || !isAvailable(context)) return
        unregister(context)
        val prefs = Prefs(context)
        val generation = prefs.transitionGeneration + 1
        prefs.transitionGeneration = generation
        val target = target(context)
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(target)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(target)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
        )
        val log = DetectionLog(context)
        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(
                    ActivityTransitionRequest(transitions), pendingIntent(context, generation, ACTION_TRANSITION),
                )
                .addOnSuccessListener { log.add("Registered ${name(target)} transitions (gen $generation)") }
                .addOnFailureListener { log.add("Registration of ${name(target)} transitions FAILED (gen $generation): $it") }
        } catch (e: SecurityException) {
            log.add("Registration failed: activity recognition permission revoked")
        }
    }

    /** Re-registers if the current registration's PendingIntent is gone (e.g. after a reboot). */
    fun ensureRegistered(context: Context) {
        if (!hasPermission(context) || !isAvailable(context)) return
        val generation = Prefs(context).transitionGeneration
        if (!exists(context, generation, ACTION_TRANSITION)) {
            DetectionLog(context).add("Registration gen $generation missing -> re-registering")
            register(context)
        }
    }

    fun unregister(context: Context) {
        if (!isAvailable(context)) return
        val generation = Prefs(context).transitionGeneration
        val pi = pendingIntent(context, generation, ACTION_TRANSITION)
        try {
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pi)
                .addOnSuccessListener {
                    pi.cancel()
                    DetectionLog(context).add("Unregistered transitions (gen $generation)")
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "Activity recognition permission revoked", e)
        }
    }

    /** True if a transition [intent] came from the current registration (a removed one may still deliver). */
    fun isCurrent(context: Context, intent: Intent): Boolean =
        intent.getIntExtra(EXTRA_GENERATION, Int.MIN_VALUE) == Prefs(context).transitionGeneration

    /** Samples every 30 s for a few minutes: after switching to Automatic, or an old ENTER. */
    fun boostSampling(context: Context) {
        Prefs(context).boostUntilMillis = System.currentTimeMillis() + BOOST_MS
        DetectionLog(context).add("Fast sampling for ${BOOST_MS / 60_000} min")
        syncSampling(context)
    }

    /**
     * Brings the sampling request in line with the current state. Cheap when nothing changed (no
     * Play services call), so it can run on every sample and process start.
     */
    fun syncSampling(context: Context) {
        val prefs = Prefs(context)
        val desired = when {
            prefs.mode != CueModes.AUTO || !hasPermission(context) || !isAvailable(context) -> 0L
            MotionCueService.isAutoSession -> FAST_SAMPLE_MS
            prefs.boostUntilMillis > System.currentTimeMillis() -> FAST_SAMPLE_MS
            else -> IDLE_SAMPLE_MS
        }
        val current = if (exists(context, SAMPLE_REQUEST_CODE, ACTION_SAMPLE)) prefs.samplingIntervalMs else 0L
        if (desired == current) return
        if (desired == 0L) stopSampling(context) else startSampling(context, desired)
    }

    /** Calling again with another interval replaces the previous request (same PendingIntent). */
    private fun startSampling(context: Context, intervalMs: Long) {
        val prefs = Prefs(context)
        prefs.samplingIntervalMs = intervalMs
        try {
            ActivityRecognition.getClient(context)
                .requestActivityUpdates(intervalMs, pendingIntent(context, SAMPLE_REQUEST_CODE, ACTION_SAMPLE))
                .addOnSuccessListener { DetectionLog(context).add("Sampling every ${intervalMs / 1000}s") }
                .addOnFailureListener {
                    prefs.samplingIntervalMs = 0L
                    DetectionLog(context).add("Sampling FAILED to start: $it")
                }
        } catch (e: SecurityException) {
            prefs.samplingIntervalMs = 0L
            DetectionLog(context).add("Sampling failed: activity recognition permission revoked")
        }
    }

    private fun stopSampling(context: Context) {
        // A stored interval of 0 already makes syncSampling() treat sampling as stopped, so the
        // PendingIntent can be cancelled once Play services has dropped the request.
        Prefs(context).samplingIntervalMs = 0L
        val pi = pendingIntent(context, SAMPLE_REQUEST_CODE, ACTION_SAMPLE)
        if (!isAvailable(context)) {
            pi.cancel()
            return
        }
        try {
            ActivityRecognition.getClient(context).removeActivityUpdates(pi)
                .addOnSuccessListener {
                    // Unless sampling was restarted meanwhile (it reuses this PendingIntent).
                    if (Prefs(context).samplingIntervalMs == 0L) pi.cancel()
                    DetectionLog(context).add("Sampling stopped")
                }
        } catch (e: SecurityException) {
            pi.cancel()
            Log.w(TAG, "Activity recognition permission revoked", e)
        }
    }
}
