# Face Changer Custom scripting

Every custom app is plain text interpreted by the app. Scripts have **no file, network, shell, Android API, reflection, process, or arbitrary Kotlin/Java access**. The host only exposes tracking data, user inputs, control flow and approved pixel/drawing operations.

## Inputs

Inputs appear as controls on the live filter screen and act like variables.

`input number strength Strength 1.8 0.5 3.0`

`input text caption Caption hello`

Syntax: `input number|text NAME LABEL DEFAULT [MIN MAX]`. Use underscores in labels for spaces.

## Tracking modes and detail

Each app chooses Face, Hand or Body plus Low, Medium or High detail in the editor. High exposes every MediaPipe landmark. Medium and Low expose progressively sampled landmark sets for cheaper effects.

Coordinates are normalized: x=0 is left, x=1 is right, y=0 is top, y=1 is bottom. `group 0` means the first detected face/hand/body. A second hand is usually `group 1`.

## Drawing / pixel commands

`dots #47D7AC 4` draws a dot at every exposed landmark.

`skeleton #56A8FF 3` connects consecutive exposed landmarks. It is deliberately generic so built-in filters are written in the same language as user filters.

`magnify GROUP POINT SCALE RADIUS` enlarges pixels around a tracked point. SCALE and RADIUS can be input-variable names. Example: `magnify 0 33 strength 0.10`.

`pixelate BLOCK_SIZE` downsamples then nearest-neighbour upscales the whole camera frame.

`tint #RRGGBB AMOUNT` blends a color over the frame. AMOUNT is 0.0 to 1.0.

## Control flow

Conditionals:

`if tracked`

`  dots #47D7AC 5`

`else`

`  tint #FF0000 0.1`

`end`

Comparisons are supported: `==`, `!=`, `>`, `<`, `>=`, `<=`. Either side can be a number, text literal, or input variable.

Loops:

`repeat 4`

`  magnify 0 33 1.05 0.08`

`end`

Repeat counts are capped at 1000 per execution to keep scripts bounded.

## Example: giant eye-ish filter

The exact face point index depends on the detail level. At High detail, point 33 is near one eye in the standard MediaPipe face mesh.

`input number strength Eye_Size 1.8 0.7 3.0`

`if tracked`

`  magnify 0 33 strength 0.11`

`  dots #47D7AC 2`

`end`

## Example: chunky camera

`input number blocks Block_Size 16 2 80`

`pixelate blocks`

`tint #47D7AC 0.08`

## Safety and performance

The interpreter rejects unknown commands. It cannot call arbitrary functions. `repeat` is bounded, magnification scale/radius are clamped, and pixelation has a minimum block size. Camera analysis runs with KEEP_ONLY_LATEST so slow filters drop frames instead of building an ever-growing queue.

## Keeping docs current

This file is the canonical scripting reference and is bundled directly into the APK. The in-app Docs page reads **this exact asset** and its Copy Docs button copies the same text, so editing this file updates both the repository docs and the in-app docs on the next build.
