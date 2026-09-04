package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/**
 * Ahead-of-frame compiler for the numeric/pixel subset of the sandbox language.
 *
 * ScriptEngine remains the compatibility interpreter. When a program is numeric and uses only
 * supported expressions, this compiler lowers it once to a compact VM representation:
 * - variables live in DoubleArray slots instead of Map<String, String>
 * - expressions are postfix stack bytecode instead of AST/string evaluation
 * - pixel loops execute without per-pixel parsing, toString(), toDoubleOrNull(), or map lookups
 *
 * Unsupported programs simply return null and continue through ScriptEngine unchanged.
 */
internal fun compilePixelBytecode(program: ScriptEngine.Program): PixelBytecodeProgram? =
    runCatching { PixelBytecodeCompiler(program).compile() }.getOrNull()

internal class PixelBytecodeProgram internal constructor(
    private val inputSlots: List<InputSlot>,
    private val slotCount: Int,
    private val specialSlots: SpecialSlots,
    private val topLevel: List<VmInstruction>,
    private val functions: Map<String, VmFunction>
) {
    private var frameCounter = 0L
    private val startedNanos = System.nanoTime()

    fun render(source: Bitmap, frame: TrackingFrame, values: Map<String, String>): Bitmap {
        val width = source.width
        val height = source.height
        val count = width * height
        val sourcePixels = IntArray(count)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        val outputPixels = sourcePixels.copyOf()

        val vars = DoubleArray(slotCount)
        inputSlots.forEach { input ->
            vars[input.slot] = (values[input.name] ?: input.defaultValue).toDoubleOrNull() ?: 0.0
        }
        vars[specialSlots.imageWidth] = width.toDouble()
        vars[specialSlots.imageHeight] = height.toDouble()
        vars[specialSlots.aspect] = width.toDouble() / max(1, height).toDouble()

        val context = VmContext(
            vars = vars,
            sourcePixels = sourcePixels,
            outputPixels = outputPixels,
            width = width,
            height = height,
            tracking = frame,
            frameIndex = frameCounter++,
            elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0,
            stack = DoubleArray(VM_STACK_SIZE),
            callScratch = DoubleArray(MAX_CALL_DEPTH * MAX_CALL_ARGS * 2),
            specialSlots = specialSlots,
            functions = functions
        )
        executeBlock(topLevel, context, pixelActive = false, callDepth = 0)

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outputPixels, 0, width, 0, 0, width, height)
        }
    }
}

internal data class InputSlot(val name: String, val defaultValue: String, val slot: Int)
internal data class SpecialSlots(
    val imageWidth: Int,
    val imageHeight: Int,
    val aspect: Int,
    val loop: Int,
    val x: Int,
    val y: Int,
    val ix: Int,
    val iy: Int,
    val r: Int,
    val g: Int,
    val b: Int,
    val a: Int
)

internal data class VmFunction(
    val parameterSlots: IntArray,
    val body: List<VmInstruction>
)

internal sealed interface VmInstruction
private data class VmStore(val slot: Int, val expression: ExprCode) : VmInstruction
private data class VmSetChannel(val slot: Int, val expression: ExprCode) : VmInstruction
private data class VmWritePixel(
    val x: ExprCode,
    val y: ExprCode,
    val r: ExprCode,
    val g: ExprCode,
    val b: ExprCode,
    val a: ExprCode
) : VmInstruction
private data class VmPixels(
    val x: ExprCode?,
    val y: ExprCode?,
    val width: ExprCode?,
    val height: ExprCode?,
    val body: List<VmInstruction>
) : VmInstruction
private data class VmRepeat(val count: ExprCode, val body: List<VmInstruction>) : VmInstruction
private data class VmIf(val condition: ExprCode, val yes: List<VmInstruction>, val no: List<VmInstruction>) : VmInstruction
private data class VmCall(val name: String, val arguments: List<ExprCode>) : VmInstruction

private class PixelBytecodeCompiler(private val program: ScriptEngine.Program) {
    private val slots = linkedMapOf<String, Int>()
    private lateinit var special: SpecialSlots

