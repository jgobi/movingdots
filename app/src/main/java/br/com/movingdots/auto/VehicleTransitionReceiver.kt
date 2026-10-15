package br.com.movingdots.auto

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import br.com.movingdots.CueModes
import br.com.movingdots.MotionCueService
import br.com.movingdots.Prefs
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Receives Play services' raw activity samples (the main detector in Automatic mode) and
 * IN_VEHICLE transitions (a shortcut when they arrive in time).
 */
class VehicleTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Keep the receiver alive briefly: a refused background start retries after an overlay
        // window has been shown (see OverlayBootstrap).
        val pending = goAsync()
        try {
            when {
                ActivityTransitionResult.hasResult(intent) -> onTransitions(context, intent)
                ActivityRecognitionResult.hasResult(intent) -> onSample(context, intent)
            }
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, RECEIVER_HOLD_MS)
        }
    }

    private fun onTransitions(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val log = DetectionLog(context)
        val all = ActivityTransitionResult.extractResult(intent)?.transitionEvents ?: return
        if (prefs.mode != CueModes.AUTO) {
            log.add("Ignored ${all.size} event(s): mode is not Automatic")
            return
        }
        if (!TransitionRegistrar.isCurrent(context, intent)) {
            log.add("Ignored ${all.size} event(s) from replaced registration " +
                "(gen ${intent.getIntExtra("generation", -1)}, current ${prefs.transitionGeneration})")
            return
        }
        val target = TransitionRegistrar.target(context)
        val nowMillis = System.currentTimeMillis()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        var decision: Decision? = null
        // Events are chronological; the last one decides.
        for (e in all.filter { it.activityType == target }) {
            val enter = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            val eventMillis = nowMillis - (nowNanos - e.elapsedRealTimeNanos) / 1_000_000
            val what = "${TransitionRegistrar.name(target)} ${if (enter) "ENTER" else "EXIT"} " +
                "at ${DetectionLog.time(eventMillis)} (age ${(nowMillis - eventMillis) / 1000}s)"
            decision = if (enter) {
                // Registering delivers the *current* state with the time the activity began, so an
                // old ENTER can be true (seen on a real ride: "in vehicle since 22 min ago") or
                // stale (Play services stuck "in vehicle" at home). Those only speed up sampling,
                // which then decides; a fresh ENTER starts the cues right away.
                val tooOld = nowMillis - eventMillis > MAX_ENTER_AGE_MS
                val beforeSampledExit = eventMillis <= prefs.sampledExitMillis + SAMPLED_EXIT_ECHO_MS
                log.add("$what: " + when {
                    tooOld -> "old, checking with fast sampling"
                    beforeSampledExit -> "not after the last sampled exit, checking with fast sampling"
                    else -> "accepted"
                })
                if (tooOld || beforeSampledExit) Decision.CHECK else Decision.ENTER
            } else {
                log.add(what)
                Decision.EXIT
            }
        }
        when (decision) {
            Decision.ENTER -> handle(context, enter = true)
            Decision.CHECK -> if (!MotionCueService.isRunning) TransitionRegistrar.boostSampling(context)
            Decision.EXIT -> handle(context, enter = false)
            null -> Unit
        }
    }

    private enum class Decision { ENTER, CHECK, EXIT }

    private fun onSample(context: Context, intent: Intent) {
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val target = TransitionRegistrar.target(context)
        // Only a confident sample counts as evidence of the target activity. Anything weaker counts
        // against it: lightly spinning on an office chair was seen to score IN_VEHICLE up to 42%
        // next to STILL 44%, which must not keep a session alive.
        var confidence = result.getActivityConfidence(target)
        if (target == DetectedActivity.WALKING) {
            confidence = maxOf(confidence, result.getActivityConfidence(DetectedActivity.ON_FOOT))
        }
        val inTarget = confidence >= TARGET_CONFIDENCE
        val all = result.probableActivities.joinToString(" ") { "${TransitionRegistrar.name(it.type)}=${it.confidence}" }
        DetectionLog(context).add(
            "Sample: $all -> ${if (inTarget) "in" else "no confident"} ${TransitionRegistrar.name(target)}",
        )
        MotionCueService.onActivitySample(inTarget)
        detectFromSamples(context, confidence, inTarget, result.mostProbableActivity, target)
        // Ends an expired sampling boost; a no-op when the rate is already right.
        TransitionRegistrar.syncSampling(context)
    }

    /**
     * Starts a session from samples: one strong sample, or two confident ones within a few
     * minutes. A clear other activity resets the pair; tilting/unknown samples are neutral.
     */
    private fun detectFromSamples(context: Context, confidence: Int, inTarget: Boolean, top: DetectedActivity, target: Int) {
        val prefs = Prefs(context)
        if (prefs.mode != CueModes.AUTO || MotionCueService.isRunning) return
        val now = System.currentTimeMillis()
        val name = TransitionRegistrar.name(target)
        when {
            confidence >= STRONG_CONFIDENCE -> {
                DetectionLog(context).add("Strong $name sample -> starting session")
                handle(context, enter = true)
            }
            inTarget && now - prefs.confidentSampleMillis <= CONFIDENT_PAIR_MS -> {
                DetectionLog(context).add("Second confident $name sample -> starting session")
                handle(context, enter = true)
            }
            inTarget -> prefs.confidentSampleMillis = now
            isOtherActivity(top, target) -> prefs.confidentSampleMillis = 0L
        }
    }

    /** A clear activity other than [target] (ON_FOOT counts as walking). */
    private fun isOtherActivity(top: DetectedActivity, target: Int) = top.type != target &&
        top.type != DetectedActivity.UNKNOWN && top.type != DetectedActivity.TILTING &&
        !(target == DetectedActivity.WALKING && top.type == DetectedActivity.ON_FOOT) &&
        top.confidence >= TARGET_CONFIDENCE

    companion object {
        private const val MAX_ENTER_AGE_MS = 10 * 60_000L
        private const val SAMPLED_EXIT_ECHO_MS = 2 * 60_000L
        private const val TARGET_CONFIDENCE = 50
        private const val STRONG_CONFIDENCE = 75
        private const val CONFIDENT_PAIR_MS = 5 * 60_000L
        private const val RECEIVER_HOLD_MS = 2_000L
        private const val BOOTSTRAP_RETRY_MS = 700L

        fun handle(context: Context, enter: Boolean) {
            if (Prefs(context).mode != CueModes.AUTO) return
            val log = DetectionLog(context)
            if (enter) {
                if (!Settings.canDrawOverlays(context)) {
                    log.add("Cannot start cues: overlay permission missing")
                    return
                }
                val wasRunning = MotionCueService.isRunning
                try {
                    // Activity-transition events are exempt from background FGS start limits.
                    MotionCueService.start(context, auto = true)
                    log.add(if (wasRunning) "Session already running: kept" else "Starting session")
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    // Android 15+: allowed once the app shows an overlay window. Show an invisible one,
                    // give the system a moment to see it, and retry.
                    log.add("Background start refused; retrying with an overlay window")
                    if (!OverlayBootstrap.show(context)) return
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            MotionCueService.start(context, auto = true)
                            log.add("Starting session (via overlay window)")
                        } catch (e2: ForegroundServiceStartNotAllowedException) {
                            log.add("Cannot start cues: background start refused even with overlay ($e2)")
                        }
                        // Safety net in case the service never comes up to remove it.
                        Handler(Looper.getMainLooper()).postDelayed({ OverlayBootstrap.hide() }, 10_000L)
                    }, BOOTSTRAP_RETRY_MS)
                }
            } else if (!MotionCueService.isRunning) {
                log.add("Exit with no session running: nothing to do")
            } else {
                context.startService(
                    Intent(context, MotionCueService::class.java).setAction(MotionCueService.ACTION_VEHICLE_EXIT),
                )
            }
        }
    }
}
