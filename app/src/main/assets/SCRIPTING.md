# Face Changer Custom scripting

Face Changer Custom uses a deliberately sandboxed filter language. The goal is **very high freedom inside the camera/filter world without giving scripts access to the phone itself**.

Scripts can combine tracking, math, animation, conditions, bounded loops, user controls, drawing and pixel warps in arbitrary ways. Scripts still have **no file access, network access, shell, Android APIs, processes, reflection, arbitrary Kotlin/Java, native code, dynamic code loading, permissions, clipboard, contacts, microphone, package management or external storage access**.

That boundary is intentional: the language can be extremely expressive as a filter language without becoming arbitrary app code.

## Inputs

Inputs become controls on the live filter screen and variables inside the script.

`input number strength Eye_Size 1.8 0.5 3.0`

`input text caption Caption hello_world`

Syntax: `input number|text NAME LABEL DEFAULT [MIN MAX]`. Use underscores for spaces in labels/default text.

## Tracking mode

Each app chooses **Face**, **Hand**, or **Body** in the editor.

There is no quality or landmark-detail switch. Every tracking mode always exposes the complete landmark set returned by its MediaPipe landmarker. A valid MediaPipe landmark index therefore does not disappear because of a lower-detail mode.

Coordinates are normalized: x=0 is left, x=1 is right, y=0 is top, y=1 is bottom. `group 0` is the first detected face/hand/body; a second hand is usually `group 1`.

The camera preview and analysis use the same CameraX viewport/crop, so landmark coordinates and scripted pixels refer to the same visible camera area.

## Variables and animation

Create or replace numeric variables with `let`:

`let pulse = 1.3 + sin(time*4)*0.25`

Built-in numeric values:

- `time` — seconds since the filter engine started.
- `frame` — processed-frame counter.
- `tracked` — 1 when something is tracked, otherwise 0.
- `groups` — number of tracked faces/hands/bodies.
- `loop` — current zero-based iteration inside `repeat`.
- `pi`, `tau`, `e` — mathematical constants.

Because `time` and `frame` change continuously, scripts can animate effects without timers or threads.

## Landmark functions

Use original MediaPipe indexes:

- `landmark_count(group)`
- `landmark_x(group,index)`
- `landmark_y(group,index)`
- `landmark_z(group,index)`
- `point_exists(group,index)` — 1 or 0
- `landmark_distance(group,a,b)` — normalized 2D distance
- `landmark_mid_x(group,a,b)`
- `landmark_mid_y(group,a,b)`
- `landmark_angle(group,a,b,c)` — angle at point B in radians

Example:

`let eyeX = landmark_mid_x(0,33,133)`

`let eyeY = landmark_mid_y(0,159,145)`

`circle eyeX eyeY 0.025 #47D7AC stroke`

## Group geometry

These helpers make effects scale with the detected face/hand/body instead of using hard-coded screen sizes:

- `group_min_x(group)`
- `group_max_x(group)`
- `group_min_y(group)`
- `group_max_y(group)`
- `group_width(group)`
- `group_height(group)`
- `group_center_x(group)`
- `group_center_y(group)`

Example: make a radius proportional to face width:

`let r = group_width(0)*0.12`

## Scientific math

Expressions support parentheses, `+ - * / % ^`, variables and scientific notation.

Functions include:

- Trig: `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`
- Roots/powers: `sqrt`, `cbrt`, `pow`, `exp`, `ln`, `log10`
- Numeric: `abs`, `floor`, `ceil`, `round`, `sign`, `min`, `max`, `sum`, `avg`, `mean`
- Mapping/interpolation: `clamp`, `saturate`, `lerp`, `inverse_lerp`, `map`, `smoothstep`, `step`, `fract`, `wrap`
- Geometry: `hypot`, `distance`, `angle`, `deg`, `rad`
- Deterministic animation/noise: `noise`, `hash`

Examples:

`let wobble = sin(time*tau)*0.08`

`let distanceFromCenter = hypot(landmark_x(0,4)-0.5,landmark_y(0,4)-0.5)`

`let faceRelativeRadius = group_width(0)*0.15`

## Boolean helpers

Boolean helpers return 1 for true and 0 for false. They can be nested, which gives scripts complex logic without exposing arbitrary code execution.

- `eq(a,b)`, `ne(a,b)`
- `lt(a,b)`, `lte(a,b)`
- `gt(a,b)`, `gte(a,b)`
- `and(a,b,...)`
- `or(a,b,...)`
- `not(value)`
- `select(condition,whenTrue,whenFalse)` / `ifelse(...)`

Example:

`if and(tracked,gt(group_width(0),0.25))`

`  tint #47D7AC 0.08`

