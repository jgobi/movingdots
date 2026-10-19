package br.com.movingdots.auto

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import br.com.movingdots.DetectionLog
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.DetectedActivity

/**
 * Play services activity sampling, requested only while a session with "turn off when I leave the
 * vehicle" runs. While the dots are on the app has a foreground service, and samples then arrived
 * every few seconds on every logged ride; in the background they were withheld, which is why
 * sessions are always started by hand.
 */
object ActivitySampler {
    private const val ACTION_SAMPLE = "br.com.movingdots.SAMPLE"
    private const val INTERVAL_MS = 30_000L

    fun isAvailable(context: Context) =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ActivitySampleReceiver::class.java).setAction(ACTION_SAMPLE),
        // Mutable: Play services fills in the result extras.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    fun start(context: Context) {
        if (!isAvailable(context) || !hasPermission(context)) return
        val log = DetectionLog(context)
        try {
            ActivityRecognition.getClient(context)
                .requestActivityUpdates(INTERVAL_MS, pendingIntent(context))
                .addOnSuccessListener { log.add("Sampling every ${INTERVAL_MS / 1000}s") }
                .addOnFailureListener { log.add("Sampling FAILED to start: $it") }
        } catch (e: SecurityException) {
            log.add("Sampling failed: activity recognition permission revoked")
        }
    }

    fun stop(context: Context) {
        if (!isAvailable(context)) return
        try {
            ActivityRecognition.getClient(context).removeActivityUpdates(pendingIntent(context))
                .addOnSuccessListener { DetectionLog(context).add("Sampling stopped") }
        } catch (e: SecurityException) {
            DetectionLog(context).add("Sampling stop failed: permission revoked")
        }
    }

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
}
