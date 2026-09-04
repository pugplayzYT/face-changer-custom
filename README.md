# Face Changer Custom

An open-source Android MediaPipe camera-effect studio. It tracks **face, hands, or body**, exposes three landmark detail levels, and lets users build filter apps in a small sandboxed language that can draw on and modify live camera pixels.

## What is included

- Front/back camera switching.
- MediaPipe Face Landmarker, Hand Landmarker and Pose Landmarker.
- A native CameraX preview that stays smooth while MediaPipe analysis runs separately.
- Low / Medium / High detail for every tracking mode, with stable original MediaPipe landmark indices across LOD sampling.
- Three built-in skeleton/mesh apps written in the same scripting language as custom filters. Their source is viewable in-app and can be saved as an editable copy.
- Home screen with saved custom apps, delete controls, code editor, mode/detail controls and live app inputs.
- Sandboxed pixel/drawing operations: magnify, pixelate, tint, dots, proper landmark connections, circles, lines, rectangles and text.
- `let` variables, `if` / `else`, bounded `repeat` loops, numeric/text inputs and comparisons.
- Scientific expressions and animation values/functions including `time`, `frame`, trig, powers, roots, logs, interpolation and direct landmark coordinate functions.
- Formatted in-app scripting reference with **Copy Docs**. `app/src/main/assets/SCRIPTING.md` is the canonical source bundled into the app.
- Dark graphite + mint/blue UI instead of the default purple Material look.

## Build

Requirements: JDK 17, Android SDK 35 and Gradle 8.9. The build automatically downloads the official MediaPipe `.task` model files into `app/src/main/assets` if they are missing. Those downloaded model files are ignored by Git.

Run `gradle assembleDebug` or open the project in Android Studio.

## Automatic releases

Every push to `main` runs `.github/workflows/release.yml`. It builds an installable debug-signed APK, uploads it as a workflow artifact, and creates a GitHub Release tagged `build-<run number>` containing `face-changer-custom-release.apk`. The project does not store a custom keystore, signing password, or signing secret. Android still requires APKs to carry a signature, so CI relies only on the build environment's automatic debug signing.

Because there is no persistent signing identity, Android may require uninstalling an older CI build before installing a newer one.

## Scripting

See [`app/src/main/assets/SCRIPTING.md`](app/src/main/assets/SCRIPTING.md). The same file is rendered by the app's Docs screen and copied by its Copy Docs button, preventing the repository documentation and in-app reference from drifting apart.

## Camera and memory architecture

The visible camera uses CameraX `PreviewView`, independent from MediaPipe inference. LOW/MEDIUM/HIGH use progressively larger analysis frames while `KEEP_ONLY_LATEST` drops stale analysis frames. Every temporary MediaPipe `MPImage` is explicitly closed after inference and bitmap ownership is bounded so camera processing cannot accumulate an unbounded frame queue.