`end`

## Skeleton drawing

`dots #47D7AC 4`

Draws every tracked landmark as a dot.

`connections #56A8FF 3`

Draws standard face-contour/eye/lip, hand-bone, or body-pose connections using original MediaPipe landmark indexes.

`skeleton #56A8FF 2`

Draws a simple consecutive-point chain. `connections` usually looks better for real skeletons.

## Pixel effects

`bulge X Y SCALE RADIUS`

Creates a smooth radial lens at normalized camera coordinates. Pixels are warped outward near the center and smoothly return to their original position at the edge, so it enlarges a feature instead of stretching a rectangular crop. `X`, `Y`, `SCALE`, and `RADIUS` are numeric expressions.

`magnify GROUP POINT SCALE RADIUS`

Creates the same smooth radial lens centered on a tracked landmark. Example:

`magnify 0 33 strength 0.10`

`pixelate BLOCK_SIZE`

Downsamples then nearest-neighbour upscales the visible camera frame.

`tint #RRGGBB AMOUNT`

Blends a color over the frame. AMOUNT is clamped from 0.0 to 1.0.

Local effects such as `bulge` and `magnify` render over the native CameraX preview instead of globally zooming it.

## Drawing primitives

All x/y/w/h/radius coordinates are normalized to the visible camera frame.

`circle X Y RADIUS #RRGGBB [stroke]`

`line X1 Y1 X2 Y2 #RRGGBB [WIDTH_PIXELS]`

`rect X Y W H #RRGGBB [stroke]`

`text VALUE_OR_TEXT_VARIABLE X Y SIZE_PIXELS #RRGGBB`

For literal text, use underscores for spaces: `text hello_world 0.05 0.10 34 #FFFFFF`.

## If / else

`if tracked`

`  connections #47D7AC 3`

`else`

`  tint #FF0000 0.10`

`end`

Simple comparisons `==`, `!=`, `>`, `<`, `>=`, `<=` are supported. For complex logic, prefer the boolean helpers above.

## Loops

`repeat 8`

`  let radius = 0.01 + loop*0.004`

`  circle 0.5 0.5 radius #47D7AC stroke`

`end`

`repeat` is intentionally bounded. There is no unbounded `while` loop.

## Example: face-relative giant eyes

This version scales the effect with the detected face instead of using one fixed radius for every distance from the camera.

`input number size Eye_Size 1.9 1.0 3.0`

`if tracked`

`  let leftX = landmark_mid_x(0,33,133)`

`  let leftY = landmark_mid_y(0,159,145)`

`  let rightX = landmark_mid_x(0,362,263)`

`  let rightY = landmark_mid_y(0,386,374)`

`  let eyeRadius = group_width(0)*0.115`

`  bulge leftX leftY size eyeRadius`

`  bulge rightX rightY size eyeRadius`

`end`

## Example: cyber hand

`input number glow Glow 5 1 15`

`connections #47D7AC glow`

`dots #56A8FF 3`

`if tracked`

`  let wobble = sin(time*4)*0.02`

`  circle landmark_x(0,8) landmark_y(0,8) 0.035+wobble #FFFFFF stroke`

`end`

## Example: adaptive face halo

`if tracked`

`  let cx = group_center_x(0)`

`  let cy = group_center_y(0)`

`  let r = max(group_width(0),group_height(0))*0.6`

`  circle cx cy r #56A8FF stroke`

`end`

## Sandbox and resource limits

The safety model is capability-based: scripts only receive numbers, text inputs, MediaPipe landmarks, time/frame values, a bounded drawing target and a fixed whitelist of filter operations.

Safety rules include:

- No filesystem, network, shell, Android API, reflection, process, native-code or dynamic-code access.
- No arbitrary class/function dispatch. Unknown script functions fail instead of secretly calling host code.
- No unbounded loops. `repeat` is capped at 1000 iterations per execution.
- Expressions are capped at 1024 characters, 4096 parser steps, 64 nesting levels and 16 function arguments.
- Bulge/magnification radius and scale are clamped.
- Divide-by-zero and non-finite math resolve safely.
- CameraX uses `KEEP_ONLY_LATEST`, so expensive analysis drops stale frames instead of creating an ever-growing queue.
- The visible camera preview remains independent for overlay-style effects.

This means the language is intentionally **not** a general Android programming language. It is designed to be as programmable as practical for camera filters while keeping the host device outside the script sandbox.

## Keeping docs current

This file is the canonical scripting reference and is bundled directly into the APK. The in-app Docs page reads this exact asset and its Copy Docs button copies the same text. Updating `app/src/main/assets/SCRIPTING.md` updates both the repository reference and the in-app reference on the next build.