    fun compile(): PixelBytecodeProgram {
        require(program.usesPixels) { "No pixel program to compile" }
        require(program.inputs.none { it.type == InputType.TEXT }) { "Text inputs use interpreter semantics" }

        val imageWidth = slot("image_width")
        val imageHeight = slot("image_height")
        val aspect = slot("aspect")
        val loop = slot("loop")
        val x = slot("x")
        val y = slot("y")
        val ix = slot("ix")
        val iy = slot("iy")
        val r = slot("r")
        val g = slot("g")
        val b = slot("b")
        val a = slot("a")
        special = SpecialSlots(imageWidth, imageHeight, aspect, loop, x, y, ix, iy, r, g, b, a)

        program.inputs.forEach { slot(it.name) }
        scanNames(program.statements)
        program.functions.values.forEach { function ->
            function.parameters.forEach(::slot)
            scanNames(function.body)
        }

        val functions = program.functions.mapValues { (_, function) ->
            VmFunction(
                parameterSlots = function.parameters.map(::slot).toIntArray(),
                body = compileBlock(function.body)
            )
        }
        val top = compileBlock(program.statements)
        val inputSlots = program.inputs.map { InputSlot(it.name, it.defaultValue, slot(it.name)) }

        return PixelBytecodeProgram(inputSlots, slots.size, special, top, functions)
    }

    private fun scanNames(statements: List<ScriptEngine.Statement>) {
        statements.forEach { statement ->
            when (statement) {
                is ScriptEngine.Let -> slot(statement.name)
                is ScriptEngine.Pixels -> scanNames(statement.body)
                is ScriptEngine.Repeat -> scanNames(statement.body)
                is ScriptEngine.If -> {
                    scanNames(statement.yes)
                    scanNames(statement.no)
                }
                else -> Unit
            }
        }
    }

    private fun compileBlock(statements: List<ScriptEngine.Statement>): List<VmInstruction> =
        statements.map { statement ->
            when (statement) {
                is ScriptEngine.Let -> VmStore(slot(statement.name), expr(statement.expression))
                is ScriptEngine.SetChannel -> VmSetChannel(slot(statement.channel), expr(statement.expression))
                is ScriptEngine.WritePixel -> VmWritePixel(
                    expr(statement.x), expr(statement.y), expr(statement.r), expr(statement.g),
                    expr(statement.b), expr(statement.a)
                )
                is ScriptEngine.Pixels -> VmPixels(
                    statement.x?.let(::expr),
                    statement.y?.let(::expr),
                    statement.width?.let(::expr),
                    statement.height?.let(::expr),
                    compileBlock(statement.body)
                )
                is ScriptEngine.Repeat -> VmRepeat(expr(statement.countExpression), compileBlock(statement.body))
                is ScriptEngine.If -> VmIf(expr(statement.expression), compileBlock(statement.yes), compileBlock(statement.no))
                is ScriptEngine.Call -> {
                    require(statement.name in program.functions) { "Unknown function ${statement.name}" }
                    VmCall(statement.name, statement.arguments.map(::expr))
                }
            }
        }

    private fun expr(source: String): ExprCode = ExprCompiler(source, slots, ::slot).compile()

    private fun slot(name: String): Int = slots.getOrPut(name) { slots.size }
}

private class VmContext(
    val vars: DoubleArray,
    val sourcePixels: IntArray,
    val outputPixels: IntArray,
    val width: Int,
    val height: Int,
    val tracking: TrackingFrame,
    val frameIndex: Long,
    val elapsedSeconds: Double,
    val stack: DoubleArray,
    val callScratch: DoubleArray,
    val specialSlots: SpecialSlots,
    val functions: Map<String, VmFunction>
) {
    var pixelVisits = 0

    fun sample(x: Double, y: Double): Int {
        val ix = (x.coerceIn(0.0, 1.0) * (width - 1)).roundToInt().coerceIn(0, width - 1)
        val iy = (y.coerceIn(0.0, 1.0) * (height - 1)).roundToInt().coerceIn(0, height - 1)
        return sourcePixels[iy * width + ix]
    }

    fun group(index: Double): List<Point3>? = tracking.groups.getOrNull(index.toInt())

    fun point(groupIndex: Double, landmarkIndex: Double): Point3? {
        val group = group(groupIndex) ?: return null
        val wanted = landmarkIndex.toInt()
        return group.firstOrNull { it.index == wanted } ?: group.getOrNull(wanted)
    }

    fun bounds(index: Double): Bounds? {
        val group = group(index) ?: return null
        if (group.isEmpty()) return null
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        group.forEach { p ->
            minX = min(minX, p.x.toDouble())
            maxX = max(maxX, p.x.toDouble())
            minY = min(minY, p.y.toDouble())
            maxY = max(maxY, p.y.toDouble())
        }
        return Bounds(minX, maxX, minY, maxY)
    }
}

private data class Bounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

