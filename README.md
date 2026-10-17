# Moving Dots

Vehicle Motion Cues for Android: dots at the screen edges move with the vehicle's acceleration to
reduce the visual/vestibular conflict behind car sickness. Accelerating pushes the dots down, braking
pushes them up, turns push them sideways (the direction of the inertial force you feel).

## Build flavors

| Flavor | Application id | Automatic mode | Dependencies | Distribution |
|---|---|---|---|---|
| `full` | `br.com.movingdots` | Yes (Google Play services) | `play-services-location` | APK release for sideloading |
| `foss` | `br.com.movingdots.foss` | No (Off / On only) | none besides the Kotlin stdlib | F-Droid |

The `foss` build has a teal icon (the `full` build's is blue), so both can be installed side by side.

Common code only talks to `AutoDetection`, which exists once per flavor with the same API:
`src/full/.../AutoDetection.kt` delegates to the Play services code in `src/full/.../auto/`, and
`src/foss/.../AutoDetection.kt` reports the feature as unsupported.

## How it works

- `MotionProcessor` rotates accelerometer readings into the world frame (game rotation vector, no
  magnetometer), keeps the horizontal part, projects it onto the passenger's forward/right axes,
  high-passes it (τ = 10 s, removes bias), applies a 0.1 m/s² dead zone and drives a ~1 Hz damped
  spring. That keeps the 0.1–0.5 Hz band where car sickness peaks and rejects hand jitter.
- `DotsOverlayView` draws the dots once; motion is applied with `translationX/Y` only.
- `MotionCueService` is a foreground service; sensors run only while the screen is on.
- Automatic mode (`full` only) samples Play services' activity classifier (every 2 min while
  waiting, every 30 s in a session). It starts the cues on one `IN_VEHICLE` sample ≥75% or two
  ≥50%, and ends them after 3 min without one (`ON_BICYCLE` ≥50% also keeps a session going, since
  slow, bumpy traffic gets classified that way). An Activity Transition API ENTER starts a session
  early when it arrives; its EXIT is only logged.

## Build

```sh
./gradlew test                    # unit tests, both flavors
./gradlew assembleFullRelease     # app/build/outputs/apk/full/release/app-full-release.apk
./gradlew assembleFossRelease     # app/build/outputs/apk/foss/release/app-foss-release.apk
adb install -r app/build/outputs/apk/full/release/app-full-release.apk
```

Release builds are currently signed with the debug key.

Check that the `foss` build pulls in nothing from Google (only `kotlin-stdlib` should be listed):

```sh
./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath
```

## Checking on a device

```sh
adb shell dumpsys gfxinfo br.com.movingdots       # frames rendered; should not grow while still
adb shell dumpsys meminfo br.com.movingdots       # PSS
adb shell dumpsys sensorservice | grep -i movingdots   # no active connections with screen off
adb logcat -s MotionCue MotionCueAuto              # fwd/lat acceleration (debug build), detection
```

For the `foss` build, use the package `br.com.movingdots.foss`.

Simulate a vehicle ENTER (`full` debug build only):

```sh
adb shell am broadcast -n br.com.movingdots/.auto.DebugTransitionReceiver
```

On Android 15+ a broadcast from adb is not exempt from background foreground-service limits, so it
may only start the cues while the app is in the foreground; real transition events are exempt.

## License

Copyright (C) 2026 João Gabriel

This program is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version (`GPL-3.0-or-later`).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.
