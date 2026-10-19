package br.com.movingdots.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.movingdots.DetectionLog
import br.com.movingdots.MotionCueService
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/** Hands Play services activity samples to the running session's TripEndDetector. */
class ActivitySampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val all = result.probableActivities.joinToString(" ") { "${ActivitySampler.name(it.type)}=${it.confidence}" }
        DetectionLog(context).add("Sample: $all")
        if (!MotionCueService.isRunning) {
            // A leftover request (e.g. the process died mid-session): nothing to decide.
            ActivitySampler.stop(context)
            return
        }
        MotionCueService.onActivitySample(
            vehicle = result.getActivityConfidence(DetectedActivity.IN_VEHICLE),
            bicycle = result.getActivityConfidence(DetectedActivity.ON_BICYCLE),
            onFoot = maxOf(
                result.getActivityConfidence(DetectedActivity.ON_FOOT),
                result.getActivityConfidence(DetectedActivity.WALKING),
                result.getActivityConfidence(DetectedActivity.RUNNING),
            ),
        )
    }
}
