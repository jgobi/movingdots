package io.github.jgobi.movingdots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

class MotionProcessorTest {

    private val gain = 6f
    private val dtNs = 20_000_000L // 50 Hz

    private companion object {
        /** Longer than the processor's high-pass warm-up. */
        const val WARMUP_S = 10.5
    }

    /** Device->world rotation: pitch the phone up from flat by [tiltDeg], then yaw by [yawDeg]. */
    private fun rotation(tiltDeg: Double, yawDeg: Double = 0.0): FloatArray {
        val t = tiltDeg * PI / 180
        val y = yawDeg * PI / 180
        val ct = cos(t); val st = sin(t); val cy = cos(y); val sy = sin(y)
        // Rz(yaw) * Rx(tilt)
        return floatArrayOf(
            cy.toFloat(), (-sy * ct).toFloat(), (sy * st).toFloat(),
            sy.toFloat(), (cy * ct).toFloat(), (-cy * st).toFloat(),
            0f, st.toFloat(), ct.toFloat(),
        )
    }

    /** Express a world-frame vector in device coordinates (R^T * v). */
    private fun toDevice(r: FloatArray, wx: Float, wy: Float, wz: Float) = floatArrayOf(
        r[0] * wx + r[3] * wy + r[6] * wz,
        r[1] * wx + r[4] * wy + r[7] * wz,
        r[2] * wx + r[5] * wy + r[8] * wz,
    )

    /**
     * Feeds [seconds] of world acceleration; [accel] gives (east, north) at time t. When starting
     * fresh, [WARMUP_S] of zero acceleration comes first: a constant present from the very first
     * sample is indistinguishable from sensor bias, and the high-pass warm-up has to finish before
     * the steady-state response can be measured.
     */
    private fun run(
        p: MotionProcessor, r: FloatArray, seconds: Double, startNs: Long = 0L,
        accel: (Double) -> Pair<Float, Float>,
    ): Long {
        var ts = startNs
        if (startNs == 0L) {
            ts = 1L
            repeat((WARMUP_S * 1e9 / dtNs).toInt()) { p.onSample(r, 0f, 0f, 0f, ts); ts += dtNs }
        }
        val n = (seconds * 1e9 / dtNs).toInt()
        for (i in 0 until n) {
            val (e, nn) = accel(i * dtNs / 1e9)
            val d = toDevice(r, e, nn, 0f)
            p.onSample(r, d[0], d[1], d[2], ts)
            ts += dtNs
        }
        return ts
    }

    private fun processor() = MotionProcessor(gainPxPerMs2 = gain, maxOffsetXPx = 1000f, maxOffsetYPx = 1000f)

    @Test
    fun forwardAccelerationPushesDotsDownAtAnyTilt() {
        for (tilt in listOf(0.0, 45.0, 80.0)) {
            val p = processor()
            // Passenger faces north; car accelerates north at 2 m/s^2 for 2 s.
            run(p, rotation(tilt), 2.0) { 0f to 2f }
            val expected = gain * (2f - 0.1f) // minus dead zone
            assertTrue("tilt $tilt dy=${p.dy}", p.dy > 0.8f * expected && p.dy < 1.1f * expected)
            assertEquals("tilt $tilt dx", 0f, p.dx, 0.5f)
        }
    }

    @Test
    fun gravityInRawAccelerometerIsIgnored() {
        val p = processor()
        val r = rotation(60.0, yawDeg = 30.0)
        var ts = 1L
        repeat(500) {
            val d = toDevice(r, 0f, 0f, 9.81f)
            p.onSample(r, d[0], d[1], d[2], ts)
            ts += dtNs
        }
        assertTrue(p.isAtRest)
    }

    @Test
    fun brakingPushesDotsUp() {
        val p = processor()
        run(p, rotation(30.0), 2.0) { 0f to -3f }
        assertTrue("dy=${p.dy}", p.dy < -0.8f * gain * 2.9f)
    }

    @Test
    fun leftTurnPushesDotsRight() {
        val p = processor()
        // Facing north, a left turn has centripetal acceleration pointing west (-east).
        run(p, rotation(40.0), 2.0) { -2f to 0f }
        assertTrue("dx=${p.dx}", p.dx > 0.8f * gain * 1.9f)
        assertEquals(0f, p.dy, 0.5f)
    }

    @Test
    fun headingDoesNotMatter() {
        val p = processor()
        // Passenger faces east (yaw -90: phone top points east); car accelerates east.
        run(p, rotation(45.0, yawDeg = -90.0), 2.0) { 2f to 0f }
        assertTrue("dy=${p.dy}", p.dy > 0.8f * gain * 1.9f)
        assertEquals(0f, p.dx, 0.5f)
    }

    @Test
    fun landscapeRotationMapsToScreenAxes() {
        val p = processor()
        p.screenRotation = 1 // ROTATION_90: screen up is device -X
        // Phone flat, yawed so device -X (screen up in ROTATION_90) points north.
        run(p, rotation(0.0, yawDeg = -90.0), 2.0) { 0f to 2f }
        assertTrue("dy=${p.dy}", p.dy > 0.8f * gain * 1.9f)
        assertEquals(0f, p.dx, 0.5f)
    }

