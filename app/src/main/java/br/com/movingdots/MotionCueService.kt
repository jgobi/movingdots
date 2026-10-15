package br.com.movingdots

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
 * Foreground service that shows the motion-cue overlay and feeds it from the sensors.
 *
 * Battery: sensors run at 50 Hz only while the screen is on and cues aren't paused; the view is
 * touched only when the offset changes by >= 0.25 px, so a still vehicle costs ~0 frames.
 */
class MotionCueService : Service(), SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener,
    DisplayManager.DisplayListener {

    private lateinit var prefs: Prefs
    private lateinit var sensorManager: SensorManager
    private lateinit var displayManager: DisplayManager
    private var windowManager: WindowManager? = null
    private var overlay: DotsOverlayView? = null
    private lateinit var processor: MotionProcessor

    private var accelSensor: Sensor? = null
    private var rotationSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private var hasRotation = false
    private var sensorsOn = false
    private var screenOn = true
    private var paused = false
    private var autoStarted = false
    private var snoozed = false

    private val handler = Handler(Looper.getMainLooper())
    private val stopAfterExit = Runnable { stopSelf() }
    private var watchingVehicle = false

    /**
     * Ends an automatic session once no confident vehicle sample has arrived for a while, whether
     * samples said otherwise or stopped arriving (Play services may pause them while the phone is
     * still). Also starts a fresh transition registration, so Play services' stuck "in vehicle"
     * state can't swallow the next ride's ENTER.
     */
    private val vehicleCheck = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastVehicleSeenMs >= NO_VEHICLE_MS) {
                prefs.sampledExitMillis = System.currentTimeMillis()
                DetectionLog(this@MotionCueService).add(
                    "No confident ${AutoDetection.targetName(this@MotionCueService)} " +
                        "sample for ${NO_VEHICLE_MS / 60_000} min -> ending session, fresh registration",
                )
                AutoDetection.onVehicleLeft(this@MotionCueService)
                stopSelf()
                return
            }
            handler.postDelayed(this, VEHICLE_CHECK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            updateSensors()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sensorManager = getSystemService(SensorManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
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
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && (prefs.mode != CueModes.ON || !Settings.canDrawOverlays(this))) {
            // Sticky restart, but the user's choice or permissions changed meanwhile.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val action = intent?.action ?: ACTION_START
        if (action != ACTION_START && !isRunning) {
            // Stale notification action or exit event with nothing to act on. stopSelf(startId)
            // keeps the service if a newer start is already queued.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (action) {
            // Delivered in order after any pending start, so the service always reaches
            // startForeground() before stopping (stopService() could crash the app here).
            ACTION_STOP -> stopSelf(startId)
            // "Turn off" in the notification of a manual session switches the mode off, so the app
            // never claims to be On while no dots are shown.
            ACTION_TURN_OFF -> CueModes.set(this, CueModes.OFF)
            ACTION_PAUSE -> { paused = !paused; applyVisibility() }
            ACTION_SNOOZE -> { snoozed = true; applyVisibility() }
            ACTION_UNSNOOZE -> { snoozed = false; applyVisibility() }
            // Only automatic sessions end with the trip; a grace period absorbs short walks.
            ACTION_VEHICLE_EXIT -> if (autoStarted) {
                DetectionLog(this).add("Exit: ending session in ${EXIT_GRACE_MS / 1000}s unless re-entered")
                handler.postDelayed(stopAfterExit, EXIT_GRACE_MS)
            }
            else -> {
                // A null intent is a sticky restart of a manual session.
                val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) ?: false
                if (handler.hasCallbacks(stopAfterExit)) DetectionLog(this).add("Exit grace canceled: re-entered")
                handler.removeCallbacks(stopAfterExit)
                try {
                    startInForeground(auto)
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    if (intent == null) {
                        // A sticky restart of On mode refused in the background (Android 15+): don't
                        // keep claiming On without dots.
                        DetectionLog(this).add("Restart of On mode refused in background -> mode Off")
                        CueModes.set(this, CueModes.OFF)
                    } else {
                        DetectionLog(this).add("Foreground start refused: $e")
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!isRunning) {
                    autoStarted = auto
                    start()
                } else if (!auto && autoStarted) {
                    // Switched from Automatic to On while showing: the session no longer ends with
                    // the trip, and a trip snooze no longer applies. (A repeated ENTER during the
                    // exit grace period keeps the auto session and its snooze.)
                    autoStarted = false
                    isAutoSession = false
                    snoozed = false
                    setVehicleWatch(false)
                    applyVisibility()
                }
            }
        }
        // Manual sessions come back if the system kills the process; automatic ones wait for the
        // next vehicle transition instead.
        return if (isRunning && !autoStarted) START_STICKY else START_NOT_STICKY
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this) || accelSensor == null || rotationSensor == null) {
            Log.w(TAG, "Cannot start: overlay permission or sensors missing")
            stopSelf()
            return
        }
        isRunning = true
        isAutoSession = autoStarted
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
        OverlayBootstrap.hide()
        windowManager = wm
        overlay = view
        processor.screenRotation = display.rotation

        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        displayManager.registerDisplayListener(this, handler)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        if (autoStarted) {
            DetectionLog(this).add("Session started (automatic)")
            prefs.confidentSampleMillis = 0L
        }
        setVehicleWatch(autoStarted)
        // Auto mode often starts with the phone in a pocket: don't assume the screen is on.
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        applyVisibility()
        TileService.requestListeningState(this, android.content.ComponentName(this, CueTileService::class.java))
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopAfterExit)
        vehicleSampleListener = null
        if (isRunning && autoStarted) {
            DetectionLog(this).add("Session ended")
            // A confident sample from before or during this session must not pair with one after it.
            prefs.confidentSampleMillis = 0L
        }
        if (isRunning) {
            isRunning = false
            isAutoSession = false
            setSensors(false)
            prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
            displayManager.unregisterDisplayListener(this)
            unregisterReceiver(screenReceiver)
            overlay?.let { windowManager?.removeView(it) }
            overlay = null
            snoozed = false
            isSnoozed = false
            isPaused = false
            onStateChanged?.invoke()
            TileService.requestListeningState(this, android.content.ComponentName(this, CueTileService::class.java))
        }
        // After the session flags are cleared, so sampling drops to the waiting rate (or stops).
        setVehicleWatch(false)
        super.onDestroy()
    }

    private fun setVehicleWatch(on: Boolean) {
        if (on != watchingVehicle) {
            watchingVehicle = on
            if (on) {
                // The session started from vehicle evidence: count it as the latest.
                lastVehicleSeenMs = SystemClock.elapsedRealtime()
                // A confident vehicle sample cancels a pending EXIT grace (a spurious EXIT at a long stop).
                vehicleSampleListener = {
                    if (handler.hasCallbacks(stopAfterExit)) {
                        handler.removeCallbacks(stopAfterExit)
                        DetectionLog(this).add("Exit grace canceled: vehicle sample")
                    }
                }
                handler.postDelayed(vehicleCheck, VEHICLE_CHECK_MS)
            } else {
                vehicleSampleListener = null
                handler.removeCallbacks(vehicleCheck)
            }
        }
        // Always, since the session flags may have changed even when watching didn't.
        AutoDetection.onSessionChanged(this)
    }

    private val hidden get() = paused || snoozed

    private fun applyVisibility() {
        overlay?.visibility = if (hidden) android.view.View.INVISIBLE else android.view.View.VISIBLE
        updateSensors()
        isSnoozed = snoozed
        isPaused = paused
        if (isRunning) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        onStateChanged?.invoke()
    }

    private fun updateSensors() = setSensors(isRunning && screenOn && !hidden)

    private fun setSensors(on: Boolean) {
        if (on == sensorsOn) return
        sensorsOn = on
        if (on) {
            processor.reset()
            hasRotation = false
            sensorManager.registerListener(this, rotationSensor, SAMPLING_US, handler)
            sensorManager.registerListener(this, accelSensor, SAMPLING_US, handler)
        } else {
            sensorManager.unregisterListener(this)
            processor.reset()
            overlay?.translationX = 0f
            overlay?.translationY = 0f
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (!hasRotation) return
            val v = event.values
            if (processor.onSample(rotationMatrix, v[0], v[1], v[2], event.timestamp)) {
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
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            hasRotation = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        // Detection state is written often; only appearance settings affect the overlay.
        if (key in Prefs.APPEARANCE_KEYS) applyPrefs()
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
        }
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit

    private fun startInForeground(auto: Boolean) {
        // Build the first notification for the session type being started.
        if (!isRunning) autoStarted = auto
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, MotionCueService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(CueModes.icon(if (autoStarted) CueModes.AUTO else CueModes.ON))
            .setContentTitle(getString(
                when {
                    snoozed -> R.string.notif_snoozed
                    paused -> R.string.notif_paused
                    autoStarted -> R.string.notif_auto
                    else -> R.string.notif_running
                }))
            .setContentIntent(open)
            .setColor(getColor(R.color.primary))
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null,
                getString(if (paused) R.string.action_resume else R.string.action_pause),
                serviceIntent(ACTION_PAUSE, 1)).build())
        if (autoStarted) {
            // Automatic sessions: hiding lasts until the vehicle is left (covers the driver case).
            b.addAction(if (snoozed) {
                Notification.Action.Builder(null, getString(R.string.action_show_again), serviceIntent(ACTION_UNSNOOZE, 3))
            } else {
                Notification.Action.Builder(null, getString(R.string.action_hide_trip), serviceIntent(ACTION_SNOOZE, 3))
            }.build())
        } else {
            b.addAction(Notification.Action.Builder(null, getString(R.string.action_turn_off),
                serviceIntent(ACTION_TURN_OFF, 2)).build())
        }
        return b.build()
    }

    companion object {
        private const val TAG = "MotionCue"
        const val ACTION_START = "br.com.movingdots.START"
        const val ACTION_STOP = "br.com.movingdots.STOP"
        const val ACTION_TURN_OFF = "br.com.movingdots.TURN_OFF"
        const val ACTION_PAUSE = "br.com.movingdots.PAUSE"
        const val ACTION_SNOOZE = "br.com.movingdots.SNOOZE"
        const val ACTION_UNSNOOZE = "br.com.movingdots.UNSNOOZE"
        const val ACTION_VEHICLE_EXIT = "br.com.movingdots.VEHICLE_EXIT"
        const val EXTRA_AUTO = "auto"
        private const val CHANNEL_ID = "cues"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLING_US = 20_000 // 50 Hz
        private const val EXIT_GRACE_MS = 60_000L
        private const val NO_VEHICLE_MS = 3 * 60_000L
        private const val VEHICLE_CHECK_MS = 30_000L

        @Volatile
        private var lastVehicleSeenMs = 0L

        /** True while an automatic session runs (sampling is fast then). */
        @Volatile
        var isAutoSession = false
            private set

        /** Set by a running automatic session; called on the main thread for confident samples. */
        private var vehicleSampleListener: (() -> Unit)? = null

        fun onActivitySample(inVehicle: Boolean) {
            if (!inVehicle) return
            lastVehicleSeenMs = SystemClock.elapsedRealtime()
            vehicleSampleListener?.invoke()
        }
        private const val MAX_TOUCH_PASSTHROUGH_ALPHA = 0.8f

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isSnoozed = false
            private set

        @Volatile
        var isPaused = false
            private set

        /** Main-thread callback for the visible UI; set while MainActivity is started. */
        var onStateChanged: (() -> Unit)? = null

        fun start(context: Context, auto: Boolean = false) {
            context.startForegroundService(
                Intent(context, MotionCueService::class.java).setAction(ACTION_START).putExtra(EXTRA_AUTO, auto),
            )
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
