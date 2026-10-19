package br.com.movingdots

/**
 * Decides when a manually started session should turn itself off because the user left the
 * vehicle, from activity-classifier samples (confidences 0-100).
 *
 * Rules, from real rides:
 *  - It only *arms* after a confident IN_VEHICLE sample, so turning the dots on while still walking
 *    to the car or waiting for the bus never ends the session early.
 *  - Once armed, a confident on-foot sample (walking/running) ends it: every logged arrival showed
 *    ON_FOOT 87-97% right after getting off.
 *  - STILL, TILTING and UNKNOWN decide nothing, so a long traffic stop doesn't end it. ON_BICYCLE
 *    counts as the ride going on, since slow, bumpy traffic was classified that way.
 *  - Safety net: armed and no vehicle evidence for [noVehicleTimeoutMs] (got off and sat still).
 *
 * Pure Kotlin, so it can be unit-tested on the JVM. Times are monotonic milliseconds that keep
 * counting while the CPU sleeps (elapsedRealtime).
 */
class TripEndDetector(
    private val confidence: Int = 50,
    private val noVehicleTimeoutMs: Long = 15 * 60_000L,
) {
    enum class Result { NONE, ARMED, ENDED_WALKING, ENDED_TIMEOUT }

    var armed = false
        private set
    private var lastVehicleMs = 0L

    /**
     * @param vehicle IN_VEHICLE confidence.
     * @param bicycle ON_BICYCLE confidence.
     * @param onFoot highest of ON_FOOT, WALKING and RUNNING confidences.
     */
    fun onSample(vehicle: Int, bicycle: Int, onFoot: Int, nowMs: Long): Result {
        if (vehicle >= confidence) {
            lastVehicleMs = nowMs
            if (armed) return Result.NONE
            armed = true
            return Result.ARMED
        }
        if (!armed) return Result.NONE
        if (bicycle >= confidence) {
            lastVehicleMs = nowMs
            return Result.NONE
        }
        if (onFoot >= confidence) return Result.ENDED_WALKING
        return check(nowMs)
    }

    /** The safety-net timeout; call it periodically too, since samples may stop arriving. */
    fun check(nowMs: Long): Result =
        if (armed && nowMs - lastVehicleMs >= noVehicleTimeoutMs) Result.ENDED_TIMEOUT else Result.NONE
}