private fun executeBlock(
    instructions: List<VmInstruction>,
    context: VmContext,
    pixelActive: Boolean,
    callDepth: Int
) {
    instructions.forEach { instruction ->
        when (instruction) {
            is VmStore -> context.vars[instruction.slot] = eval(instruction.expression, context)
            is VmSetChannel -> {
                require(pixelActive) { "set r/g/b/a is only valid inside pixels" }
                context.vars[instruction.slot] = eval(instruction.expression, context).coerceIn(0.0, 1.0)
            }
            is VmWritePixel -> {
                val x = eval(instruction.x, context).coerceIn(0.0, 1.0)
                val y = eval(instruction.y, context).coerceIn(0.0, 1.0)
                val ix = (x * (context.width - 1)).roundToInt().coerceIn(0, context.width - 1)
                val iy = (y * (context.height - 1)).roundToInt().coerceIn(0, context.height - 1)
                context.outputPixels[iy * context.width + ix] = vmColor(
                    eval(instruction.r, context), eval(instruction.g, context),
                    eval(instruction.b, context), eval(instruction.a, context)
                )
            }
            is VmPixels -> executePixels(instruction, context, callDepth)
            is VmRepeat -> {
                val count = eval(instruction.count, context).toInt().coerceIn(0, MAX_REPEAT_VM)
                val loopSlot = context.specialSlots.loop
                val oldLoop = context.vars[loopSlot]
                repeat(count) { index ->
                    context.vars[loopSlot] = index.toDouble()
                    executeBlock(instruction.body, context, pixelActive, callDepth)
                }
                context.vars[loopSlot] = oldLoop
            }
            is VmIf -> executeBlock(
                if (truthy(eval(instruction.condition, context))) instruction.yes else instruction.no,
                context,
                pixelActive,
                callDepth
            )
            is VmCall -> executeCall(instruction, context, pixelActive, callDepth)
        }
    }
}

private fun executeCall(instruction: VmCall, context: VmContext, pixelActive: Boolean, callDepth: Int) {
    require(callDepth < MAX_CALL_DEPTH) { "Function call depth exceeded" }
    val function = context.functions[instruction.name] ?: error("Unknown function ${instruction.name}")
    require(function.parameterSlots.size == instruction.arguments.size) {
        "${instruction.name} expects ${function.parameterSlots.size} arguments, got ${instruction.arguments.size}"
    }
    require(function.parameterSlots.size <= MAX_CALL_ARGS) { "Too many function arguments" }

    val base = callDepth * MAX_CALL_ARGS * 2
    instruction.arguments.forEachIndexed { index, expression ->
        context.callScratch[base + index] = eval(expression, context)
    }
    function.parameterSlots.forEachIndexed { index, slot ->
        context.callScratch[base + MAX_CALL_ARGS + index] = context.vars[slot]
        context.vars[slot] = context.callScratch[base + index]
    }
    try {
        executeBlock(function.body, context, pixelActive, callDepth + 1)
    } finally {
        function.parameterSlots.forEachIndexed { index, slot ->
            context.vars[slot] = context.callScratch[base + MAX_CALL_ARGS + index]
        }
    }
}

private fun executePixels(instruction: VmPixels, context: VmContext, callDepth: Int) {
    val nx = instruction.x?.let { eval(it, context) } ?: 0.0
    val ny = instruction.y?.let { eval(it, context) } ?: 0.0
    val nw = instruction.width?.let { eval(it, context) } ?: 1.0
    val nh = instruction.height?.let { eval(it, context) } ?: 1.0

    val x0n = min(nx, nx + nw).coerceIn(0.0, 1.0)
    val x1n = max(nx, nx + nw).coerceIn(0.0, 1.0)
    val y0n = min(ny, ny + nh).coerceIn(0.0, 1.0)
    val y1n = max(ny, ny + nh).coerceIn(0.0, 1.0)

    val left = floor(x0n * context.width).toInt().coerceIn(0, context.width)
    val right = ceil(x1n * context.width).toInt().coerceIn(0, context.width)
    val top = floor(y0n * context.height).toInt().coerceIn(0, context.height)
    val bottom = ceil(y1n * context.height).toInt().coerceIn(0, context.height)

    val s = context.specialSlots
    val saved = doubleArrayOf(
        context.vars[s.x], context.vars[s.y], context.vars[s.ix], context.vars[s.iy],
        context.vars[s.r], context.vars[s.g], context.vars[s.b], context.vars[s.a]
    )
    try {
        for (iy in top until bottom) {
            val yNorm = if (context.height <= 1) 0.0 else iy.toDouble() / (context.height - 1).toDouble()
            for (ix in left until right) {
                context.pixelVisits++
                require(context.pixelVisits <= MAX_PIXEL_VISITS_VM) { "Pixel visit budget exceeded" }

                val arrayIndex = iy * context.width + ix
                val current = context.outputPixels[arrayIndex]
                context.vars[s.ix] = ix.toDouble()
                context.vars[s.iy] = iy.toDouble()
                context.vars[s.x] = if (context.width <= 1) 0.0 else ix.toDouble() / (context.width - 1).toDouble()
                context.vars[s.y] = yNorm
                context.vars[s.r] = Color.red(current) / 255.0
                context.vars[s.g] = Color.green(current) / 255.0
                context.vars[s.b] = Color.blue(current) / 255.0
                context.vars[s.a] = Color.alpha(current) / 255.0

                executeBlock(instruction.body, context, pixelActive = true, callDepth = callDepth)

                context.outputPixels[arrayIndex] = vmColor(
                    context.vars[s.r], context.vars[s.g], context.vars[s.b], context.vars[s.a]
                )
            }
        }
    } finally {
        context.vars[s.x] = saved[0]
        context.vars[s.y] = saved[1]
        context.vars[s.ix] = saved[2]
        context.vars[s.iy] = saved[3]
        context.vars[s.r] = saved[4]
        context.vars[s.g] = saved[5]
        context.vars[s.b] = saved[6]
        context.vars[s.a] = saved[7]
    }
}

