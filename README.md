# Moving Dots

Vehicle motion cues for Android. Reading or watching something on your phone in a moving car or
bus makes many people feel sick: the inner ear feels the vehicle accelerate, brake and turn, while
the eyes see a still screen. Moving Dots draws small dots along the screen edges, on top of any app,
that move with the vehicle (accelerating pushes them down, braking up, turns sideways), so what you
see matches what you feel.

Website: https://jgobi.github.io/movingdots/

Use it as a passenger, never while driving. Evidence for visual motion cues is still limited, so
the app lets you rate rides with and without the dots and see whether they help you.

## Features

- Works over any app; touches pass through to the app underneath.
- Quick Settings tile to turn the dots on and off with one tap.
- Adjustable sensitivity, dot size, opacity, count and color, with a live preview.
- Dots on the side edges only, or also at the top and bottom.
- Optional ride ratings (a 0–10 symptom scale, MISC) to compare rides with and without the dots.
- Optional notification.
- In the GitHub version only: **turn off when I leave the vehicle**, using Google Play services
  activity recognition while the dots are on.

## Download

| Version | Where | Turn off when leaving the vehicle |
|---|---|---|
| Free (`foss`) | GitHub Releases (APK), F-Droid (coming) | No |
| With Google Play services (`full`) | GitHub Releases (APK) | Yes |

Both versions are available as APKs for sideloading from the
[GitHub Releases](https://github.com/jgobi/movingdots/releases) page. The `foss` version will
also be on F-Droid; F-Droid signs apps with its own key, so a `foss` installed from GitHub and one
installed from F-Droid can't update each other (uninstall one to switch).

Both need Android 12 or later. They have different package names and icon colors (teal for
`foss`, blue for `full`), so they can be installed side by side.

## Permissions and privacy

| Permission | Why |
|---|---|
| Display over other apps | To draw the dots on top of other apps |
| Foreground service | To keep the dots running while you use other apps |
| Notifications (optional) | Only for the optional notification |
| Run at startup | To bring the dots back after a reboot or update if they were on |
| Physical activity (`full` only, optional) | For "turn off when I leave the vehicle" |

The app has **no internet permission**: nothing leaves your phone. Motion sensors only run while
the dots are on and the screen is on.

## Known limitations

- The app doesn't appear in recent apps. Some systems (Infinix XOS, and likely other aggressive
  OEM cleaners) kill an app whose card is swiped away, and the dots with it; without a card there's
  nothing to swipe. Open the app from its icon, or long-press the Quick Settings tile.
- The dots can't be hidden automatically for screenshots: Android doesn't tell apps before a
  screenshot is taken. Turn them off from the tile first.
- "Turn off when I leave the vehicle" depends on Google Play services recognizing the vehicle. On
  some bus rides it mostly answered "unknown"; if it never sees a vehicle, the dots stay on until
  you turn them off.

## Development

### Build flavors

| Flavor | Application id | Turn off when leaving the vehicle | Dependencies | Distribution |
|---|---|---|---|---|
| `full` | `io.github.jgobi.movingdots` | Yes (Google Play services) | `play-services-location` | APK release for sideloading |
| `foss` | `io.github.jgobi.movingdots.foss` | No | none besides the Kotlin stdlib | F-Droid |

The `foss` build has a teal icon (the `full` build's is blue), so both can be installed side by side.

Common code only talks to `AutoDetection`, which exists once per flavor with the same API:
`src/full/.../AutoDetection.kt` delegates to the Play services code in `src/full/.../auto/`, and
`src/foss/.../AutoDetection.kt` reports the feature as unsupported.

### How it works

- `MotionProcessor` rotates accelerometer readings into the world frame (game rotation vector, no
  magnetometer), keeps the horizontal part, projects it onto the passenger's forward/right axes,
  high-passes it (τ = 10 s, removes bias), applies a 0.1 m/s² dead zone and drives a ~1 Hz damped
  spring. That keeps the 0.1–0.5 Hz band where car sickness peaks and rejects hand jitter.
- `DotsOverlayView` draws the dots once; motion is applied with `translationX/Y` only.
- `MotionCueService` is a foreground service; sensors run only while the screen is on.
- The dots are always turned on by hand (app or Quick Settings tile). With "turn off when I leave
  the vehicle" (`full` only), Play services' activity classifier is sampled every 30 s while they
  are on (and re-requested when the screen turns on, which brings a fresh sample within ~2 s).
  `TripEndDetector` turns them off once a vehicle was seen and the user then walks, or after
  15 min and 10 samples without vehicle evidence; with no samples it decides nothing. Starting
  automatically was dropped: on the test device Play services withheld its events from the app
  while it was in the background.
- The notification is optional: Android requires one for the foreground service, but without the
  notification permission (or with its channel turned off) the dots run silently.

### Build

```sh
./gradlew test                    # unit tests, both flavors
./gradlew assembleFullRelease     # app/build/outputs/apk/full/release/app-full-release.apk
./gradlew assembleFossRelease     # app/build/outputs/apk/foss/release/app-foss-release.apk
adb install -r app/build/outputs/apk/full/release/app-full-release.apk
```

Release APKs are unsigned unless a key is configured, through environment variables or a
git-ignored `keystore.properties` in the project root, with the same keys:

```properties
MOVINGDOTS_KEYSTORE=/path/to/release.jks
MOVINGDOTS_KEYSTORE_PASSWORD=...
MOVINGDOTS_KEY_ALIAS=...
MOVINGDOTS_KEY_PASSWORD=...
```

CI (`.github/workflows/ci.yml`) runs the tests, lint and unsigned release builds on every push,
and fails if the `foss` build depends on Google or AndroidX libraries. Pushing a `v*` tag runs
`release.yml`, which signs both flavors with the key from the repository secrets
(`MOVINGDOTS_KEYSTORE_BASE64`, `MOVINGDOTS_KEYSTORE_PASSWORD`, `MOVINGDOTS_KEY_ALIAS`,
`MOVINGDOTS_KEY_PASSWORD`) and creates a draft GitHub Release with the APKs.

F-Droid descriptions are in `fastlane/metadata/android/`. The website lives in `site/` and is
published to GitHub Pages by `pages.yml` whenever it changes on `main`.

To check by hand that the `foss` build pulls in nothing from Google (only `kotlin-stdlib` should be
listed):

```sh
./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath
```

### Checking on a device

```sh
adb shell dumpsys gfxinfo io.github.jgobi.movingdots  # frames rendered; should not grow while still
adb shell dumpsys meminfo io.github.jgobi.movingdots  # PSS
adb shell dumpsys sensorservice | grep -i movingdots  # no active connections with screen off
adb logcat -s MotionCue MotionCueAuto                 # fwd/lat acceleration (debug build), detection
```

For the `foss` build, use the package `io.github.jgobi.movingdots.foss`.

## License

Copyright (C) 2026 João Gabriel

This program is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version (`GPL-3.0-or-later`).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

### Additional permission under GNU GPL version 3 section 7

If you modify this Program, or any covered work, by linking or combining it with Google Play
services (or a modified version of that library), containing parts covered by the terms of the
Google Play services license, the licensors of this Program grant you additional permission to
convey the resulting work.

This permission is what allows the `full` build, which uses Google Play services for "turn off
when I leave the vehicle", to be distributed. The `foss` build doesn't use it.
