package io.github.jgobi.movingdots

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Display
import android.view.WindowManager

/**
 * Foreground service that shows the motion-cue overlay and feeds it from the sensors. Always
 * started by hand; with "turn off when I leave the vehicle" a [TripEndDetector] fed by activity
 * samples may end it.
 *
 * Battery: sensors run at 50 Hz only while the screen is on; the view is touched only when the
 * offset changes by >= 0.25 px, so a still vehicle costs ~0 frames.
 */
class MotionCueService : Service(), SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener,
    DisplayManager.DisplayListener {

    private lateinit var prefs: Prefs
    private lateinit var sensorManager: SensorManager
    private lateinit var displayManager: DisplayManager
    private lateinit var powerManager: PowerManager
    private var windowManager: WindowManager? = null
    private var overlay: DotsOverlayView? = null
    private lateinit var processor: MotionProcessor

    private var accelSensor: Sensor? = null
    private var rotationSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private var hasRotation = false
    private var lastAccelMs = 0L
    private var lastRotationMs = 0L
    // Debug diagnostics for the "frozen dots" report: events since the last heartbeat.
    private var accelEvents = 0
    private var rotationEvents = 0
    private val lastAccel = FloatArray(3)

    /** Debug builds only: the service's view of sensors, screen and overlay every 30 s. */
    private val heartbeat = object : Runnable {
        override fun run() {
            val v = overlay
            DetectionLog(this@MotionCueService).add(
                "Heartbeat: screenOn=$screenOn interactive=${powerManager.isInteractive} " +
                    "sensorsOn=$sensorsOn accel=$accelEvents rot=$rotationEvents hasRot=$hasRotation " +
                    "a=%.2f,%.2f,%.2f fwd=%.2f lat=%.2f dx=%.1f dy=%.1f ".format(
                        lastAccel[0], lastAccel[1], lastAccel[2],
                        processor.accelForward, processor.accelLateral, processor.dx, processor.dy,
                    ) +
                    "view: attached=${v?.isAttachedToWindow} shown=${v?.isShown} vis=${v?.visibility} " +
                    "tx=%.1f ty=%.1f rotation=${processor.screenRotation}".format(v?.translationX ?: 0f, v?.translationY ?: 0f),
            )
            accelEvents = 0
            rotationEvents = 0
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /**
     * While the dots are shown, both sensors report continuously (~50 Hz). Dots were seen frozen
     * on a ride until turned off and on again: if either sensor goes quiet, register them again.
     * (The CPU is awake whenever this runs, since sensors only run with the screen on.)
     */
    private val sensorWatchdog = object : Runnable {
        override fun run() {
            if (!sensorsOn) return
            val now = SystemClock.elapsedRealtime()
            val quietAccel = now - lastAccelMs
            val quietRotation = now - lastRotationMs
            if (quietAccel >= SENSOR_SILENCE_MS || quietRotation >= SENSOR_SILENCE_MS) {
                DetectionLog(this@MotionCueService).add(
                    "Sensor events stopped (accelerometer ${quietAccel / 1000}s, rotation ${quietRotation / 1000}s " +
                        "without events) -> registering the sensors again",
                )
                registerSensors()
            }
            handler.postDelayed(this, SENSOR_WATCHDOG_MS)
        }
    }
    private var sensorsOn = false
    private var screenOn = true

    private val handler = Handler(Looper.getMainLooper())
    /** Non-null while this session watches for the end of the trip. */
    private var detector: TripEndDetector? = null
    private var ending = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            checkScreen(intent.action.orEmpty().substringAfterLast('.'))
        }
    }

    /** Runs while the screen is believed off; see [checkScreen]. */
    private val screenPoll = object : Runnable {
        override fun run() {
            checkScreen("poll")
            if (!screenOn) handler.postDelayed(this, SCREEN_POLL_MS)
        }
    }

    /**
     * Follows the real screen state. ACTION_SCREEN_ON alone isn't enough: after the screen had been
     * off for 17 min, unlocking with the fingerprint (XOS) woke the phone without it, leaving the
     * sensors off and the dots frozen in the centre. So every hint re-reads PowerManager: the screen
     * broadcasts, ACTION_USER_PRESENT, display changes, and a poll while the screen is believed off
     * (the handler doesn't wake the CPU, so the poll only runs while the phone is awake anyway).
     */
    private fun checkScreen(source: String) {
        val on = powerManager.isInteractive
        if (on == screenOn) return
        screenOn = on
        if (BuildConfig.DEBUG) DetectionLog(this).add("Screen ${if (on) "on" else "off"} ($source)")
        handler.removeCallbacks(screenPoll)
        if (!on) handler.postDelayed(screenPoll, SCREEN_POLL_MS)
        // With the screen off Play services may stop sending samples. Re-requesting brings a fresh
        // one within ~2 s, so the trip is judged on current information as soon as the dots can be
        // seen again.
        if (on && detector != null) {
            DetectionLog(this).add("Screen on: asking for a fresh sample")
            AutoDetection.startWatching(this)
        }
        updateSensors()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sensorManager = getSystemService(SensorManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // Game rotation vector avoids the magnetometer, which a car's metal and motors distort.
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val density = resources.displayMetrics.density
        processor = MotionProcessor(
            gainPxPerMs2 = prefs.gainDp * density,
            maxOffsetXPx = prefs.maxOffsetXDp() * density,
            maxOffsetYPx = prefs.maxOffsetYDp() * density,
        )
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && (prefs.mode != CueModes.ON || !Settings.canDrawOverlays(this))) {
            // Sticky restart, but the user's choice or permissions changed meanwhile.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A null intent is a sticky restart of a running session.
        val action = intent?.action ?: ACTION_START
        if (action != ACTION_START && !isRunning) {
            // Stale notification action with nothing to act on. stopSelf(startId) keeps the
            // service if a newer start is already queued.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (action) {
            // Delivered in order after any pending start, so the service always reaches
            // startForeground() before stopping (stopService() could crash the app here).
            ACTION_STOP -> stopSelf(startId)
            // "Turn off" in the notification switches the mode off, so the app never claims to be
            // On while no dots are shown.
            ACTION_TURN_OFF -> CueModes.set(this, CueModes.OFF)
            else -> {
                try {
                    startInForeground()
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    if (intent == null) {
                        // A sticky restart refused in the background (Android 15+): don't keep
                        // claiming On without dots.
                        DetectionLog(this).add("Restart of the dots refused in background -> Off")
                        CueModes.set(this, CueModes.OFF)
                    } else {
                        DetectionLog(this).add("Foreground start refused: $e")
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!isRunning) start()
            }
        }
        return if (isRunning) START_STICKY else START_NOT_STICKY
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this) || accelSensor == null || rotationSensor == null) {
            Log.w(TAG, "Cannot start: overlay permission or sensors missing")
            stopSelf()
            return
        }
        isRunning = true
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val windowContext = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val view = DotsOverlayView(windowContext).apply { applyPrefs(prefs) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Android 12+ blocks touches through untrusted overlays more opaque than 0.8.
            alpha = MAX_TOUCH_PASSTHROUGH_ALPHA
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "MotionCues"
        }
        wm.addView(view, lp)
        windowManager = wm
        overlay = view
        processor.screenRotation = display.rotation

        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        displayManager.registerDisplayListener(this, handler)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        screenOn = powerManager.isInteractive
        if (!screenOn) handler.postDelayed(screenPoll, SCREEN_POLL_MS)
        updateSensors()
        if (BuildConfig.DEBUG) handler.postDelayed(heartbeat, HEARTBEAT_MS)
        updateWatching()
        RecentsVisibility.update(this, dotsOn = true)
        TileService.requestListeningState(this, android.content.ComponentName(this, CueTileService::class.java))
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(screenPoll)
        if (isRunning) {
            isRunning = false
            setSensors(false)
            prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
            displayManager.unregisterDisplayListener(this)
            unregisterReceiver(screenReceiver)
            overlay?.let { windowManager?.removeView(it) }
            overlay = null
            updateWatching()
            RecentsVisibility.update(this, dotsOn = false)
            onStateChanged?.invoke()
            TileService.requestListeningState(this, android.content.ComponentName(this, CueTileService::class.java))
        }
        super.onDestroy()
    }

    /** Starts or stops watching for the end of the trip, following the session and the option. */
    private fun updateWatching() {
        val want = isRunning && !ending && AutoDetection.isEnabled(this)
        if (want && detector == null) {
            detector = TripEndDetector()
            sampleListener = { vehicle, bicycle, onFoot ->
                detector?.let { onTripResult(it.onSample(vehicle, bicycle, onFoot, SystemClock.elapsedRealtime())) }
            }
            DetectionLog(this).add("Watching for the end of the trip")
            AutoDetection.startWatching(this)
        } else if (!want && detector != null) {
            detector = null
            sampleListener = null
            AutoDetection.stopWatching(this)
            DetectionLog(this).add("Stopped watching for the end of the trip")
        }
        isArmed = detector?.armed == true
        refreshState()
    }

    private fun onTripResult(result: TripEndDetector.Result) {
        if (ending) return
        when (result) {
            TripEndDetector.Result.NONE -> Unit
            TripEndDetector.Result.ARMED -> {
                DetectionLog(this).add("Vehicle detected: the dots turn off when you walk away")
                isArmed = true
                refreshState()
            }
            TripEndDetector.Result.ENDED_WALKING -> endTrip("walking after the ride")
            TripEndDetector.Result.ENDED_TIMEOUT -> endTrip("no vehicle for a long time")
        }
    }

    /** The user left the vehicle: turn the dots off, as if from the tile. */
    private fun endTrip(reason: String) {
        ending = true
        DetectionLog(this).add("Trip ended ($reason) -> dots off")
        // Never show stale dots while the service winds down.
        overlay?.visibility = android.view.View.INVISIBLE
        setSensors(false)
        CueModes.set(this, CueModes.OFF)
    }

    private fun refreshState() {
        if (isRunning) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        onStateChanged?.invoke()
    }

    private fun updateSensors() = setSensors(isRunning && screenOn && !ending)

    private fun setSensors(on: Boolean) {
        if (on == sensorsOn) return
        sensorsOn = on
        if (BuildConfig.DEBUG) DetectionLog(this).add("Sensors ${if (on) "on" else "off"}")
        if (on) {
            registerSensors()
            handler.postDelayed(sensorWatchdog, SENSOR_WATCHDOG_MS)
        } else {
            handler.removeCallbacks(sensorWatchdog)
            sensorManager.unregisterListener(this)
            processor.reset()
            overlay?.translationX = 0f
            overlay?.translationY = 0f
        }
    }

    /** (Re)registers both sensors from a clean state; also used by [sensorWatchdog]. */
    private fun registerSensors() {
        sensorManager.unregisterListener(this)
        processor.reset()
        hasRotation = false
        // Count registration as fresh, so the watchdog gives the sensors time to start.
        lastAccelMs = SystemClock.elapsedRealtime()
        lastRotationMs = lastAccelMs
        sensorManager.registerListener(this, rotationSensor, SAMPLING_US, handler)
        sensorManager.registerListener(this, accelSensor, SAMPLING_US, handler)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            lastAccelMs = SystemClock.elapsedRealtime()
            accelEvents++
            val v = event.values
            lastAccel[0] = v[0]; lastAccel[1] = v[1]; lastAccel[2] = v[2]
            if (!hasRotation) return
            val invalidBefore = processor.invalidSamples
            val moved = processor.onSample(rotationMatrix, v[0], v[1], v[2], event.timestamp)
            if (processor.invalidSamples != invalidBefore && processor.invalidSamples <= MAX_LOGGED_INVALID) {
                DetectionLog(this).add("Invalid sensor value ignored (NaN/infinite), #${processor.invalidSamples}")
            }
            if (moved) {
                overlay?.let {
                    it.translationX = processor.dx
                    it.translationY = processor.dy
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "fwd=%.2f lat=%.2f dx=%.1f dy=%.1f".format(
                        processor.accelForward, processor.accelLateral, processor.dx, processor.dy))
                }
            }
        } else {
            lastRotationMs = SystemClock.elapsedRealtime()
            rotationEvents++
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            hasRotation = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            in Prefs.APPEARANCE_KEYS -> applyPrefs()
            Prefs.K_AUTO_STOP -> updateWatching()
        }
    }

    private fun applyPrefs() {
        val density = resources.displayMetrics.density
        processor.gainPxPerMs2 = prefs.gainDp * density
        processor.maxOffsetXPx = prefs.maxOffsetXDp() * density
        processor.maxOffsetYPx = prefs.maxOffsetYDp() * density
        overlay?.applyPrefs(prefs)
    }

    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            processor.screenRotation = displayManager.getDisplay(displayId).rotation
            checkScreen("display")
        }
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit

    private fun startInForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val turnOff = PendingIntent.getService(
            this, 1, Intent(this, MotionCueService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(CueModes.icon(this))
            .setContentTitle(getString(R.string.notif_running))
            .setContentIntent(open)
            .setColor(getColor(R.color.primary))
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_turn_off), turnOff).build())
        if (detector != null) {
            b.setContentText(getString(if (isArmed) R.string.auto_stop_armed else R.string.auto_stop_waiting))
        }
        return b.build()
    }

    companion object {
        private const val TAG = "MotionCue"
        const val ACTION_START = "io.github.jgobi.movingdots.START"
        const val ACTION_STOP = "io.github.jgobi.movingdots.STOP"
        const val ACTION_TURN_OFF = "io.github.jgobi.movingdots.TURN_OFF"
        const val CHANNEL_ID = "cues"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLING_US = 20_000 // 50 Hz
        private const val SENSOR_WATCHDOG_MS = 2_000L
        private const val HEARTBEAT_MS = 30_000L
        private const val SENSOR_SILENCE_MS = 2_000L
        private const val SCREEN_POLL_MS = 2_000L
        private const val MAX_LOGGED_INVALID = 20
        private const val MAX_TOUCH_PASSTHROUGH_ALPHA = 0.8f

        @Volatile
        var isRunning = false
            private set

        /** The running session saw a vehicle and will turn off when the user walks away. */
        @Volatile
        var isArmed = false
            private set

        /** Main-thread callback for the visible UI; set while MainActivity is started. */
        var onStateChanged: (() -> Unit)? = null

        /** Set while a session watches for the end of the trip; called on the main thread. */
        private var sampleListener: ((vehicle: Int, bicycle: Int, onFoot: Int) -> Unit)? = null

        /** An activity sample (confidences 0-100); ignored unless a session is watching. */
        fun onActivitySample(vehicle: Int, bicycle: Int, onFoot: Int) {
            sampleListener?.invoke(vehicle, bicycle, onFoot)
        }

        /**
         * The notification is optional for the user: Android requires one for the foreground
         * service, but without the notification permission, or with this channel turned off, the
         * dots run silently (listed only among the active apps in Quick Settings).
         */
        fun ensureChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** The user can see the notification: app notifications allowed and the channel not turned off. */
        fun notificationVisible(context: Context): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = nm.getNotificationChannel(CHANNEL_ID)
            return nm.areNotificationsEnabled() && (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MotionCueService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            // Stop through an intent rather than stopService(): a start requested just before may
            // still be queued, and stopping before it calls startForeground() crashes the app.
            try {
                context.startService(Intent(context, MotionCueService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                // Background start not allowed: nothing can be pending then, so stop directly.
                context.stopService(Intent(context, MotionCueService::class.java))
            }
        }
    }
}
