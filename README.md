# Moving Dots

Vehicle Motion Cues for Android: dots at the screen edges move with the vehicle's acceleration to
reduce the visual/vestibular conflict behind car sickness. Accelerating pushes the dots down, braking
pushes them up, turns push them sideways (the direction of the inertial force you feel).

## How it works

- `MotionProcessor` rotates accelerometer readings into the world frame (game rotation vector, no
  magnetometer), keeps the horizontal part, projects it onto the passenger's forward/right axes,
  high-passes it (τ = 10 s, removes bias), applies a 0.1 m/s² dead zone and drives a ~1 Hz damped
  spring. That keeps the 0.1–0.5 Hz band where car sickness peaks and rejects hand jitter.
- `DotsOverlayView` draws the dots once; motion is applied with `translationX/Y` only.
- `MotionCueService` is a foreground service; sensors run only while the screen is on.
- Auto mode uses the Play services Activity Recognition Transition API (`IN_VEHICLE` enter/exit).

## Build

```sh
./gradlew test               # MotionProcessor unit tests
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk (debug-signed)
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Checking on a device

```sh
adb shell dumpsys gfxinfo br.com.movingdots       # frames rendered; should not grow while still
adb shell dumpsys meminfo br.com.movingdots       # PSS
adb shell dumpsys sensorservice | grep -i movingdots   # no active connections with screen off
adb logcat -s MotionCue MotionCueAuto              # fwd/lat acceleration (debug build), transitions
```

Simulate vehicle transitions (debug build only):

```sh
adb shell am broadcast -n br.com.movingdots/.auto.DebugTransitionReceiver --ez enter true
adb shell am broadcast -n br.com.movingdots/.auto.DebugTransitionReceiver --ez enter false
```

On Android 15+ a broadcast from adb is not exempt from background foreground-service limits, so
ENTER may only start the cues while the app is in the foreground; real transition events are exempt.
