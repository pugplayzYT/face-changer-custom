# Face Changer Custom scripting

The app includes premade filters such as Face Mesh, Hand Skeleton and Body Skeleton, but **the programming language does not contain premade visual-effect commands**. Those premades are ordinary scripts built from the exact same primitives available to custom filters, and their source can be opened and forked.

The language is deliberately small and general. There is no built-in `invert`, `grayscale`, `sepia`, `bulge`, `blur`, `pixelate`, `circle`, `line`, `skeleton`, or `connections` opcode. You build those effects yourself from functions, math, pixel access, camera sampling, drawing-by-pixel, and MediaPipe landmarks.

Scripts have no file access, network access, shell, Android APIs, processes, reflection, arbitrary Kotlin/Java, native code, dynamic code loading, permissions, clipboard, contacts, microphone, package management, or external-storage access.

## Inputs

`input number amount Invert 1 0 1`

`input text caption Caption hello_world`

Syntax: `input number|text NAME LABEL DEFAULT [MIN MAX]`.

Inputs become live controls on the camera screen and variables inside the script.

## Variables

`let value = 1.5`

`let wave = sin(time*tau)*0.25`

Built-in numeric values:

- `time` — seconds since this engine instance started
- `frame` — processed-frame counter
- `tracked` — 1 when the selected tracker found something
- `groups` — number of tracked faces/hands/bodies
- `loop` — zero-based index inside `repeat`
- `pi`, `tau`, `e` — constants
- `image_width`, `image_height`, `aspect` — analyzed-frame geometry

## User functions

Functions are made from the same sandbox primitives as top-level code.

`fn invert strength`

`  pixels`

`    set r lerp(r,1-r,strength)`

`    set g lerp(g,1-g,strength)`

`    set b lerp(b,1-b,strength)`

`  end`

`end`

Call it with:

`call invert 1`

Syntax:

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

Comparisons: `==`, `!=`, `>`, `<`, `>=`, `<=`.

Boolean helpers return 1 or 0: `eq`, `ne`, `lt`, `lte`, `gt`, `gte`, `and`, `or`, `not`, `select` / `ifelse`.

## Bounded loops

`repeat 10`

`  let t = loop/9`

`end`

There is no unbounded `while` loop.

## Full-frame pixel programs

`pixels`

`  ...`

`end`

A script containing `pixels` is a **full-frame camera filter**. Its processed bitmap is placed over the native CameraX preview, so changes to `r`, `g`, `b`, and `a` affect the visible camera image itself rather than a transparent drawing layer.

Inside `pixels`:

- `x`, `y` — normalized coordinates from 0 to 1
- `ix`, `iy` — integer pixel coordinates
- `r`, `g`, `b`, `a` — current channels from 0 to 1

Change the current pixel with:

`set r EXPRESSION`

`set g EXPRESSION`

`set b EXPRESSION`

`set a EXPRESSION`

Channel values are clamped to 0..1.

A pixel loop can be restricted to a normalized rectangle:

`pixels X Y WIDTH HEIGHT`

Example:

`pixels 0.25 0.25 0.5 0.5`

Pixel blocks cannot be nested.

## Source camera sampling

The source camera frame is read-only. Sample it at normalized coordinates with:

- `sample_r(x,y)`
- `sample_g(x,y)`
- `sample_b(x,y)`
- `sample_a(x,y)`
- `sample_luma(x,y)`

Coordinates are clamped and sampling is bilinear. These primitives are enough to write custom warps, blur kernels, sharpen filters, edge detectors, chromatic effects, and other image operations without adding named effects to the engine.

## Sparse pixel writing

`write_pixel X Y R G B A`

This writes one normalized output pixel. Scripts without a `pixels` block render as a transparent overlay on the smooth native preview, which is ideal for meshes, skeletons, labels, particles, and custom raster drawing.

Example:

`repeat 100`

`  let t = loop/99`

`  write_pixel t t 1 0 0 1`

`end`

## Tracking

Each filter selects Face, Hand or Body. Full MediaPipe landmark sets are exposed; there is no quality/LOD selector.

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

Available helpers include:

- trig: `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`
- numeric: `sqrt`, `cbrt`, `abs`, `floor`, `ceil`, `round`, `sign`, `min`, `max`, `sum`, `avg`, `mean`, `pow`, `ln`, `log10`, `exp`
- mapping: `clamp`, `saturate`, `lerp`, `inverse_lerp`, `map`, `smoothstep`, `step`, `fract`, `wrap`
- geometry: `hypot`, `distance`, `angle`, `deg`, `rad`
- deterministic animation: `noise`, `hash`

## Example: invert

There is no invert opcode. This is the entire effect:

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

## Example: local eye warp

This is a custom effect built from camera sampling rather than a built-in bulge command.

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

## Sandbox and performance limits

The language is capability-based: scripts only receive numbers, text inputs, MediaPipe landmarks, time/frame values, a bounded output image, and a read-only camera sampler.

Loops, function depth, statement count, script size, expression complexity, per-frame operations, and per-frame pixel visits are bounded. CameraX uses `KEEP_ONLY_LATEST`, so expensive scripts drop old frames instead of building an ever-growing latency queue.

Color-only pixel programs skip MediaPipe tracking when the script does not reference tracking data, reducing unnecessary work.

## Keeping docs current

This file is the canonical scripting reference. The in-app Docs screen reads this exact asset and Copy Docs copies this exact text.
