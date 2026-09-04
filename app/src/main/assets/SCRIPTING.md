# Face Changer Custom scripting

Face Changer Custom has a small sandboxed filter language for camera pixels and MediaPipe tracking. Premade filters such as Face Mesh, Hand Skeleton, and Body Skeleton are built from the same primitives available to custom filters.

The language does **not** expose file access, networking, shell commands, Android APIs, arbitrary Kotlin/Java, native code, reflection, dynamic code loading, contacts, microphone access, or external storage.

## Performance modes

Performance is controlled **only by target FPS**. It does not remove landmarks, switch to a lower-detail model, or change script semantics.

- **LOW — 15 FPS**
- **MEDIUM — 30 FPS**
- **MAX — 60 FPS**

The camera pipeline prefers an advertised adaptive range (for example 15–60 or 15–30 FPS), allowing longer exposures in dim light instead of forcing a dark, noisy fixed-60-FPS preview. If no suitable adaptive range exists, it keeps the camera defaults. The frame pacer accepts frames up to the selected rate. Tap the FPS badge on the camera screen to cycle LOW → MEDIUM → MAX.

Tracking and filter rendering run on separate workers. MediaPipe keeps processing the newest available tracking frame while the renderer continues at the selected frame cadence. At MAX, tracking landmarks are interpolated between new MediaPipe results so overlays move smoothly instead of teleporting from one detection to the next.

MAX is a 60 FPS target. A device or camera that cannot physically supply or process 60 frames each second can still run below that target.

## How scripts execute

When a filter is applied, supported numeric pixel programs compile once into the app's compact bytecode VM.

- Variables use numeric array slots instead of string maps.
- Expressions compile to stack bytecode.
- Pixel loops avoid per-pixel parsing and string conversion.
- Unsupported language features safely fall back to the compatibility interpreter.

This keeps the language sandboxed while avoiding the enormous overhead of interpreting source text for every camera pixel.

## Inputs

Syntax:

`input number|text NAME LABEL DEFAULT [MIN MAX]`

Examples:

`input number amount Invert 1 0 1`
`input text caption Caption hello_world`

Inputs become live controls on the camera screen and variables inside the script.

## Variables

`let value = 1.5`
`let wave = sin(time*tau)*0.25`

Built-in numeric values:

- `time` — seconds since this engine instance started
- `frame` — processed-frame counter
- `tracked` — 1 when the selected tracker has a result
- `groups` — number of tracked faces, hands, or bodies
- `loop` — zero-based index inside `repeat`
- `pi`, `tau`, `e` — constants
- `image_width`, `image_height`, `aspect` — analyzed-frame geometry

## User functions

Function definition:

`fn invert strength`
`  pixels`
`    set r lerp(r,1-r,strength)`
`    set g lerp(g,1-g,strength)`
`    set b lerp(b,1-b,strength)`
`  end`
`end`

Call it with:

`call invert 1`

General syntax:

`fn NAME [PARAM ...]`
`  ...`
`end`
`call NAME [ARG ...]`

Function-call depth is bounded by the sandbox.

## If / else

`if tracked`
`  let strength = 1`
`else`
`  let strength = 0`
`end`

Comparisons:

- `==`
- `!=`
- `>`
- `<`
- `>=`
- `<=`

Boolean helpers return 1 or 0: `eq`, `ne`, `lt`, `lte`, `gt`, `gte`, `and`, `or`, `not`, `select`, and `ifelse`.

## Bounded loops

`repeat 10`
`  let t = loop/9`
`end`

There is no unbounded `while` loop.

## Pixel programs

A full-frame pixel block is:

`pixels`
`  ...`
`end`

Inside a pixel block:

- `x`, `y` — normalized coordinates from 0 to 1
- `ix`, `iy` — integer pixel coordinates
- `r`, `g`, `b`, `a` — current channels from 0 to 1

Set channels with:

`set r EXPRESSION`
`set g EXPRESSION`
`set b EXPRESSION`
`set a EXPRESSION`

Channel values are clamped to 0..1.

A pixel block can be restricted to a normalized rectangle:

`pixels X Y WIDTH HEIGHT`

Example:

`pixels 0.25 0.25 0.5 0.5`

Pixel blocks cannot be nested.

## Source camera sampling

The camera source is read-only. Sample it at normalized coordinates with:

- `sample_r(x,y)`
- `sample_g(x,y)`
- `sample_b(x,y)`
- `sample_a(x,y)`
- `sample_luma(x,y)`

Sampling is bilinear and coordinates are clamped. These functions can be combined to build warps, blur kernels, sharpening, edge detection, chromatic effects, and other image operations.

