package br.com.movingdots

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import br.com.movingdots.auto.DetectionLog
import br.com.movingdots.auto.TransitionRegistrar
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var grantOverlay: Button
    private lateinit var overlayHint: View
    private lateinit var status: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var modeDesc: TextView
    private lateinit var miscDotsOn: CheckBox
    private lateinit var preview: DotsOverlayView
    private var demo: ValueAnimator? = null
    private var updatingUi = false
    private val rideLog by lazy { RideLog(File(filesDir, "misc_log.csv")) }
    private var historyExpanded = false
    private val detectionLog by lazy { DetectionLog(this) }
    private val handler = Handler(Looper.getMainLooper())
    /** Log entries arrive without a UI callback; re-read the log only when it changed. */
    private var shownLogModified = -1L
    private val logRefresher = object : Runnable {
        override fun run() {
            if (findViewById<View>(R.id.detection_card).visibility == View.VISIBLE &&
                detectionLog.lastModified() != shownLogModified
            ) {
                refreshDetectionLog()
            }
            handler.postDelayed(this, LOG_REFRESH_MS)
        }
    }
    /** Last read of the ride log, so dialogs don't re-read the file. */
    private var entries: List<RideLog.Entry> = emptyList()
    private val modeIds = intArrayOf(R.id.mode_off, R.id.mode_on, R.id.mode_auto)
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.K_MODE) refreshMode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        grantOverlay = findViewById(R.id.grant_overlay)
        grantOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        overlayHint = findViewById(R.id.overlay_hint)
        status = findViewById(R.id.status)
        modeDesc = findViewById(R.id.mode_desc)
        modeGroup = findViewById(R.id.mode)
        if (!TransitionRegistrar.isAvailable(this)) findViewById<View>(R.id.mode_auto).visibility = View.GONE
        modeGroup.setOnCheckedChangeListener { _, id ->
            if (!updatingUi) onModeSelected(modeIds.indexOf(id))
        }

        preview = DotsOverlayView(this).apply { applyPrefs(prefs) }
        findViewById<FrameLayout>(R.id.preview).addView(preview)

        val sliders = findViewById<LinearLayout>(R.id.sliders)
        addSlider(sliders, R.string.sensitivity, 2, 16, { prefs.gainDp }, { prefs.gainDp = it })
        addSlider(sliders, R.string.dot_size, 2, 8, { prefs.dotSizeDp }, { prefs.dotSizeDp = it })
        addSlider(sliders, R.string.opacity, 20, 100, { prefs.opacity }, { prefs.opacity = it })
        addSlider(sliders, R.string.dot_count, 4, 12, { prefs.dotsPerSide }, { prefs.dotsPerSide = it })

        val colorIds = intArrayOf(R.id.color_auto, R.id.color_white, R.id.color_black, R.id.color_blue)
        val colors = findViewById<RadioGroup>(R.id.color)
        colors.check(colorIds[prefs.colorPreset.coerceIn(0, 3)])
        colors.setOnCheckedChangeListener { _, id ->
            prefs.colorPreset = colorIds.indexOf(id)
            preview.applyPrefs(prefs)
        }

        findViewById<CheckBox>(R.id.top_bottom).apply {
            isChecked = prefs.topBottomDots
            setOnCheckedChangeListener { _, on ->
                prefs.topBottomDots = on
                preview.applyPrefs(prefs)
            }
        }

        setupMisc(savedInstanceState)
        setupDetection()
    }

    override fun onResume() {
        super.onResume()
        val canOverlay = Settings.canDrawOverlays(this)
        // Keep the mode honest: without the overlay permission nothing can be shown; a manual
        // session that was killed is restarted while we're in the foreground.
        if (!canOverlay && prefs.mode != CueModes.OFF) CueModes.set(this, CueModes.OFF)
        if (canOverlay && prefs.mode == CueModes.ON && !MotionCueService.isRunning) CueModes.set(this, CueModes.ON)
        // Automatic mode can't work (and its option is hidden) without Play services.
        if (prefs.mode == CueModes.AUTO && !TransitionRegistrar.isAvailable(this)) CueModes.set(this, CueModes.OFF)
        refreshMode()
    }

    override fun onStart() {
        super.onStart()
        MotionCueService.onStateChanged = { refreshMode() }
        logRefresher.run()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        // Preview demo: the dots sway as if the car gently accelerates (down) and brakes (up).
        demo = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val amplitude = min(prefs.gainDp * DEMO_ACCEL, prefs.maxOffsetYDp()) * resources.displayMetrics.density
                preview.translationY = amplitude * sin(2 * PI * it.animatedFraction).toFloat()
            }
            start()
        }
    }

    override fun onStop() {
        MotionCueService.onStateChanged = null
        handler.removeCallbacks(logRefresher)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        demo?.cancel()
        demo = null
        super.onStop()
    }

    private fun onModeSelected(mode: Int) {
        if (mode == prefs.mode) return
        val missing = ArrayList<String>()
        if (mode == CueModes.AUTO && !TransitionRegistrar.hasPermission(this)) {
            missing += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (mode != CueModes.OFF && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Manifest.permission.ACTIVITY_RECOGNITION in missing) {
            // Automatic mode is applied once activity recognition is granted.
            requestPermissions(missing.toTypedArray(), REQ_AUTO)
            return
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_NOTIFICATIONS)
        CueModes.set(this, mode)
        refreshMode()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode != REQ_AUTO) return
        val granted = permissions.indices.any {
            permissions[it] == Manifest.permission.ACTIVITY_RECOGNITION && results[it] == PackageManager.PERMISSION_GRANTED
        }
        if (granted) CueModes.set(this, CueModes.AUTO)
        refreshMode()
    }

    /** Syncs the mode selector, description and live state line with the actual app state. */
    private fun refreshMode() {
        val canOverlay = Settings.canDrawOverlays(this)
        val mode = prefs.mode.coerceIn(CueModes.OFF, CueModes.AUTO)
        updatingUi = true
        grantOverlay.visibility = if (canOverlay) View.GONE else View.VISIBLE
        overlayHint.visibility = grantOverlay.visibility
        findViewById<View>(R.id.mode_on).isEnabled = canOverlay
        findViewById<View>(R.id.mode_auto).isEnabled = canOverlay
        modeGroup.check(modeIds[mode])
        updatingUi = false

        modeDesc.setText(
            when (mode) {
                CueModes.ON -> R.string.mode_on_desc
                CueModes.AUTO -> R.string.mode_auto_desc
                else -> R.string.mode_off_desc
            },
        )
        findViewById<View>(R.id.detection_card).visibility =
            if (mode == CueModes.AUTO || BuildConfig.DEBUG) View.VISIBLE else View.GONE
        refreshDetectionLog()
        val running = MotionCueService.isRunning
        status.setText(
            when {
                mode == CueModes.OFF -> R.string.state_hidden
                mode == CueModes.AUTO && !running -> R.string.state_waiting
                !running -> R.string.state_hidden
                MotionCueService.isSnoozed -> R.string.state_snoozed
                MotionCueService.isPaused -> R.string.state_paused
                else -> R.string.state_showing
            },
        )
    }

    /** Adds a labelled SeekBar bound to an Int preference. The running service picks changes up live. */
    private fun addSlider(parent: LinearLayout, label: Int, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit) {
        val text = TextView(this, null, 0, R.style.Body).apply {
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(16), 0, 0)
        }
        val bar = SeekBar(this, null, 0, R.style.Slider).apply {
            this.min = min
            this.max = max
            progress = get()
        }
        text.text = getString(label, bar.progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                text.text = getString(label, value)
                if (fromUser) {
                    set(value)
                    preview.applyPrefs(prefs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        parent.addView(text)
        parent.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
    }

    /** Diagnostics for automatic mode: detection log (copyable) and, in debug builds, the test trigger. */
    private fun setupDetection() {
        if (BuildConfig.DEBUG) {
            findViewById<View>(R.id.test_trigger_section).visibility = View.VISIBLE
            val group = findViewById<RadioGroup>(R.id.test_trigger)
            group.check(if (prefs.testWalking) R.id.test_walking else R.id.test_vehicle)
            group.setOnCheckedChangeListener { _, id ->
                val walking = id == R.id.test_walking
                if (walking == prefs.testWalking) return@setOnCheckedChangeListener
                prefs.testWalking = walking
                detectionLog.add("Test trigger -> ${if (walking) "WALKING" else "IN_VEHICLE"}")
                // Re-apply Automatic so the registration and any running session use the new trigger.
                if (prefs.mode == CueModes.AUTO) CueModes.set(this, CueModes.AUTO)
                refreshMode()
            }
        }
        findViewById<Button>(R.id.detection_copy).setOnClickListener {
            val header = "Moving Dots ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                "Mode=${arrayOf("Off", "On", "Automatic")[prefs.mode.coerceIn(0, 2)]} " +
                "trigger=${TransitionRegistrar.name(TransitionRegistrar.target(this))} " +
                "gen=${prefs.transitionGeneration} running=${MotionCueService.isRunning}\n"
            val text = header + detectionLog.lines().reversed().joinToString("\n")
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.detection_title), text))
            // Android 13+ confirms clipboard writes itself.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.detection_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.detection_clear).setOnClickListener {
            detectionLog.clear()
            refreshDetectionLog()
        }
    }

    private fun refreshDetectionLog() {
        shownLogModified = detectionLog.lastModified()
        val lines = detectionLog.lines()
        findViewById<TextView>(R.id.detection_log).text =
            if (lines.isEmpty()) getString(R.string.detection_empty) else lines.take(DETECTION_LINES).joinToString("\n")
    }

    /**
     * Misery Scale (MISC, 0-10) self-report after a ride, logged locally so the user can compare
     * rides with and without the dots.
     */
    private fun setupMisc(savedInstanceState: Bundle?) {
        val labels = resources.getStringArray(R.array.misc_levels)
        val label = findViewById<TextView>(R.id.misc_label)
        val value = findViewById<TextView>(R.id.misc_value)
        val bar = findViewById<SeekBar>(R.id.misc)
        miscDotsOn = findViewById(R.id.misc_dots_on)
        // Only a default: once the user changes it, the CheckBox keeps their choice (also across
        // recreation), so a rating can't silently flip to "with dots".
        if (savedInstanceState == null) miscDotsOn.isChecked = MotionCueService.isRunning && !MotionCueService.isSnoozed
        val show = { v: Int ->
            value.text = v.toString()
            label.text = labels[v]
        }
        show(0)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) = show(value)
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        findViewById<Button>(R.id.misc_save).setOnClickListener {
            rideLog.add(bar.progress, miscDotsOn.isChecked)
            refreshHistory()
        }
        findViewById<Button>(R.id.history_toggle).setOnClickListener {
            historyExpanded = !historyExpanded
            refreshHistory()
        }
        findViewById<Button>(R.id.history_clear).setOnClickListener { confirmClearHistory() }
        refreshHistory()
    }

    private fun refreshHistory() {
        entries = rideLog.entries()
        findViewById<TextView>(R.id.misc_summary).text = miscSummary(entries)
        findViewById<View>(R.id.history_section).visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        val list = findViewById<LinearLayout>(R.id.history)
        list.removeAllViews()
        val labels = resources.getStringArray(R.array.misc_levels)
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val shown = if (historyExpanded) entries else entries.take(HISTORY_COLLAPSED)
        for (e in shown) list.addView(historyRow(e, labels, dateFormat))

        val toggle = findViewById<Button>(R.id.history_toggle)
        toggle.visibility = if (entries.size > HISTORY_COLLAPSED) View.VISIBLE else View.GONE
        toggle.text = if (historyExpanded) getString(R.string.history_show_less)
        else getString(R.string.history_show_all, entries.size)
    }

    /** One history line: the score, then the symptom level and "date · with/without dots". */
    private fun historyRow(e: RideLog.Entry, labels: Array<String>, dateFormat: DateFormat): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = e.score.toString()
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.primary))
            minWidth = dp(40)
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this, null, 0, R.style.Body).apply {
            text = labels[e.score.coerceIn(0, labels.size - 1)]
            setTextColor(getColor(R.color.text_primary))
        })
        texts.addView(TextView(this, null, 0, R.style.Body).apply {
            text = getString(
                R.string.history_meta, dateFormat.format(Date(e.timeMillis)),
                getString(if (e.dotsOn) R.string.with_dots else R.string.without_dots),
            )
            textSize = 12f
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun confirmClearHistory() {
        val count = entries.size
        AlertDialog.Builder(this)
            .setTitle(R.string.history_clear_title)
            .setMessage(resources.getQuantityString(R.plurals.history_clear_message, count, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.history_clear_confirm) { _, _ ->
                rideLog.clear()
                historyExpanded = false
                refreshHistory()
            }
            .show()
    }

    private fun miscSummary(entries: List<RideLog.Entry>): String {
        if (entries.isEmpty()) return getString(R.string.misc_none)
        val on = entries.filter { it.dotsOn }.map { it.score }
        val off = entries.filterNot { it.dotsOn }.map { it.score }
        fun avg(l: List<Int>) = if (l.isEmpty()) "–" else "%.1f".format(l.average())
        return getString(R.string.misc_summary, avg(on), on.size, avg(off), off.size)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIFICATIONS = 1
        private const val REQ_AUTO = 2
        /** Peak acceleration of the preview demo, in m/s^2 (gentle city driving). */
        private const val DEMO_ACCEL = 2.5f
        private const val HISTORY_COLLAPSED = 5
        private const val DETECTION_LINES = 15
        private const val LOG_REFRESH_MS = 5_000L
    }
}
