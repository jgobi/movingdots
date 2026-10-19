package br.com.movingdots

import br.com.movingdots.TripEndDetector.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sample sequences taken from real ride logs (confidences as Play services reported them). */
class TripEndDetectorTest {

    private class Sample(val sec: Int, val vehicle: Int = 0, val bicycle: Int = 0, val onFoot: Int = 0)

    /** Feeds the samples and returns each result, keyed by the sample's time in seconds. */
    private fun run(d: TripEndDetector, vararg samples: Sample): List<Pair<Int, Result>> =
        samples.map { it.sec to d.onSample(it.vehicle, it.bicycle, it.onFoot, it.sec * 1000L) }

    private fun firstEnd(results: List<Pair<Int, Result>>) =
        results.firstOrNull { it.second == Result.ENDED_WALKING || it.second == Result.ENDED_TIMEOUT }

    @Test
    fun turningOnWhileWalkingToTheCarDoesNotEndTheSession() {
        val d = TripEndDetector()
        val r = run(d,
            Sample(0, onFoot = 86),             // walking to the car (10-17 18:47 log)
            Sample(30, onFoot = 89),
            Sample(60),                         // UNKNOWN
            Sample(90, vehicle = 98),           // in the car
        )
        assertEquals(null, firstEnd(r))
        assertEquals(Result.ARMED, r.last().second)
    }

    @Test
    fun arrivalEndsOnTheFirstWalkingSample() {
        // 10-18 15:31-15:35: in the car, tilting/unknown noise at the stop, then walking away.
        val d = TripEndDetector()
        val r = run(d,
            Sample(0, vehicle = 96),
            Sample(30, vehicle = 86),
            Sample(60),                          // TILTING
            Sample(90),                          // UNKNOWN=40 IN_VEHICLE=10 ...
            Sample(120, vehicle = 10, bicycle = 10, onFoot = 10),
            Sample(180, vehicle = 1, bicycle = 1, onFoot = 94),
            Sample(240, onFoot = 97),
        )
        assertEquals(180 to Result.ENDED_WALKING, firstEnd(r))
    }

    @Test
    fun bumpyTrafficClassifiedAsBicycleKeepsTheSession() {
        // 10-16 22:39-22:44: ON_BICYCLE 80-92% for minutes before arrival, then walking.
        val d = TripEndDetector(noVehicleTimeoutMs = 3 * 60_000L)
        val r = run(d,
            Sample(0, vehicle = 90),
            Sample(30, bicycle = 80, vehicle = 10, onFoot = 7),
            Sample(60, bicycle = 83, vehicle = 15),
            Sample(150, bicycle = 66, vehicle = 31),
            Sample(180, bicycle = 92, vehicle = 6),
            Sample(260, bicycle = 86, vehicle = 8, onFoot = 3),
            Sample(380, onFoot = 87, vehicle = 2, bicycle = 2),
        )
        assertEquals(380 to Result.ENDED_WALKING, firstEnd(r))
    }

    @Test
    fun longStillTrafficStopDoesNotEndTheSession() {
        val d = TripEndDetector()
        run(d, Sample(0, vehicle = 95))
        // Ten minutes stuck in traffic: STILL samples report nothing we act on.
        for (sec in 30..600 step 30) {
            assertEquals(Result.NONE, d.onSample(vehicle = 1, bicycle = 0, onFoot = 2, nowMs = sec * 1000L))
        }
        assertEquals(Result.NONE, d.onSample(vehicle = 92, bicycle = 0, onFoot = 0, nowMs = 630_000L))
    }

    @Test
    fun gettingOffAndSittingStillEndsAfterTheTimeout() {
        val d = TripEndDetector(noVehicleTimeoutMs = 15 * 60_000L)
        run(d, Sample(0, vehicle = 97))
        assertEquals(Result.NONE, d.check(14 * 60_000L))
        assertEquals(Result.ENDED_TIMEOUT, d.check(15 * 60_000L))
        // Also reported by a STILL sample arriving after the timeout.
        assertEquals(Result.ENDED_TIMEOUT, d.onSample(vehicle = 0, bicycle = 0, onFoot = 0, nowMs = 16 * 60_000L))
    }

    @Test
    fun neverArmedNeverEnds() {
        val d = TripEndDetector()
        // Chair spinning reached IN_VEHICLE 42%; walking around never ends an unarmed session.
        val r = run(d, Sample(0, vehicle = 42), Sample(30, onFoot = 95), Sample(3600, vehicle = 29))
        assertEquals(null, firstEnd(r))
        assertFalse(d.armed)
        assertEquals(Result.NONE, d.check(10 * 3600_000L))
    }

    @Test
    fun bicycleBeforeTheVehicleDoesNotArm() {
        val d = TripEndDetector()
        assertEquals(Result.NONE, d.onSample(vehicle = 0, bicycle = 90, onFoot = 0, nowMs = 0))
        assertFalse(d.armed)
        assertEquals(Result.ARMED, d.onSample(vehicle = 60, bicycle = 0, onFoot = 0, nowMs = 1000))
        assertTrue(d.armed)
    }
}
