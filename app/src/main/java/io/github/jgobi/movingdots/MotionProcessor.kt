package io.github.jgobi.movingdots

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns phone sensor readings into a screen-space offset for the motion-cue dots.
 *
 * Pipeline per accelerometer sample:
 *  1. Rotate device-frame acceleration into the world frame and keep the horizontal part. Gravity is
 *     purely vertical, so the raw accelerometer can be used directly — no linear-acceleration
 *     sensor (an extra fused virtual sensor) is needed.
 *  2. Project it onto the passenger's "forward" and "right" directions, derived from the screen
 *     orientation so the result is the same whether the phone is held flat or upright.
 *  3. High-pass (tau ~10 s) to remove sensor bias and gravity leakage from tilt errors. Right after
 *     a reset tau starts short and grows to its full value over [highPassWarmupS]: the rotation
 *     vector restarts with the sensors, and when that happens in a moving vehicle its first tilt
 *     estimate includes the vehicle's acceleration, which then leaks in as a false offset. The
 *     filter also starts from zero rather than from the first reading: that reading usually
 *     includes the hand unlocking the phone, and taken as the baseline it would show up as an
 *     opposite offset once the hand stops.
 *  4. Soft dead zone so the dots rest perfectly still while cruising.
 *  5. Drive a damped spring (~1 Hz, zeta 0.9) with the inertial pseudo-force. This is a 2nd-order
 *     low-pass that keeps the 0.1-0.5 Hz band where car sickness peaks and rejects hand jitter,
 *     and its steady state displacement is proportional to acceleration.
 *
 * Output convention (screen pixels, y down): accelerating forward pushes dots down, braking pushes
 * them up, a left turn pushes them right — the direction of the inertial force the body feels.
 *
 * Pure Kotlin with no Android dependencies so it can be unit-tested on the JVM.
 */