private fun vmColor(r: Double, g: Double, b: Double, a: Double): Int = Color.argb(
    (a.coerceIn(0.0, 1.0) * 255.0).roundToInt(),
    (r.coerceIn(0.0, 1.0) * 255.0).roundToInt(),
    (g.coerceIn(0.0, 1.0) * 255.0).roundToInt(),
    (b.coerceIn(0.0, 1.0) * 255.0).roundToInt()
)

private data class ExprCode(
    val words: IntArray,
    val constants: DoubleArray,
    val calls: Array<CallSpec>
)

private data class CallSpec(val builtin: Builtin, val argc: Int)

private enum class Builtin {
    SIN, COS, TAN, ASIN, ACOS, ATAN, ATAN2, SQRT, CBRT, ABS, FLOOR, CEIL, ROUND, SIGN,
    MIN, MAX, SUM, AVG, POW, LN, LOG10, EXP, HYPOT, DEG, RAD, CLAMP, SATURATE, LERP,
    INVERSE_LERP, MAP, SMOOTHSTEP, STEP, FRACT, WRAP, DISTANCE, ANGLE,
    EQ, NE, LT, LTE, GT, GTE, AND, OR, NOT, SELECT, NOISE, HASH,
    LANDMARK_COUNT, LANDMARK_X, LANDMARK_Y, LANDMARK_Z, POINT_EXISTS, LANDMARK_DISTANCE,
    LANDMARK_MID_X, LANDMARK_MID_Y, LANDMARK_ANGLE,
    GROUP_MIN_X, GROUP_MAX_X, GROUP_MIN_Y, GROUP_MAX_Y, GROUP_WIDTH, GROUP_HEIGHT,
    GROUP_CENTER_X, GROUP_CENTER_Y,
    SAMPLE_R, SAMPLE_G, SAMPLE_B, SAMPLE_A
}

private object ExprOp {
    const val CONST = 1
    const val SLOT = 2
    const val SPECIAL = 3
    const val ADD = 4
    const val SUB = 5
    const val MUL = 6
    const val DIV = 7
    const val MOD = 8
    const val POW = 9
    const val NEG = 10
    const val CALL = 11
}

private enum class SpecialValue { PI, TAU, E, TIME, FRAME, TRACKED, GROUPS }