    private fun steadyAmplitude(freqHz: Double): Float {
        val p = processor()
        val r = rotation(45.0)
        var peak = 0f
        var ts = 1L
        // 2 m/s^2 sinusoid for 40 s; measure the peak response over the last 10 s once settled.
        for (i in 0 until (40e9 / dtNs).toInt()) {
            val t = i * dtNs / 1e9
            val d = toDevice(r, 0f, (2 * sin(2 * PI * freqHz * t)).toFloat(), 0f)
            p.onSample(r, d[0], d[1], d[2], ts)
            ts += dtNs
            if (t >= 30) peak = maxOf(peak, abs(p.dy))
        }
        return peak
    }

    @Test
    fun handShakeIsAttenuatedVersusSicknessBand() {
        val sicknessBand = steadyAmplitude(0.2)
        val handShake = steadyAmplitude(3.0)
        val db = 20 * log10(sicknessBand / handShake)
        assertTrue("attenuation $db dB (0.2Hz=$sicknessBand, 3Hz=$handShake)", db >= 15)
    }

    @Test
    fun constantBiasDecaysToRest() {
        val p = processor()
        // A 0.3 m/s^2 bias that appears after the processor has initialised (e.g. a slight tilt error).
        run(p, rotation(45.0), 40.0) { 0f to 0.3f }
        assertTrue("dy=${p.dy}", p.isAtRest)
    }

    /** Offset left after a tilt error from the sensors' restart shows up 2 s after the reset. */
    private fun leakAfterReset(p: MotionProcessor): Float {
        val r = rotation(45.0)
        var ts = 1L
        for (i in 0 until (10e9 / dtNs).toInt()) {
            val north = if (i * dtNs >= 2e9) 1.5f else 0f
            val d = toDevice(r, 0f, north, 0f)
            p.onSample(r, d[0], d[1], d[2], ts)
            ts += dtNs
        }
        return abs(p.dy)
    }

    @Test
    fun earlyTiltErrorFadesFasterThanWithoutWarmUp() {
        val warm = leakAfterReset(processor())
        val plain = leakAfterReset(MotionProcessor(gain, 1000f, 1000f, highPassStartTauS = 10f))
        assertTrue("warm=$warm plain=$plain", warm < 0.5f * plain)
    }

    @Test
    fun handMovingWhenSensorsStartLeavesNoOffset() {
        val p = processor()
        val r = rotation(45.0)
        var ts = 1L
        // The hand moves the phone at 2 m/s^2 for 0.3 s as the sensors start, then holds it still.
        for (i in 0 until (4e9 / dtNs).toInt()) {
            val north = if (i * dtNs < 300_000_000L) 2f else 0f
            val d = toDevice(r, 0f, north, 0f)
            p.onSample(r, d[0], d[1], d[2], ts)
            ts += dtNs
        }
        assertEquals(0f, p.dy, 1f)
    }

    @Test
    fun rotatingPhoneWithoutAccelerationDoesNotMoveDots() {
        val p = processor()
        var ts = 1L
        for (i in 0 until 500) {
            val r = rotation(20.0 + 50 * sin(i / 30.0), yawDeg = i * 0.7)
            p.onSample(r, 0f, 0f, 0f, ts)
            ts += dtNs
            assertTrue(p.isAtRest)
        }
    }

    @Test
    fun belowDeadZoneStaysAtRest() {
        val p = processor()
        run(p, rotation(45.0), 5.0) { t -> (0.05f * sin(t * 7).toFloat()) to 0.06f * cos(t * 5).toFloat() }
        assertTrue(p.isAtRest)
    }

    @Test
    fun invalidReadingsAreIgnoredAndDoNotFreezeTheDots() {
        val p = processor()
        val r = rotation(45.0)
        var ts = run(p, r, 1.0) { 0f to 2f }
        // A NaN accelerometer reading and a NaN rotation matrix are both rejected.
        p.onSample(r, Float.NaN, 0f, 0f, ts); ts += dtNs
        val badR = r.copyOf().also { it[4] = Float.NaN }
        p.onSample(badR, 0f, 1f, 0f, ts); ts += dtNs
        assertEquals(2, p.invalidSamples)
        assertTrue(p.dx.isFinite() && p.dy.isFinite())
        // The dots keep responding afterwards.
        val before = p.dy
        run(p, r, 2.0, startNs = ts) { 0f to -3f }
        assertTrue("dy=${p.dy} before=$before", p.dy < before - 1f)
    }

    @Test
    fun offsetIsClamped() {
        val p = MotionProcessor(gainPxPerMs2 = gain, maxOffsetXPx = 5f, maxOffsetYPx = 10f)
        run(p, rotation(45.0), 3.0) { 9f to 9f }
        assertEquals(10f, p.dy, 1e-3f)
        assertEquals(-5f, p.dx, 1e-3f)
    }
}
