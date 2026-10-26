package io.github.jgobi.movingdots

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.min

/** User-tunable cue parameters. Every value is adjustable because the evidence base is thin. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Steady-state dot displacement in dp per m/s^2 of vehicle acceleration. */
    var gainDp: Int
        get() = sp.getInt(K_GAIN, 10)
        set(v) = sp.edit().putInt(K_GAIN, v).apply()

    /** Dot radius in dp. */
    var dotSizeDp: Int
        get() = sp.getInt(K_SIZE, 7)
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

    /** [CueModes.OFF] or [CueModes.ON]. */
    var mode: Int
        get() = sp.getInt(K_MODE, CueModes.OFF)
        set(v) = sp.edit().putInt(K_MODE, v).apply()

    /** "Turn off when I leave the vehicle" ("full" build only, see AutoDetection). */
    var autoStop: Boolean
        get() = sp.getBoolean(K_AUTO_STOP, false)
        set(v) = sp.edit().putBoolean(K_AUTO_STOP, v).apply()

    /** The notification permission was requested once (later requests go to system settings). */
    var notificationAsked: Boolean
        get() = sp.getBoolean(K_NOTIFICATION_ASKED, false)
        set(v) = sp.edit().putBoolean(K_NOTIFICATION_ASKED, v).apply()

    /** Timestamp of the last process exit already written to the detection log. */
    var lastExitLogged: Long
        get() = sp.getLong(K_LAST_EXIT, 0L)
        set(v) = sp.edit().putLong(K_LAST_EXIT, v).apply()

    /**
     * Up to 0.5 there was an Automatic mode (2) that started sessions by itself: it becomes Off
     * with "turn off when I leave the vehicle" enabled, and its detection state is dropped.
     */
    fun migrate() {
        if (sp.getInt(K_MODE, CueModes.OFF) != LEGACY_AUTO && !sp.contains("transition_generation")) return
        val e = sp.edit()
        if (sp.getInt(K_MODE, CueModes.OFF) == LEGACY_AUTO) e.putInt(K_MODE, CueModes.OFF).putBoolean(K_AUTO_STOP, true)
        for (k in listOf("transition_generation", "sampled_exit", "boost_until", "sampling_interval",
            "confident_sample", "last_active_mode", "test_walking", "confirm_until")) e.remove(k)
        e.apply()
    }

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
        const val K_MODE = "mode"
        const val K_AUTO_STOP = "auto_stop"
        const val K_LAST_EXIT = "last_exit_logged"
        const val K_NOTIFICATION_ASKED = "notification_asked"
        private const val LEGACY_AUTO = 2

        /** Settings that change how the overlay looks or moves. */
        val APPEARANCE_KEYS = setOf(K_GAIN, K_SIZE, K_OPACITY, K_COUNT, K_COLOR, K_TOP_BOTTOM)

        /** Maximum dot displacement in dp. */
        const val MAX_OFFSET_DP = 48f
    }
}