private class ExprCompiler(
    private val source: String,
    private val existingSlots: Map<String, Int>,
    private val slotFor: (String) -> Int
) {
    private var pos = 0
    private val words = mutableListOf<Int>()
    private val constants = mutableListOf<Double>()
    private val calls = mutableListOf<CallSpec>()
    private var steps = 0

    fun compile(): ExprCode {
        require(source.length <= 512) { "Expression is too long" }
        parseAddSub()
        skipSpace()
        require(pos == source.length) { "Unsupported expression near '${source.substring(pos)}'" }
        return ExprCode(words.toIntArray(), constants.toDoubleArray(), calls.toTypedArray())
    }

    private fun parseAddSub() {
        parseMulDiv()
        while (true) {
            tick(); skipSpace()
            when {
                take('+') -> { parseMulDiv(); emit(ExprOp.ADD) }
                take('-') -> { parseMulDiv(); emit(ExprOp.SUB) }
                else -> return
            }
        }
    }

    private fun parseMulDiv() {
        parsePower()
        while (true) {
            tick(); skipSpace()
            when {
                take('*') -> { parsePower(); emit(ExprOp.MUL) }
                take('/') -> { parsePower(); emit(ExprOp.DIV) }
                take('%') -> { parsePower(); emit(ExprOp.MOD) }
                else -> return
            }
        }
    }

    private fun parsePower() {
        parseUnary()
        skipSpace()
        if (take('^')) {
            parsePower()
            emit(ExprOp.POW)
        }
    }

    private fun parseUnary() {
        tick(); skipSpace()
        when {
            take('+') -> parseUnary()
            take('-') -> { parseUnary(); emit(ExprOp.NEG) }
            else -> parsePrimary()
        }
    }

    private fun parsePrimary() {
        tick(); skipSpace()
        if (take('(')) {
            parseAddSub()
            skipSpace()
            require(take(')')) { "Missing ')'" }
            return
        }
        if (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) {
            val value = parseNumber()
            val index = constants.size
            constants += value
            emit(ExprOp.CONST, index)
            return
        }

        val name = parseIdentifier()
        require(name.isNotEmpty()) { "Expected number or variable" }
        skipSpace()
        if (take('(')) {
            var argc = 0
            skipSpace()
            if (!take(')')) {
                while (true) {
                    require(argc < 16) { "Too many function arguments" }
                    parseAddSub()
                    argc++
                    skipSpace()
                    if (take(')')) break
                    require(take(',')) { "Expected ',' in $name(...)" }
                }
            }
            val builtin = builtin(name) ?: error("Bytecode does not support function '$name'")
            val callIndex = calls.size
            calls += CallSpec(builtin, argc)
            emit(ExprOp.CALL, callIndex)
            return
        }

        val special = when (name.lowercase()) {
            "pi" -> SpecialValue.PI
            "tau" -> SpecialValue.TAU
            "e" -> SpecialValue.E
            "time" -> SpecialValue.TIME
            "frame" -> SpecialValue.FRAME
            "tracked" -> SpecialValue.TRACKED
            "groups" -> SpecialValue.GROUPS
            else -> null
        }
        if (special != null) emit(ExprOp.SPECIAL, special.ordinal)
        else emit(ExprOp.SLOT, existingSlots[name] ?: slotFor(name))
    }

    private fun builtin(name: String): Builtin? = when (name.lowercase()) {
        "sin" -> Builtin.SIN; "cos" -> Builtin.COS; "tan" -> Builtin.TAN
        "asin" -> Builtin.ASIN; "acos" -> Builtin.ACOS; "atan" -> Builtin.ATAN; "atan2" -> Builtin.ATAN2
        "sqrt" -> Builtin.SQRT; "cbrt" -> Builtin.CBRT; "abs" -> Builtin.ABS
        "floor" -> Builtin.FLOOR; "ceil" -> Builtin.CEIL; "round" -> Builtin.ROUND; "sign" -> Builtin.SIGN
        "min" -> Builtin.MIN; "max" -> Builtin.MAX; "sum" -> Builtin.SUM; "avg", "mean" -> Builtin.AVG
        "pow" -> Builtin.POW; "ln" -> Builtin.LN; "log10" -> Builtin.LOG10; "exp" -> Builtin.EXP
        "hypot" -> Builtin.HYPOT; "deg" -> Builtin.DEG; "rad" -> Builtin.RAD
        "clamp" -> Builtin.CLAMP; "saturate" -> Builtin.SATURATE; "lerp" -> Builtin.LERP
        "inverse_lerp" -> Builtin.INVERSE_LERP; "map" -> Builtin.MAP; "smoothstep" -> Builtin.SMOOTHSTEP
        "step" -> Builtin.STEP; "fract" -> Builtin.FRACT; "wrap" -> Builtin.WRAP
        "distance" -> Builtin.DISTANCE; "angle" -> Builtin.ANGLE
        "eq" -> Builtin.EQ; "ne" -> Builtin.NE; "lt" -> Builtin.LT; "lte" -> Builtin.LTE
        "gt" -> Builtin.GT; "gte" -> Builtin.GTE; "and" -> Builtin.AND; "or" -> Builtin.OR; "not" -> Builtin.NOT
        "select", "ifelse" -> Builtin.SELECT; "noise" -> Builtin.NOISE; "hash" -> Builtin.HASH
        "landmark_count" -> Builtin.LANDMARK_COUNT; "landmark_x" -> Builtin.LANDMARK_X
        "landmark_y" -> Builtin.LANDMARK_Y; "landmark_z" -> Builtin.LANDMARK_Z
        "point_exists" -> Builtin.POINT_EXISTS; "landmark_distance" -> Builtin.LANDMARK_DISTANCE
        "landmark_mid_x" -> Builtin.LANDMARK_MID_X; "landmark_mid_y" -> Builtin.LANDMARK_MID_Y
        "landmark_angle" -> Builtin.LANDMARK_ANGLE
        "group_min_x" -> Builtin.GROUP_MIN_X; "group_max_x" -> Builtin.GROUP_MAX_X
        "group_min_y" -> Builtin.GROUP_MIN_Y; "group_max_y" -> Builtin.GROUP_MAX_Y
        "group_width" -> Builtin.GROUP_WIDTH; "group_height" -> Builtin.GROUP_HEIGHT
        "group_center_x" -> Builtin.GROUP_CENTER_X; "group_center_y" -> Builtin.GROUP_CENTER_Y
        "sample_r" -> Builtin.SAMPLE_R; "sample_g" -> Builtin.SAMPLE_G
        "sample_b" -> Builtin.SAMPLE_B; "sample_a" -> Builtin.SAMPLE_A
        else -> null
    }

    private fun emit(op: Int, operand: Int = 0) {
        require(operand in 0..0x00ffffff) { "Bytecode operand overflow" }
        words += (op shl 24) or operand
    }

    private fun parseNumber(): Double {
        val start = pos
        var exponent = false
        while (pos < source.length) {
            val c = source[pos]
            when {
                c.isDigit() || c == '.' -> pos++
                (c == 'e' || c == 'E') && !exponent -> {
                    exponent = true; pos++
                    if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) pos++
                }
                else -> break
            }
        }
        return source.substring(start, pos).toDouble()
    }

    private fun parseIdentifier(): String {
        skipSpace()
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        return source.substring(start, pos)
    }

    private fun take(c: Char): Boolean {
        if (pos < source.length && source[pos] == c) { pos++; return true }
        return false
    }

    private fun skipSpace() { while (pos < source.length && source[pos].isWhitespace()) pos++ }
    private fun tick() { steps++; require(steps <= 1024) { "Expression is too complex" } }
}

