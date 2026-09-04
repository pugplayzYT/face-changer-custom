# Face Changer Custom scripting

Every custom app is plain text interpreted by Face Changer Custom. The language is deliberately sandboxed: scripts have **no file, network, shell, Android API, process, reflection, arbitrary Kotlin/Java, or dynamic-code access**. They can only use the tracking data, controls, math, control flow, drawing operations and pixel effects documented here.

## Inputs

Inputs become controls on the live filter screen and are variables inside the script.

`input number strength Eye_Size 1.8 0.5 3.0`

`input text caption Caption hello_world`

Syntax: `input number|text NAME LABEL DEFAULT [MIN MAX]`. Use underscores for spaces in labels/default text.

## Tracking mode

Each app chooses **Face**, **Hand**, or **Body** in the editor.

There is no quality or landmark-detail switch. Every tracking mode always exposes the complete landmark set returned by its MediaPipe landmarker. A valid MediaPipe landmark index therefore does not disappear because of a lower-detail mode.

Coordinates are normalized: x=0 is left, x=1 is right, y=0 is top, y=1 is bottom. `group 0` is the first detected face/hand/body; a second hand is usually `group 1`.

The camera preview and analysis use the same CameraX viewport/crop, so landmark coordinates and scripted pixels refer to the same visible camera area.

## Variables and animation

Create numeric variables with `let`:

`let pulse = 1.3 + sin(time*4)*0.25`

Built-in numeric values:

- `time` — seconds since the filter engine started.
- `frame` — processed-frame counter.
- `tracked` — 1 when something is tracked, otherwise 0.
- `groups` — number of tracked faces/hands/bodies.
- `loop` — current zero-based iteration inside `repeat`.
- `pi`, `e` — mathematical constants.

Because `time` and `frame` change continuously, scripts can animate effects without timers or threads.

## Landmark functions

Use original MediaPipe indexes:

- `landmark_count(group)`
- `landmark_x(group,index)`
- `landmark_y(group,index)`
- `landmark_z(group,index)`
- `point_exists(group,index)` returns 1 or 0.

Example:

`let eyeX = landmark_x(0,33)`

`let eyeY = landmark_y(0,33)`

`circle eyeX eyeY 0.025 #47D7AC stroke`

## Scientific math

Expressions support parentheses plus `+ - * / % ^` and scientific notation.

Functions: `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `sqrt`, `cbrt`, `abs`, `floor`, `ceil`, `round`, `sign`, `min`, `max`, `pow`, `ln`, `log10`, `exp`, `hypot`, `deg`, `rad`, `clamp`, `lerp`, `smoothstep`, `fract`, and deterministic animated `noise`.

Examples:

`let wobble = sin(time*6.28318)*0.08`

`let distance = hypot(landmark_x(0,4)-0.5,landmark_y(0,4)-0.5)`

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

Creates the same smooth radial lens centered on a tracked landmark. `GROUP`, `POINT`, `SCALE`, and `RADIUS` are numeric expressions. Example:

`magnify 0 33 strength 0.10`

`pixelate BLOCK_SIZE`

Downsamples then nearest-neighbour upscales the whole visible camera frame.

`tint #RRGGBB AMOUNT`

Blends a color over the frame. AMOUNT is clamped from 0.0 to 1.0.

Local effects such as `bulge` and `magnify` are rendered as a transparent overlay on the native CameraX preview. They do not replace or globally zoom the preview.

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

Comparisons: `==`, `!=`, `>`, `<`, `>=`, `<=`. Numeric sides can be full expressions; equality also works with text-input variables.

## Loops

`repeat 8`

`  let radius = 0.01 + loop*0.004`

`  circle 0.5 0.5 radius #47D7AC stroke`

`end`

Repeat counts are hard-capped at 1000 per execution so a script cannot create an unbounded loop.

## Example: actually make both eyes bigger

`input number size Eye_Size 1.9 1.0 3.0`

`if tracked`

`  let leftX = (landmark_x(0,33)+landmark_x(0,133))/2`

`  let leftY = (landmark_y(0,159)+landmark_y(0,145))/2`

`  let rightX = (landmark_x(0,362)+landmark_x(0,263))/2`

`  let rightY = (landmark_y(0,386)+landmark_y(0,374))/2`

`  bulge leftX leftY size 0.075`

`  bulge rightX rightY size 0.075`

`end`

This uses the eye corners and eyelids to calculate the center of each eye, so the effect follows the whole eye rather than zooming a random landmark.

## Example: cyber hand

`input number glow Glow 5 1 15`

`connections #47D7AC glow`

`dots #56A8FF 3`

`if tracked`

`  let wobble = sin(time*4)*0.02`

`  circle landmark_x(0,8) landmark_y(0,8) 0.035+wobble #FFFFFF stroke`

`end`

## Example: chunky animated camera

`input number blocks Block_Size 16 2 80`

`pixelate blocks`

`let pulse = 0.05 + (sin(time*3)+1)*0.03`

`tint #47D7AC pulse`

## Sandbox / performance rules

Unknown commands are rejected while saving. There is no arbitrary function dispatch. `repeat` is bounded, bulge/magnification radius and scale are clamped, and divide-by-zero math resolves safely. CameraX runs `KEEP_ONLY_LATEST`, so an expensive filter drops intermediate analysis frames instead of building an ever-growing latency queue. The visible preview remains independent and smooth for overlay-only effects.

## Keeping docs current

This file is the **canonical scripting reference** and is bundled directly into the APK. The in-app Docs page reads this exact asset and its Copy Docs button copies the same text. Updating `app/src/main/assets/SCRIPTING.md` therefore updates the repository reference and the app reference together on the next build.
