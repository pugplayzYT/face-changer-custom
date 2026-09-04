# Face Changer Custom

An open-source Android MediaPipe camera-effect studio. It tracks **face, hands, or body**, exposes three landmark detail levels, and lets users build filter apps in a small sandboxed language that can draw on and modify live camera pixels.

## What is included

- Front/back camera switching.
- MediaPipe Face Landmarker, Hand Landmarker and Pose Landmarker.
- A native CameraX preview that stays smooth while MediaPipe analysis runs separately.
- Low / Medium / High detail for every tracking mode, with stable original MediaPipe landmark indices across LOD sampling.
- Three built-in skeleton/mesh apps written in the same scripting language as custom filters. Their source is viewable in-app and can be saved as an editable copy.
- Home screen with saved custom apps, delete controls, code editor, mode/detail controls and live app inputs.
- **No login or sign-in screen**; the app opens directly into Filter Studio.
- Sandboxed pixel/drawing operations: magnify, pixelate, tint, dots, proper landmark connections, circles, lines, rectangles and text.
- `let` variables, `if` / `else`, bounded `repeat` loops, numeric/text inputs and comparisons.
- Scientific expressions and animation values/functions including `time`, `frame`, trig, powers, roots, logs, interpolation and direct landmark coordinate functions.
- Formatted in-app scripting reference with **Copy Docs**. `app/src/main/assets/SCRIPTING.md` is the canonical source bundled into the app.
- Dark graphite + mint/blue UI instead of the default purple Material look.

## Build

Requirements: JDK 17, Android SDK 35 and Gradle 8.9. The build automatically downloads the official MediaPipe `.task` model files into `app/src/main/assets` if they are missing. Those downloaded model files are ignored by Git.

Run `gradle assembleRelease` or open the project in Android Studio and build the release variant.

## Release signing

This repository intentionally contains a reproducible public release signing identity under `signing/`. `release-key.jks` is the actual keystore file and `signing.properties` contains its alias/passwords. Gradle signs the `release` variant with that keystore.

Because the signing identity is public, **anyone can sign an APK as this app**. That is intentional for this open-source project, but this identity should not be reused for a private or Play Store app.

The current release certificate SHA-256 fingerprint is `90:A5:EB:40:EC:7D:B4:42:26:76:EC:1C:DB:6A:76:0C:FC:38:C0:AD:97:07:F2:7C:A9:6C:5A:54:6C:9A:FB:EA`.

## Automatic releases

Every push to `main` runs `.github/workflows/release.yml`. It runs `assembleRelease`, uploads the signed release APK as a workflow artifact, and creates a GitHub Release tagged `build-<run number>` containing `face-changer-custom-release.apk`. CI also uses the run number as Android `versionCode`, so newer builds install as upgrades as long as they use this same repository signing identity.

## Scripting

See [`app/src/main/assets/SCRIPTING.md`](app/src/main/assets/SCRIPTING.md). The same file is rendered by the app's Docs screen and copied by its Copy Docs button, preventing the repository documentation and in-app reference from drifting apart.

## Camera and memory architecture

The visible camera uses CameraX `PreviewView`, independent from MediaPipe inference. LOW/MEDIUM/HIGH use progressively larger analysis frames while `KEEP_ONLY_LATEST` drops stale analysis frames. Every temporary MediaPipe `MPImage` is explicitly closed after inference and bitmap ownership is bounded so camera processing cannot accumulate an unbounded frame queue.