private fun eval(code: ExprCode, context: VmContext): Double {
    val stack = context.stack
    var sp = 0
    code.words.forEach { word ->
        val op = word ushr 24
        val operand = word and 0x00ffffff
        when (op) {
            ExprOp.CONST -> stack[sp++] = code.constants[operand]
            ExprOp.SLOT -> stack[sp++] = context.vars[operand]
            ExprOp.SPECIAL -> stack[sp++] = when (SpecialValue.entries[operand]) {
                SpecialValue.PI -> Math.PI
                SpecialValue.TAU -> Math.PI * 2.0
                SpecialValue.E -> Math.E
                SpecialValue.TIME -> context.elapsedSeconds
                SpecialValue.FRAME -> context.frameIndex.toDouble()
                SpecialValue.TRACKED -> if (context.tracking.groups.isNotEmpty()) 1.0 else 0.0
                SpecialValue.GROUPS -> context.tracking.groups.size.toDouble()
            }
            ExprOp.ADD -> { val b = stack[--sp]; stack[sp - 1] += b }
            ExprOp.SUB -> { val b = stack[--sp]; stack[sp - 1] -= b }
            ExprOp.MUL -> { val b = stack[--sp]; stack[sp - 1] *= b }
            ExprOp.DIV -> {
                val b = stack[--sp]
                stack[sp - 1] = if (abs(b) < 1e-12) 0.0 else stack[sp - 1] / b
            }
            ExprOp.MOD -> {
                val b = stack[--sp]
                stack[sp - 1] = if (abs(b) < 1e-12) 0.0 else stack[sp - 1] % b
            }
            ExprOp.POW -> { val b = stack[--sp]; stack[sp - 1] = stack[sp - 1].pow(b) }
            ExprOp.NEG -> stack[sp - 1] = -stack[sp - 1]
            ExprOp.CALL -> {
                val call = code.calls[operand]
                val base = sp - call.argc
                stack[base] = callBuiltin(call.builtin, stack, base, call.argc, context)
                sp = base + 1
            }
            else -> error("Invalid bytecode opcode $op")
        }
        require(sp in 0..VM_STACK_SIZE) { "Expression stack overflow" }
    }
    val value = if (sp == 0) 0.0 else stack[sp - 1]
    return if (value.isFinite()) value else 0.0
}