class MotionProcessor(
    /** Steady-state displacement in px per m/s^2 of acceleration. */
    var gainPxPerMs2: Float,
    /** Displacement limits; set so the dots stay on screen. */
    var maxOffsetXPx: Float,
    var maxOffsetYPx: Float,
    springHz: Float = 1.0f,
    private val damping: Float = 0.9f,
    private val highPassTauS: Float = 10f,
    private val highPassStartTauS: Float = 0.5f,
    private val highPassWarmupS: Float = 10f,
    private val deadZoneMs2: Float = 0.1f,
) {
    /** Display rotation, as Surface.ROTATION_0..ROTATION_270 (0..3). */
    var screenRotation: Int = 0

    var dx = 0f; private set
    var dy = 0f; private set

    /** Latest horizontal acceleration after filtering, in m/s^2 (for debugging/tuning). */
    var accelForward = 0f; private set
    var accelLateral = 0f; private set

    /** Latest horizontal acceleration before filtering, in m/s^2 (for debugging/tuning). */
    var rawForward = 0f; private set
    var rawLateral = 0f; private set

    /** Seconds of samples since the last reset, for the high-pass warm-up. */
    private var sinceResetS = 0f

    private val omega = (2.0 * Math.PI * springHz).toFloat()
    private var vx = 0f
    private var vy = 0f
    private var lastTimestampNs = 0L

    // High-pass filter state (forward, lateral).
    private var hpInF = 0f
    private var hpInL = 0f
    private var hpOutF = 0f
    private var hpOutL = 0f

    private var lastDrawnX = 0f
    private var lastDrawnY = 0f

    fun reset() {
        dx = 0f; dy = 0f; vx = 0f; vy = 0f
        hpInF = 0f; hpInL = 0f; hpOutF = 0f; hpOutL = 0f
        accelForward = 0f; accelLateral = 0f
        rawForward = 0f; rawLateral = 0f
        sinceResetS = 0f
        lastTimestampNs = 0L
        lastDrawnX = 0f; lastDrawnY = 0f
    }

    /**
     * @param r row-major 3x3 rotation matrix mapping device to world coordinates (world Z up), as
     *   produced by SensorManager.getRotationMatrixFromVector.
     * @return true when the offset moved enough since the last true return to be worth redrawing.
     */
    fun onSample(r: FloatArray, ax: Float, ay: Float, az: Float, timestampNs: Long): Boolean {
        // A single NaN/infinite reading would poison the spring state for good: once dx is NaN, no
        // later offset ever differs "enough" from it, so the dots would freeze until a reset.
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite() || r.any { !it.isFinite() }) {
            invalidSamples++
            return false
        }
        // World-frame horizontal acceleration.
        val awx = r[0] * ax + r[1] * ay + r[2] * az
        val awy = r[3] * ax + r[4] * ay + r[5] * az

        // Screen "up" and "right" axes in device coordinates for the current display rotation.
        val upX: Float; val upY: Float
        when (screenRotation) {
            1 -> { upX = -1f; upY = 0f }
            2 -> { upX = 0f; upY = -1f }
            3 -> { upX = 1f; upY = 0f }
            else -> { upX = 0f; upY = 1f }
        }
        // Forward = horizontal(screen up) + horizontal(back of phone, -Z). Their sum points ahead of
        // the passenger at every reading tilt, from lying flat to fully upright.
        var fx = r[0] * upX + r[1] * upY - r[2]
        var fy = r[3] * upX + r[4] * upY - r[5]
        val fn = sqrt(fx * fx + fy * fy)
        if (fn > 1e-3f) {
            fx /= fn; fy /= fn
        } else {
            return false
        }
        // Right is forward rotated 90 degrees clockwise about world up.
        val rx = fy
        val ry = -fx

        val inF = awx * fx + awy * fy
        val inL = awx * rx + awy * ry
        rawForward = inF
        rawLateral = inL

        if (lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            return false
        }
        val dt = ((timestampNs - lastTimestampNs) * 1e-9f).coerceIn(0f, 0.05f)
        lastTimestampNs = timestampNs
        if (dt <= 0f) return false

        // First-order high-pass.
        sinceResetS += dt
        val tau = if (sinceResetS >= highPassWarmupS) highPassTauS
        else highPassStartTauS + (highPassTauS - highPassStartTauS) * sinceResetS / highPassWarmupS
        val a = exp(-dt / tau)
        hpOutF = a * (hpOutF + inF - hpInF)
        hpOutL = a * (hpOutL + inL - hpInL)
        hpInF = inF; hpInL = inL

        // Soft radial dead zone (keeps direction, removes the first deadZone m/s^2).
        var uF = hpOutF
        var uL = hpOutL
        val mag = sqrt(uF * uF + uL * uL)
        if (mag <= deadZoneMs2) {
            uF = 0f; uL = 0f
        } else {
            val s = (mag - deadZoneMs2) / mag
            uF *= s; uL *= s
        }
        accelForward = uF
        accelLateral = uL

        // Spring targets in screen px: lateral force opposes lateral acceleration; forward
        // acceleration pushes down (+y).
        val w2 = omega * omega
        val c = 2f * damping * omega
        val targetX = -gainPxPerMs2 * uL
        val targetY = gainPxPerMs2 * uF
        // Semi-implicit Euler.
        vx += (w2 * (targetX - dx) - c * vx) * dt
        vy += (w2 * (targetY - dy) - c * vy) * dt
        dx += vx * dt
        dy += vy * dt
        if (!dx.isFinite() || !dy.isFinite() || !vx.isFinite() || !vy.isFinite()) {
            // Defensive: never keep an invalid state. Rest at the centre and start over.
            invalidSamples++
            reset()
            return true
        }
        if (abs(dx) > maxOffsetXPx) { dx = maxOffsetXPx * (if (dx > 0) 1 else -1); vx = 0f }
        if (abs(dy) > maxOffsetYPx) { dy = maxOffsetYPx * (if (dy > 0) 1 else -1); vy = 0f }

        // Snap to rest so the view stops being touched once settled.
        if (uF == 0f && uL == 0f && abs(dx) < 0.2f && abs(dy) < 0.2f && abs(vx) < 1f && abs(vy) < 1f) {
            dx = 0f; dy = 0f; vx = 0f; vy = 0f
        }

        if (abs(dx - lastDrawnX) >= REDRAW_PX || abs(dy - lastDrawnY) >= REDRAW_PX ||
            (dx == 0f && dy == 0f && (lastDrawnX != 0f || lastDrawnY != 0f))
        ) {
            lastDrawnX = dx; lastDrawnY = dy
            return true
        }
        return false
    }

    /** Readings or states rejected as NaN/infinite since creation (diagnostics). */
    var invalidSamples = 0
        private set

    val isAtRest: Boolean
        get() = dx == 0f && dy == 0f && vx == 0f && vy == 0f

    companion object {
        const val REDRAW_PX = 0.25f
    }
}
