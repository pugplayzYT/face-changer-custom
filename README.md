# Face Changer Custom

An open-source Android MediaPipe camera-effect studio. It tracks **face, hands, or body**, exposes three landmark detail levels, and lets users build filter apps in a small sandboxed language that can draw on and modify live camera pixels.

## What is included

- Front/back camera switching.
- MediaPipe Face Landmarker, Hand Landmarker and Pose Landmarker.
- Low / Medium / High detail for every tracking mode, with stable original MediaPipe landmark indices across LOD sampling.
- Three built-in skeleton/mesh apps written in the same scripting language as custom filters. Their source is viewable in-app and can be saved as an editable copy.
- Home screen with saved custom apps, code editor, mode/detail controls and live app inputs.
- Sandboxed pixel/drawing operations: magnify, pixelate, tint, dots, proper landmark connections, circles, lines, rectangles and text.
- `let` variables, `if` / `else`, bounded `repeat` loops, numeric/text inputs and comparisons.
- Scientific expressions and animation values/functions including `time`, `frame`, trig, powers, roots, logs, interpolation and direct landmark coordinate functions.
- Formatted in-app scripting reference with **Copy Docs**. `app/src/main/assets/SCRIPTING.md` is the canonical source bundled into the app.
- Dark graphite + mint/blue UI instead of the default purple Material look.

## Build

Requirements: JDK 17, Android SDK 35 and Gradle 8.9. The build automatically downloads the official MediaPipe `.task` model files into `app/src/main/assets` if they are missing. Those downloaded model files are ignored by Git.

Run `gradle assembleDebug` or open the project in Android Studio.

## Automatic releases

Every push to `main` runs `.github/workflows/release.yml`. It builds `assembleRelease`, uploads the APK as a workflow artifact, and creates a GitHub Release tagged `build-<run number>` containing `face-changer-custom-release.apk`. CI also uses the run number as Android `versionCode`, so newer release APKs install as upgrades instead of all pretending to be version code 1.

## Scripting

See [`app/src/main/assets/SCRIPTING.md`](app/src/main/assets/SCRIPTING.md). The same file is rendered by the app's Docs screen and copied by its Copy Docs button, preventing the repository documentation and in-app reference from drifting apart.

## Architecture note

The current renderer processes a 1280×720 analysis stream rather than pretending a phone can run full MediaPipe + arbitrary CPU pixel effects at 1080p60. CameraX uses `KEEP_ONLY_LATEST`, so expensive scripts drop frames rather than accumulating latency. This is a solid base for a future GPU shader backend while keeping custom scripts host-controlled and sandboxed.
