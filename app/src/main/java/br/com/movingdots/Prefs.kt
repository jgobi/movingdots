package br.com.movingdots

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.min

/** User-tunable cue parameters. Every value is adjustable because the evidence base is thin. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Steady-state dot displacement in dp per m/s^2 of vehicle acceleration. */
    var gainDp: Int
        get() = sp.getInt(K_GAIN, 6)
        set(v) = sp.edit().putInt(K_GAIN, v).apply()

    /** Dot radius in dp. */
    var dotSizeDp: Int
        get() = sp.getInt(K_SIZE, 4)
        set(v) = sp.edit().putInt(K_SIZE, v).apply()

    /** Dot opacity, 0-100. The overlay window itself is capped at 0.8 so touches pass through. */
    var opacity: Int
        get() = sp.getInt(K_OPACITY, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v).apply()

    /** Dots along each long screen edge; short edges get a proportional count. */
    var dotsPerSide: Int
        get() = sp.getInt(K_COUNT, 8)
        set(v) = sp.edit().putInt(K_COUNT, v).apply()

    /** Dots along the left and right edges only (default), or also along the top and bottom. */
    var topBottomDots: Boolean
        get() = sp.getBoolean(K_TOP_BOTTOM, false)
        set(v) = sp.edit().putBoolean(K_TOP_BOTTOM, v).apply()

    /** 0 = dark with light outline (visible on anything), 1 = white, 2 = black, 3 = blue. */
    var colorPreset: Int
        get() = sp.getInt(K_COLOR, 0)
        set(v) = sp.edit().putInt(K_COLOR, v).apply()

    /** [CueModes.OFF], [CueModes.ON] or [CueModes.AUTO]. */
    var mode: Int
        get() = sp.getInt(K_MODE, CueModes.OFF)
        set(v) {
            val e = sp.edit().putInt(K_MODE, v)
            if (v != CueModes.OFF) e.putInt(K_LAST_ACTIVE, v)
            e.apply()
        }

    /** Debug builds only: use WALKING instead of IN_VEHICLE as the trigger, to test detection without a ride. */
    var testWalking: Boolean
        get() = BuildConfig.DEBUG && sp.getBoolean(K_TEST_WALKING, false)
        set(v) = sp.edit().putBoolean(K_TEST_WALKING, v).apply()

    /** Identifies the current activity-transition registration (see TransitionRegistrar). */
    var transitionGeneration: Int
        get() = sp.getInt(K_GENERATION, 0)
        set(v) = sp.edit().putInt(K_GENERATION, v).apply()

    /** Wall time when sampling last concluded the user had left the vehicle. */
    var sampledExitMillis: Long
        get() = sp.getLong(K_SAMPLED_EXIT, 0L)
        set(v) = sp.edit().putLong(K_SAMPLED_EXIT, v).apply()

    /** Timestamp of the last process exit already written to the detection log. */
    var lastExitLogged: Long
        get() = sp.getLong(K_LAST_EXIT, 0L)
        set(v) = sp.edit().putLong(K_LAST_EXIT, v).apply()

    /** Fast sampling continues until this wall time (see TransitionRegistrar.boostSampling). */
    var boostUntilMillis: Long
        get() = sp.getLong(K_BOOST_UNTIL, 0L)
        set(v) = sp.edit().putLong(K_BOOST_UNTIL, v).apply()

    /** Interval of the current sampling request, or 0 if none was requested. */
    var samplingIntervalMs: Long
        get() = sp.getLong(K_SAMPLING_INTERVAL, 0L)
        set(v) = sp.edit().putLong(K_SAMPLING_INTERVAL, v).apply()

    /** Wall time of the last confident (but not strong) vehicle sample, for the two-sample rule. */
    var confidentSampleMillis: Long
        get() = sp.getLong(K_CONFIDENT_SAMPLE, 0L)
        set(v) = sp.edit().putLong(K_CONFIDENT_SAMPLE, v).apply()

    /** The last mode other than Off, restored by the Quick Settings tile. */
    val lastActiveMode: Int
        get() = sp.getInt(K_LAST_ACTIVE, CueModes.ON)

    /**
     * Horizontal displacement limit: the side columns sit [DotsOverlayView.INSET_DP] from the edge
     * and must never leave the screen, or the cue vanishes in exactly the hardest turns.
     */
    fun maxOffsetXDp(): Float = min(MAX_OFFSET_DP, DotsOverlayView.INSET_DP - dotSizeDp - 1f)

    /** Vertical limit: only the optional top/bottom rows can leave the screen vertically. */
    fun maxOffsetYDp(): Float = if (topBottomDots) maxOffsetXDp() else MAX_OFFSET_DP

    companion object {
        const val K_GAIN = "gain"
        const val K_SIZE = "size"
        const val K_OPACITY = "opacity"
        const val K_COUNT = "count"
        const val K_COLOR = "color"
        const val K_TOP_BOTTOM = "top_bottom"
        const val K_GENERATION = "transition_generation"
        const val K_TEST_WALKING = "test_walking"
        const val K_LAST_EXIT = "last_exit_logged"
        const val K_BOOST_UNTIL = "boost_until"
        const val K_SAMPLING_INTERVAL = "sampling_interval"
        const val K_CONFIDENT_SAMPLE = "confident_sample"
        const val K_SAMPLED_EXIT = "sampled_exit"
        const val K_MODE = "mode"
        const val K_LAST_ACTIVE = "last_active_mode"

        /** Settings that change how the overlay looks or moves. */
        val APPEARANCE_KEYS = setOf(K_GAIN, K_SIZE, K_OPACITY, K_COUNT, K_COLOR, K_TOP_BOTTOM)

        /** Maximum dot displacement in dp. */
        const val MAX_OFFSET_DP = 48f
    }
}