## Sparse pixel writing

`write_pixel X Y R G B A`

Scripts without a `pixels` block render as a transparent overlay over the native preview. This is useful for meshes, skeletons, particles, and custom raster drawing.

Example:

`repeat 100`
`  let t = loop/99`
`  write_pixel t t 1 0 0 1`
`end`

## Tracking

Each filter selects Face, Hand, or Body. Full MediaPipe landmark sets are exposed at every performance level.

Landmark functions:

- `landmark_count(group)`
- `landmark_x(group,index)`
- `landmark_y(group,index)`
- `landmark_z(group,index)`
- `point_exists(group,index)`
- `landmark_distance(group,a,b)`
- `landmark_mid_x(group,a,b)`
- `landmark_mid_y(group,a,b)`
- `landmark_angle(group,a,b,c)`

Tracked-object geometry:

- `group_min_x(group)` / `group_max_x(group)`
- `group_min_y(group)` / `group_max_y(group)`
- `group_width(group)` / `group_height(group)`
- `group_center_x(group)` / `group_center_y(group)`

## Math

Expressions support parentheses and `+ - * / % ^`.

Helpers include:

- Trigonometry: `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`
- Numeric: `sqrt`, `cbrt`, `abs`, `floor`, `ceil`, `round`, `sign`, `min`, `max`, `sum`, `avg`, `mean`, `pow`, `ln`, `log10`, `exp`
- Mapping: `clamp`, `saturate`, `lerp`, `inverse_lerp`, `map`, `smoothstep`, `step`, `fract`, `wrap`
- Geometry: `hypot`, `distance`, `angle`, `deg`, `rad`
- Deterministic animation: `noise`, `hash`

## Example: invert

There is no special invert opcode. The complete effect is:

`input number amount Invert 1 0 1`
`fn invert strength`
`  pixels`
`    set r lerp(r,1-r,strength)`
`    set g lerp(g,1-g,strength)`
`    set b lerp(b,1-b,strength)`
`  end`
`end`
`call invert amount`

## Example: grayscale

`fn grayscale`
`  pixels`
`    let gray = r*0.299+g*0.587+b*0.114`
`    set r gray`
`    set g gray`
`    set b gray`
`  end`
`end`
`call grayscale`

## Example: monochrome eyes

`fn mono_eye cx cy radius`
`  pixels cx-radius cy-radius radius*2 radius*2`
`    let dx = x-cx`
`    let dy = y-cy`
`    let d = hypot(dx,dy)`
`    if lt(d,radius)`
`      let gray = r*0.299+g*0.587+b*0.114`
`      set r gray`
`      set g gray`
`      set b gray`
`    end`
`  end`
`end`
`if tracked`
`  let eyeRadius = group_width(0)*0.10`
`  let leftX = landmark_mid_x(0,33,133)`
`  let leftY = landmark_mid_y(0,159,145)`
`  let rightX = landmark_mid_x(0,362,263)`
`  let rightY = landmark_mid_y(0,386,374)`
`  call mono_eye leftX leftY eyeRadius`
`  call mono_eye rightX rightY eyeRadius`
`end`

## Example: local eye warp

`input number size Eye_Size 1.8 1 3`
`fn warp cx cy radius scale`
`  pixels cx-radius cy-radius radius*2 radius*2`
`    let dx = x-cx`
`    let dy = y-cy`
`    let d = hypot(dx,dy)`
`    if lt(d,radius)`
`      let falloff = 1-d/radius`
`      let local = 1+(scale-1)*falloff*falloff`
`      let sx = cx+dx/local`
`      let sy = cy+dy/local`
`      set r sample_r(sx,sy)`
`      set g sample_g(sx,sy)`
`      set b sample_b(sx,sy)`
`    end`
`  end`
`end`
`if tracked`
`  let leftX = landmark_mid_x(0,33,133)`
`  let leftY = landmark_mid_y(0,159,145)`
`  let radius = group_width(0)*0.12`
`  call warp leftX leftY radius size`
`end`

## Sandbox limits

The language is capability-based. Scripts receive only script inputs, numbers, MediaPipe landmarks, time/frame values, a bounded output image, and a read-only camera sampler.

The engine bounds loops, function depth, statement count, script size, expression complexity, per-frame operations, and per-frame pixel visits. CameraX uses `KEEP_ONLY_LATEST`, so an expensive filter drops stale frames instead of accumulating seconds of queued latency.

Color-only filters skip MediaPipe when the script does not reference tracking values.

## Keeping docs current

`SCRIPTING.md` is the canonical scripting reference. The in-app Docs screen reads this exact asset, and Copy Docs copies this exact text.