private fun callBuiltin(builtin: Builtin, s: DoubleArray, base: Int, argc: Int, c: VmContext): Double {
    fun arg(index: Int): Double = if (index < argc) s[base + index] else 0.0
    fun bool(value: Boolean) = if (value) 1.0 else 0.0
    fun minArg(): Double { var v = Double.POSITIVE_INFINITY; for (i in 0 until argc) v = min(v, arg(i)); return if (v.isInfinite()) 0.0 else v }
    fun maxArg(): Double { var v = Double.NEGATIVE_INFINITY; for (i in 0 until argc) v = max(v, arg(i)); return if (v.isInfinite()) 0.0 else v }
    fun sumArg(): Double { var v = 0.0; for (i in 0 until argc) v += arg(i); return v }

    return when (builtin) {
        Builtin.SIN -> sin(arg(0)); Builtin.COS -> cos(arg(0)); Builtin.TAN -> tan(arg(0))
        Builtin.ASIN -> asin(arg(0).coerceIn(-1.0, 1.0)); Builtin.ACOS -> acos(arg(0).coerceIn(-1.0, 1.0))
        Builtin.ATAN -> atan(arg(0)); Builtin.ATAN2 -> atan2(arg(0), arg(1))
        Builtin.SQRT -> sqrt(max(0.0, arg(0))); Builtin.CBRT -> cbrt(arg(0)); Builtin.ABS -> abs(arg(0))
        Builtin.FLOOR -> floor(arg(0)); Builtin.CEIL -> ceil(arg(0)); Builtin.ROUND -> round(arg(0)); Builtin.SIGN -> sign(arg(0))
        Builtin.MIN -> minArg(); Builtin.MAX -> maxArg(); Builtin.SUM -> sumArg(); Builtin.AVG -> if (argc == 0) 0.0 else sumArg() / argc
        Builtin.POW -> arg(0).pow(arg(1)); Builtin.LN -> ln(max(1e-12, arg(0))); Builtin.LOG10 -> log10(max(1e-12, arg(0)))
        Builtin.EXP -> exp(arg(0).coerceIn(-60.0, 60.0)); Builtin.HYPOT -> hypot(arg(0), arg(1))
        Builtin.DEG -> Math.toDegrees(arg(0)); Builtin.RAD -> Math.toRadians(arg(0))
        Builtin.CLAMP -> arg(0).coerceIn(min(arg(1), arg(2)), max(arg(1), arg(2)))
        Builtin.SATURATE -> arg(0).coerceIn(0.0, 1.0)
        Builtin.LERP -> arg(0) + (arg(1) - arg(0)) * arg(2)
        Builtin.INVERSE_LERP -> { val span = arg(1) - arg(0); if (abs(span) < 1e-12) 0.0 else (arg(2) - arg(0)) / span }
        Builtin.MAP -> { val span = arg(2) - arg(1); val t = if (abs(span) < 1e-12) 0.0 else (arg(0) - arg(1)) / span; arg(3) + (arg(4) - arg(3)) * t }
        Builtin.SMOOTHSTEP -> { val span = arg(1) - arg(0); val t = if (abs(span) < 1e-12) 0.0 else ((arg(2) - arg(0)) / span).coerceIn(0.0, 1.0); t * t * (3.0 - 2.0 * t) }
        Builtin.STEP -> if (arg(1) < arg(0)) 0.0 else 1.0
        Builtin.FRACT -> arg(0) - floor(arg(0))
        Builtin.WRAP -> { val lo = arg(1); val hi = arg(2); val span = hi - lo; if (abs(span) < 1e-12) lo else ((arg(0) - lo) % span + span) % span + lo }
        Builtin.DISTANCE -> hypot(arg(2) - arg(0), arg(3) - arg(1))
        Builtin.ANGLE -> atan2(arg(3) - arg(1), arg(2) - arg(0))
        Builtin.EQ -> bool(abs(arg(0) - arg(1)) < 1e-9); Builtin.NE -> bool(abs(arg(0) - arg(1)) >= 1e-9)
        Builtin.LT -> bool(arg(0) < arg(1)); Builtin.LTE -> bool(arg(0) <= arg(1)); Builtin.GT -> bool(arg(0) > arg(1)); Builtin.GTE -> bool(arg(0) >= arg(1))
        Builtin.AND -> { var v = true; for (i in 0 until argc) v = v && truthy(arg(i)); bool(v) }
        Builtin.OR -> { var v = false; for (i in 0 until argc) v = v || truthy(arg(i)); bool(v) }
        Builtin.NOT -> bool(!truthy(arg(0))); Builtin.SELECT -> if (truthy(arg(0))) arg(1) else arg(2)
        Builtin.NOISE -> { val x = arg(0) * 12.9898 + c.frameIndex * 0.071; val n = sin(x) * 43758.5453; n - floor(n) }
        Builtin.HASH -> { val x = arg(0) * 12.9898 + arg(1) * 78.233 + arg(2) * 37.719; val n = sin(x) * 43758.5453; n - floor(n) }
        Builtin.LANDMARK_COUNT -> c.group(arg(0))?.size?.toDouble() ?: 0.0
        Builtin.LANDMARK_X -> c.point(arg(0), arg(1))?.x?.toDouble() ?: 0.0
        Builtin.LANDMARK_Y -> c.point(arg(0), arg(1))?.y?.toDouble() ?: 0.0
        Builtin.LANDMARK_Z -> c.point(arg(0), arg(1))?.z?.toDouble() ?: 0.0
        Builtin.POINT_EXISTS -> bool(c.point(arg(0), arg(1)) != null)
        Builtin.LANDMARK_DISTANCE -> { val p1 = c.point(arg(0), arg(1)); val p2 = c.point(arg(0), arg(2)); if (p1 == null || p2 == null) 0.0 else hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble()) }
        Builtin.LANDMARK_MID_X -> { val p1 = c.point(arg(0), arg(1)); val p2 = c.point(arg(0), arg(2)); if (p1 == null || p2 == null) 0.0 else (p1.x + p2.x) / 2.0 }
        Builtin.LANDMARK_MID_Y -> { val p1 = c.point(arg(0), arg(1)); val p2 = c.point(arg(0), arg(2)); if (p1 == null || p2 == null) 0.0 else (p1.y + p2.y) / 2.0 }
        Builtin.LANDMARK_ANGLE -> landmarkAngle(c, arg(0), arg(1), arg(2), arg(3))
        Builtin.GROUP_MIN_X -> c.bounds(arg(0))?.minX ?: 0.0; Builtin.GROUP_MAX_X -> c.bounds(arg(0))?.maxX ?: 0.0
        Builtin.GROUP_MIN_Y -> c.bounds(arg(0))?.minY ?: 0.0; Builtin.GROUP_MAX_Y -> c.bounds(arg(0))?.maxY ?: 0.0
        Builtin.GROUP_WIDTH -> c.bounds(arg(0))?.let { it.maxX - it.minX } ?: 0.0
        Builtin.GROUP_HEIGHT -> c.bounds(arg(0))?.let { it.maxY - it.minY } ?: 0.0
        Builtin.GROUP_CENTER_X -> c.bounds(arg(0))?.let { (it.minX + it.maxX) * 0.5 } ?: 0.0
        Builtin.GROUP_CENTER_Y -> c.bounds(arg(0))?.let { (it.minY + it.maxY) * 0.5 } ?: 0.0
        Builtin.SAMPLE_R -> Color.red(c.sample(arg(0), arg(1))) / 255.0
        Builtin.SAMPLE_G -> Color.green(c.sample(arg(0), arg(1))) / 255.0
        Builtin.SAMPLE_B -> Color.blue(c.sample(arg(0), arg(1))) / 255.0
        Builtin.SAMPLE_A -> Color.alpha(c.sample(arg(0), arg(1))) / 255.0
    }
}

private fun landmarkAngle(c: VmContext, group: Double, a: Double, b: Double, d: Double): Double {
    val p1 = c.point(group, a) ?: return 0.0
    val p2 = c.point(group, b) ?: return 0.0
    val p3 = c.point(group, d) ?: return 0.0
    val v1x = (p1.x - p2.x).toDouble()
    val v1y = (p1.y - p2.y).toDouble()
    val v2x = (p3.x - p2.x).toDouble()
    val v2y = (p3.y - p2.y).toDouble()
    return atan2(v1x * v2y - v1y * v2x, v1x * v2x + v1y * v2y)
}

private fun truthy(value: Double): Boolean = abs(value) > 1e-12

private const val VM_STACK_SIZE = 128
private const val MAX_CALL_DEPTH = 8
private const val MAX_CALL_ARGS = 16
private const val MAX_REPEAT_VM = 10_000
private const val MAX_PIXEL_VISITS_VM = 2_000_000
