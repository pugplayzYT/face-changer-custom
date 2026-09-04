# Face Changer Custom

An open-source Android MediaPipe camera-effect studio. It tracks **face, hands, or body**, exposes three landmark detail levels, and lets users build filter apps in a small sandboxed language that can draw on and modify live camera pixels.

## What is included

- Front/back camera switching.
- MediaPipe Face Landmarker, Hand Landmarker and Pose Landmarker.
- Low / Medium / High detail for every tracking mode.
- Three built-in skeleton apps written in the same scripting language as custom filters.
- Home screen with saved custom apps, code editor, mode/detail controls and live app inputs.
- Sandboxed commands for landmark drawing, magnification, pixelation and tinting.
- `if` / `else`, bounded `repeat` loops, numeric/text inputs and comparisons.
- In-app formatted scripting reference with **Copy Docs**. `app/src/main/assets/SCRIPTING.md` is the canonical source bundled into the app.
- Dark graphite + mint/blue UI instead of the default purple Material look.
- Open-source demo sign-in key hardcoded as `I am super cool 27` (intentionally **not** a secret).

## Build

Requirements: JDK 17, Android SDK 35 and Gradle 8.9. The build automatically downloads the official MediaPipe `.task` model files into `app/src/main/assets` if they are missing.

Run `gradle assembleDebug` or open the project in Android Studio.

Release builds deliberately use Android's standard debug signing config because this is an open-source demo and the GitHub release APK needs to be directly installable. Do not treat that signing identity as production security.

## Automatic releases

Every push to `main` runs `.github/workflows/release.yml`. It builds `assembleRelease`, uploads the APK as a workflow artifact, and creates a GitHub Release tagged `build-<run number>` containing `face-changer-custom-release.apk`.

## Scripting

See [`app/src/main/assets/SCRIPTING.md`](app/src/main/assets/SCRIPTING.md). The same file is shown by the app's Docs screen, preventing the repo documentation and in-app reference from drifting apart.

## Architecture note

The current renderer processes a 1280×720 analysis stream rather than pretending a phone can run full MediaPipe + arbitrary CPU pixel effects at 1080p60. CameraX uses `KEEP_ONLY_LATEST`, so expensive scripts drop frames rather than accumulating latency. This is a good base for a future GPU shader backend while keeping custom scripts host-controlled and sandboxed.
