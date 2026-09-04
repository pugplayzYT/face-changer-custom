# Face Changer Custom scripting

The language is now intentionally **small and general**. There are no premade visual-effect commands such as `invert`, `bulge`, `grayscale`, `pixelate`, `tint`, `circle`, `skeleton`, or `connections`.

Instead, scripts build effects from a few safe primitives: variables, functions, conditions, bounded loops, pixel iteration, pixel writes, camera sampling, math, user inputs and MediaPipe landmarks.

Scripts still have **no file access, network access, shell, Android APIs, processes, reflection, arbitrary Kotlin/Java, native code, dynamic code loading, permissions, clipboard, contacts, microphone, package management or external storage access**.

## Inputs

`input number amount Amount 1 0 1`

`input text caption Caption hello_world`

Syntax: `input number|text NAME LABEL DEFAULT [MIN MAX]`.

Inputs become controls on the camera screen and variables inside the script.

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
- `image_width`, `image_height`, `aspect` — current analyzed frame geometry

## User functions

Functions are procedures made entirely from the same sandbox primitives.

`fn invert amount`

`  pixels`

`    set r lerp(r,1-r,amount)`

`    set g lerp(g,1-g,amount)`

`    set b lerp(b,1-b,amount)`

`  end`

`end`

Call a function with:

`call invert 1`

Function syntax:

`fn NAME [PARAM ...]`

`  ...`

`end`

`call NAME [ARG ...]`

Functions may call other functions. Call depth is bounded by the sandbox.

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

There is deliberately no unbounded `while` loop.

## Pixel loop

`pixels`

`  ...`

`end`

Inside a `pixels` block these variables exist:

- `x`, `y` — normalized pixel coordinates from 0 to 1
- `ix`, `iy` — integer pixel coordinates
- `r`, `g`, `b`, `a` — current output channels from 0 to 1

Change the current pixel with only these essential writes:

`set r EXPRESSION`

`set g EXPRESSION`

`set b EXPRESSION`

`set a EXPRESSION`

Channel values are clamped to 0..1.

A pixel loop may optionally be restricted to a normalized rectangle:

`pixels X Y WIDTH HEIGHT`

For example:

`pixels 0.25 0.25 0.5 0.5`

This is important for fast local effects.

Pixel loops cannot be nested.

## Source camera sampling

The source camera frame is read-only. Sample it at normalized coordinates with:

- `sample_r(x,y)`
- `sample_g(x,y)`
- `sample_b(x,y)`
- `sample_a(x,y)`
- `sample_luma(x,y)`

Coordinates are clamped to the frame and sampling is bilinear.

This is enough to write custom warps, blur kernels, sharpen filters, edge detectors, chromatic effects and other image operations without adding dedicated effect commands to the engine.

## Sparse pixel writing

`write_pixel X Y R G B A`

`write_pixel` writes one normalized output pixel. This is the low-level primitive for custom point/line/raster algorithms that do not need to scan the whole frame.

Example:

`repeat 100`

`  let t = loop/99`

`  write_pixel t t 1 0 0 1`

`end`

## Tracking

Each app selects Face, Hand or Body. Full MediaPipe landmarks are always exposed.

- `landmark_count(group)`
- `landmark_x(group,index)`
- `landmark_y(group,index)`
- `landmark_z(group,index)`
- `point_exists(group,index)`
- `landmark_distance(group,a,b)`
- `landmark_mid_x(group,a,b)`
- `landmark_mid_y(group,a,b)`
- `landmark_angle(group,a,b,c)`

Tracked-object bounds:

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

## Example: invert written by the script

There is no built-in invert opcode.

`input number amount Invert 1 0 1`

`fn invert strength`

`  pixels`

`    set r lerp(r,1-r,strength)`

`    set g lerp(g,1-g,strength)`

`    set b lerp(b,1-b,strength)`

`  end`

`end`

`call invert amount`

## Example: grayscale written by the script

`fn grayscale`

`  pixels`

`    let gray = r*0.299+g*0.587+b*0.114`

`    set r gray`

`    set g gray`

`    set b gray`

`  end`

`end`

`call grayscale`

## Example: custom eye bulge with no bulge opcode

This uses a local pixel region and source sampling. The effect itself is written in the script.

`input number size Eye_Size 1.9 1 3`

`fn bulge cx cy radius scale`

`  let left = cx-radius`

`  let top = cy-radius`

`  let diameter = radius*2`

`  pixels left top diameter diameter`

`    let dx = x-cx`

`    let dy = y-cy`

`    let d = hypot(dx,dy)`

`    if lt(d,radius)`

`      let t = d/radius`

`      let localScale = 1+(scale-1)*(1-t)*(1-t)`

`      let sx = cx+dx/localScale`

`      let sy = cy+dy/localScale`

`      set r sample_r(sx,sy)`

`      set g sample_g(sx,sy)`

`      set b sample_b(sx,sy)`

`      set a sample_a(sx,sy)`

`    end`

`  end`

`end`

`if tracked`

`  let leftX = landmark_mid_x(0,33,133)`

`  let leftY = landmark_mid_y(0,159,145)`

`  let rightX = landmark_mid_x(0,362,263)`

`  let rightY = landmark_mid_y(0,386,374)`

`  let radius = group_width(0)*0.115`

`  call bulge leftX leftY radius size`

`  call bulge rightX rightY radius size`

`end`

## Safety and resource limits

The sandbox is capability-based. Scripts only receive numbers, text inputs, time/frame values, MediaPipe landmarks, a read-only camera sampler and bounded output-pixel writes.

Hard limits include:

- maximum script size
- maximum statement count
- maximum function count and parameters
- maximum expression size, parser work and nesting
- maximum function call depth
- bounded `repeat`
- no nested `pixels`
- per-frame operation budget
- per-frame pixel-visit budget
- coordinates and color channels are clamped
- CameraX uses `KEEP_ONLY_LATEST`, so expensive scripts drop analysis frames instead of building an endless queue

The goal is broad visual programmability without giving user scripts capabilities outside the filter sandbox.

## Keeping docs current

This file is the canonical scripting reference. The in-app Docs page reads this exact asset and Copy Docs copies the same text.
