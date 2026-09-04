package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Small capability-based filter language.
 *
 * The engine deliberately contains no named visual effects. Scripts get only the essentials:
 * variables, functions, conditions, bounded loops, pixel iteration/writes, source sampling,
 * user inputs, math and MediaPipe tracking values. Effects such as invert, grayscale, bulge,
 * blur, outlines and eye enlargement are written in the language itself.
 */
class ScriptEngine {
    data class Program(
        val inputs: List<ScriptInput>,
        val statements: List<Statement>,
        val functions: Map<String, UserFunction>,
        /** Pixel loops replace the whole analyzed frame; non-pixel scripts can stay overlays. */
        val usesPixels: Boolean
    )

    data class UserFunction(
        val name: String,
        val parameters: List<String>,
        val body: List<Statement>
    )

    sealed interface Statement
    data class Let(val name: String, val expression: String) : Statement
    data class SetChannel(val channel: String, val expression: String) : Statement
    data class WritePixel(
        val x: String,
        val y: String,
        val r: String,
        val g: String,
        val b: String,
        val a: String
    ) : Statement
    data class Pixels(
        val x: String?,
        val y: String?,
        val width: String?,
        val height: String?,
        val body: List<Statement>
    ) : Statement
    data class Repeat(val countExpression: String, val body: List<Statement>) : Statement
    data class If(val expression: String, val yes: List<Statement>, val no: List<Statement>) : Statement
    data class Call(val name: String, val arguments: List<String>) : Statement

    private var frameCounter = 0L
    private val startedNanos = System.nanoTime()

    fun parse(source: String): Program {
        require(source.length <= MAX_SCRIPT_CHARS) { "Script is too large (max $MAX_SCRIPT_CHARS characters)" }

        val lines = source.lines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val inputs = mutableListOf<ScriptInput>()
        val functions = linkedMapOf<String, UserFunction>()
        val topLevelLines = mutableListOf<String>()

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.startsWith("input ") -> inputs += parseInput(line)
                line.startsWith("fn ") -> {
                    require(functions.size < MAX_FUNCTIONS) { "Too many functions (max $MAX_FUNCTIONS)" }
                    val parts = line.split(Regex("\\s+"))
                    require(parts.size >= 2) { "fn syntax: fn NAME [PARAM ...]" }
                    val name = identifier(parts[1])
                    val parameters = parts.drop(2).map(::identifier)
                    require(parameters.distinct().size == parameters.size) { "Duplicate parameter in $name" }
                    require(parameters.size <= MAX_FUNCTION_PARAMETERS) { "Too many parameters in $name" }
                    require(name !in functions) { "Function '$name' is already defined" }

                    val parsed = parseBlock(lines, index + 1, setOf("end"))
                    require(parsed.second < lines.size && lines[parsed.second] == "end") { "fn $name missing end" }
                    functions[name] = UserFunction(name, parameters, parsed.first)
                    index = parsed.second
                }
                else -> topLevelLines += line
            }
            index++
        }

        val parsedTop = parseBlock(topLevelLines, 0, emptySet())
        require(parsedTop.second == topLevelLines.size) { "Unexpected block terminator" }

        val statementCount = countStatements(parsedTop.first) + functions.values.sumOf { countStatements(it.body) }
        require(statementCount <= MAX_STATEMENTS) { "Too many statements (max $MAX_STATEMENTS)" }

        val usesPixels = containsPixels(parsedTop.first) || functions.values.any { containsPixels(it.body) }
        return Program(inputs, parsedTop.first, functions.toMap(), usesPixels)
    }

    private fun parseInput(line: String): ScriptInput {
        val parts = line.split(Regex("\\s+"))
        require(parts.size >= 5) { "input syntax: input number|text NAME LABEL DEFAULT [MIN MAX]" }
        val type = when (parts[1]) {
            "number" -> InputType.NUMBER
            "text" -> InputType.TEXT
            else -> error("input type must be number or text")
        }
        return ScriptInput(
            name = identifier(parts[2]),
            label = parts[3].replace('_', ' '),
            type = type,
            defaultValue = parts[4].replace('_', ' '),
            min = parts.getOrNull(5)?.toDoubleOrNull(),
            max = parts.getOrNull(6)?.toDoubleOrNull()
        )
    }

    private fun parseBlock(
        lines: List<String>,
        start: Int,
        stops: Set<String>
    ): Pair<List<Statement>, Int> {
        val out = mutableListOf<Statement>()
        var index = start

        while (index < lines.size) {
            val line = lines[index]
            if (line in stops) break
            val parts = line.split(Regex("\\s+"))

            when (parts[0]) {
                "let" -> {
                    require(parts.size >= 3) { "let syntax: let NAME = EXPRESSION" }
                    val name = identifier(parts[1])
                    // Parse relative to the `let` payload, not substringAfter(name): a short name
                    // such as `t` also occurs in the word `let` and used to produce `t = ...` as
                    // the expression, which then failed only at runtime.
                    val declaration = line.removePrefix("let").trimStart()
                    require(declaration.startsWith(name)) { "let syntax: let NAME = EXPRESSION" }
                    val expression = declaration.drop(name.length).trimStart().removePrefix("=").trimStart()
                    require(expression.isNotEmpty()) { "let requires an expression" }
                    out += Let(name, expression)
                }

                "set" -> {
                    require(parts.size >= 3) { "set syntax inside pixels: set r|g|b|a EXPRESSION" }
                    val channel = parts[1].lowercase()
                    require(channel in CHANNELS) { "set can only write r, g, b or a" }
                    out += SetChannel(channel, line.substringAfter(parts[1]).trim())
                }

                "write_pixel" -> {
                    require(parts.size == 7) { "write_pixel syntax: write_pixel X Y R G B A" }
                    out += WritePixel(parts[1], parts[2], parts[3], parts[4], parts[5], parts[6])
                }

                "pixels" -> {
                    require(parts.size == 1 || parts.size == 5) {
                        "pixels syntax: pixels  OR  pixels X Y WIDTH HEIGHT"
                    }
                    val parsed = parseBlock(lines, index + 1, setOf("end"))
                    require(parsed.second < lines.size && lines[parsed.second] == "end") { "pixels missing end" }
                    out += if (parts.size == 1) {
                        Pixels(null, null, null, null, parsed.first)
                    } else {
                        Pixels(parts[1], parts[2], parts[3], parts[4], parsed.first)
                    }
                    index = parsed.second
                }

                "repeat" -> {
                    val count = line.removePrefix("repeat").trim()
                    require(count.isNotEmpty()) { "repeat requires a count expression" }
                    val parsed = parseBlock(lines, index + 1, setOf("end"))
                    require(parsed.second < lines.size && lines[parsed.second] == "end") { "repeat missing end" }
                    out += Repeat(count, parsed.first)
                    index = parsed.second
                }

                "if" -> {
                    val expression = line.removePrefix("if").trim()
                    require(expression.isNotEmpty()) { "if requires an expression" }
                    val yesParsed = parseBlock(lines, index + 1, setOf("else", "end"))
                    var no = emptyList<Statement>()
                    var end = yesParsed.second
                    if (end < lines.size && lines[end] == "else") {
                        val noParsed = parseBlock(lines, end + 1, setOf("end"))
                        no = noParsed.first
                        end = noParsed.second
                    }
                    require(end < lines.size && lines[end] == "end") { "if missing end" }
                    out += If(expression, yesParsed.first, no)
                    index = end
                }

                "call" -> {
                    require(parts.size >= 2) { "call syntax: call FUNCTION [ARG ...]" }
                    out += Call(identifier(parts[1]), parts.drop(2))
                }

                "input", "fn" -> error("${parts[0]} is only allowed at top level")
                "else", "end" -> error("Unexpected '${parts[0]}'")
                else -> {
                    if (parts[0] in REMOVED_EFFECT_COMMANDS) {
                        error("'${parts[0]}' was removed. Build the effect from pixels, set, sample_* and functions.")
                    }
                    error("Unknown command: ${parts[0]}")
                }
            }
            index++
        }

        return out to index
    }

    fun render(
        source: Bitmap,
        frame: TrackingFrame,
        program: Program,
        values: Map<String, String>
    ): Bitmap = renderInternal(source, frame, program, values, overlay = false)

    fun renderOverlay(
        source: Bitmap,
        frame: TrackingFrame,
        program: Program,
        values: Map<String, String>
    ): Bitmap = renderInternal(source, frame, program, values, overlay = true)

    private fun renderInternal(
        source: Bitmap,
        frame: TrackingFrame,
        program: Program,
        values: Map<String, String>,
        overlay: Boolean
    ): Bitmap {
        val width = source.width
        val height = source.height
        val sourcePixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        val outputPixels = if (overlay) IntArray(sourcePixels.size) else sourcePixels.copyOf()

        val vars = program.inputs.associate { input ->
            input.name to (values[input.name] ?: input.defaultValue)
        }.toMutableMap()
        vars["image_width"] = width.toString()
        vars["image_height"] = height.toString()
        vars["aspect"] = (width.toDouble() / max(1, height)).toString()

        val sampler = SourceSampler(sourcePixels, width, height)
        val state = ExecutionState(
            program = program,
            frame = frame,
            frameIndex = frameCounter++,
            elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000.0,
            width = width,
            height = height,
            sourcePixels = sourcePixels,
            outputPixels = outputPixels,
            sampler = sampler,
            budget = ExecutionBudget()
        )

        execute(program.statements, vars, state, pixelActive = false, callDepth = 0)

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { result ->
            result.setPixels(outputPixels, 0, width, 0, 0, width, height)
        }
    }

    private fun execute(
        statements: List<Statement>,
        vars: MutableMap<String, String>,
        state: ExecutionState,
        pixelActive: Boolean,
        callDepth: Int
    ) {
        statements.forEach { statement ->
            state.budget.operation()
            fun number(expression: String): Double = eval(expression, vars, state)

            when (statement) {
                is Let -> vars[statement.name] = number(statement.expression).toString()

                is SetChannel -> {
                    require(pixelActive) { "set r/g/b/a is only valid inside pixels" }
                    vars[statement.channel] = number(statement.expression).coerceIn(0.0, 1.0).toString()
                }

                is WritePixel -> {
                    val x = number(statement.x).coerceIn(0.0, 1.0)
                    val y = number(statement.y).coerceIn(0.0, 1.0)
                    val ix = (x * (state.width - 1)).roundToInt().coerceIn(0, state.width - 1)
                    val iy = (y * (state.height - 1)).roundToInt().coerceIn(0, state.height - 1)
                    state.outputPixels[iy * state.width + ix] = color(
                        number(statement.r), number(statement.g), number(statement.b), number(statement.a)
                    )
                }

                is Pixels -> {
                    require(!pixelActive) { "pixels blocks cannot be nested" }
                    executePixels(statement, vars, state, callDepth)
                }

                is Repeat -> {
                    val count = number(statement.countExpression).toInt().coerceIn(0, MAX_REPEAT)
                    val oldLoop = vars["loop"]
                    repeat(count) { loopIndex ->
                        vars["loop"] = loopIndex.toString()
                        execute(statement.body, vars, state, pixelActive, callDepth)
                    }
                    restore(vars, "loop", oldLoop)
                }

                is If -> execute(
                    if (condition(statement.expression, vars, state)) statement.yes else statement.no,
                    vars,
                    state,
                    pixelActive,
                    callDepth
                )

                is Call -> {
                    require(callDepth < MAX_CALL_DEPTH) { "Function call depth exceeded" }
                    val function = state.program.functions[statement.name]
                        ?: error("Unknown function: ${statement.name}")
                    require(statement.arguments.size == function.parameters.size) {
                        "${function.name} expects ${function.parameters.size} arguments, got ${statement.arguments.size}"
                    }
                    val arguments = statement.arguments.map(::number)
                    val oldValues = function.parameters.associateWith { vars[it] }
                    function.parameters.forEachIndexed { parameterIndex, parameter ->
                        vars[parameter] = arguments[parameterIndex].toString()
                    }
                    execute(function.body, vars, state, pixelActive, callDepth + 1)
                    function.parameters.forEach { restore(vars, it, oldValues[it]) }
                }
            }
        }
    }

    private fun executePixels(
        statement: Pixels,
        vars: MutableMap<String, String>,
        state: ExecutionState,
        callDepth: Int
    ) {
        fun number(expression: String): Double = eval(expression, vars, state)

        val nx = statement.x?.let(::number) ?: 0.0
        val ny = statement.y?.let(::number) ?: 0.0
        val nw = statement.width?.let(::number) ?: 1.0
        val nh = statement.height?.let(::number) ?: 1.0

        val x0n = min(nx, nx + nw).coerceIn(0.0, 1.0)
        val x1n = max(nx, nx + nw).coerceIn(0.0, 1.0)
        val y0n = min(ny, ny + nh).coerceIn(0.0, 1.0)
        val y1n = max(ny, ny + nh).coerceIn(0.0, 1.0)

        val left = floor(x0n * state.width).toInt().coerceIn(0, state.width)
        val right = ceil(x1n * state.width).toInt().coerceIn(0, state.width)
        val top = floor(y0n * state.height).toInt().coerceIn(0, state.height)
        val bottom = ceil(y1n * state.height).toInt().coerceIn(0, state.height)

        val specialNames = listOf("x", "y", "ix", "iy", "r", "g", "b", "a")
        val oldValues = specialNames.associateWith { vars[it] }

        try {
            for (iy in top until bottom) {
                for (ix in left until right) {
                    state.budget.pixel()
                    val arrayIndex = iy * state.width + ix
                    val current = state.outputPixels[arrayIndex]

                    vars["ix"] = ix.toString()
                    vars["iy"] = iy.toString()
                    vars["x"] = if (state.width <= 1) "0" else (ix.toDouble() / (state.width - 1)).toString()
                    vars["y"] = if (state.height <= 1) "0" else (iy.toDouble() / (state.height - 1)).toString()
                    vars["r"] = (Color.red(current) / 255.0).toString()
                    vars["g"] = (Color.green(current) / 255.0).toString()
                    vars["b"] = (Color.blue(current) / 255.0).toString()
                    vars["a"] = (Color.alpha(current) / 255.0).toString()

                    execute(statement.body, vars, state, pixelActive = true, callDepth = callDepth)

                    state.outputPixels[arrayIndex] = color(
                        vars["r"]?.toDoubleOrNull() ?: 0.0,
                        vars["g"]?.toDoubleOrNull() ?: 0.0,
                        vars["b"]?.toDoubleOrNull() ?: 0.0,
                        vars["a"]?.toDoubleOrNull() ?: 1.0
                    )
                }
            }
        } finally {
            specialNames.forEach { restore(vars, it, oldValues[it]) }
        }
    }

    private fun eval(
        expression: String,
        vars: Map<String, String>,
        state: ExecutionState
    ): Double = ExpressionEvaluator(
        source = expression,
        vars = vars,
        tracking = state.frame,
        frameIndex = state.frameIndex,
        elapsedSeconds = state.elapsed,
        samplePixel = state.sampler::sample
    ).eval()

    private fun condition(
        expression: String,
        vars: Map<String, String>,
        state: ExecutionState
    ): Boolean {
        val trimmed = expression.trim()
        if (trimmed == "tracked") return state.frame.groups.isNotEmpty()
        val comparison = Regex("^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").matchEntire(trimmed)
        if (comparison != null) {
            val (left, op, right) = comparison.destructured
            val leftNumber = numberOrNull(left, vars, state)
            val rightNumber = numberOrNull(right, vars, state)
            if (leftNumber != null && rightNumber != null) {
                return when (op) {
                    "==" -> abs(leftNumber - rightNumber) < 1e-9
                    "!=" -> abs(leftNumber - rightNumber) >= 1e-9
                    ">" -> leftNumber > rightNumber
                    "<" -> leftNumber < rightNumber
                    ">=" -> leftNumber >= rightNumber
                    "<=" -> leftNumber <= rightNumber
                    else -> false
                }
            }
            val leftText = resolveText(left, vars)
            val rightText = resolveText(right, vars)
            return when (op) {
                "==" -> leftText == rightText
                "!=" -> leftText != rightText
                else -> false
            }
        }
        return runCatching { eval(trimmed, vars, state) != 0.0 }.getOrDefault(false)
    }

    private fun numberOrNull(
        expression: String,
        vars: Map<String, String>,
        state: ExecutionState
    ): Double? {
        val trimmed = expression.trim()
        if (
            Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(trimmed) &&
            vars[trimmed]?.toDoubleOrNull() == null &&
            trimmed !in BUILTIN_NUMERIC_NAMES
        ) return null
        return runCatching { eval(trimmed, vars, state) }.getOrNull()
    }

    private fun resolveText(token: String, vars: Map<String, String>): String =
        vars[token.trim()] ?: token.trim().removeSurrounding("\"").replace('_', ' ')

    private fun color(r: Double, g: Double, b: Double, a: Double): Int = Color.argb(
        (a.coerceIn(0.0, 1.0) * 255.0).roundToInt(),
        (r.coerceIn(0.0, 1.0) * 255.0).roundToInt(),
        (g.coerceIn(0.0, 1.0) * 255.0).roundToInt(),
        (b.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    )

    private fun restore(vars: MutableMap<String, String>, name: String, oldValue: String?) {
        if (oldValue == null) vars.remove(name) else vars[name] = oldValue
    }

    private fun identifier(text: String): String {
        require(Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$").matches(text)) { "Invalid identifier: $text" }
        return text
    }

    private fun containsPixels(statements: List<Statement>): Boolean = statements.any { statement ->
        when (statement) {
            is Pixels -> true
            is Repeat -> containsPixels(statement.body)
            is If -> containsPixels(statement.yes) || containsPixels(statement.no)
            else -> false
        }
    }

    private fun countStatements(statements: List<Statement>): Int = statements.sumOf { statement ->
        1 + when (statement) {
            is Pixels -> countStatements(statement.body)
            is Repeat -> countStatements(statement.body)
            is If -> countStatements(statement.yes) + countStatements(statement.no)
            else -> 0
        }
    }

    private data class ExecutionState(
        val program: Program,
        val frame: TrackingFrame,
        val frameIndex: Long,
        val elapsed: Double,
        val width: Int,
        val height: Int,
        val sourcePixels: IntArray,
        val outputPixels: IntArray,
        val sampler: SourceSampler,
        val budget: ExecutionBudget
    )

    private class ExecutionBudget {
        private var operations = 0
        private var pixelVisits = 0

        fun operation() {
            operations++
            require(operations <= MAX_FRAME_OPERATIONS) { "Script exceeded the per-frame operation budget" }
        }

        fun pixel() {
            pixelVisits++
            require(pixelVisits <= MAX_PIXEL_VISITS) { "Script exceeded the per-frame pixel budget" }
            operation()
        }
    }

    /** Immutable camera-frame sampler with bilinear interpolation and a one-sample cache. */
    private class SourceSampler(
        private val pixels: IntArray,
        private val width: Int,
        private val height: Int
    ) {
        private var lastX = Double.NaN
        private var lastY = Double.NaN
        private var lastColor = Color.TRANSPARENT

        fun sample(normalizedX: Double, normalizedY: Double): Int {
            val nx = normalizedX.coerceIn(0.0, 1.0)
            val ny = normalizedY.coerceIn(0.0, 1.0)
            if (nx == lastX && ny == lastY) return lastColor

            val px = nx * (width - 1)
            val py = ny * (height - 1)
            val x0 = floor(px).toInt().coerceIn(0, width - 1)
            val y0 = floor(py).toInt().coerceIn(0, height - 1)
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val tx = px - x0
            val ty = py - y0

            val top = mix(pixels[y0 * width + x0], pixels[y0 * width + x1], tx)
            val bottom = mix(pixels[y1 * width + x0], pixels[y1 * width + x1], tx)
            val result = mix(top, bottom, ty)
            lastX = nx
            lastY = ny
            lastColor = result
            return result
        }

        private fun mix(a: Int, b: Int, t: Double): Int {
            fun channel(av: Int, bv: Int): Int = (av + (bv - av) * t).roundToInt().coerceIn(0, 255)
            return Color.argb(
                channel(Color.alpha(a), Color.alpha(b)),
                channel(Color.red(a), Color.red(b)),
                channel(Color.green(a), Color.green(b)),
                channel(Color.blue(a), Color.blue(b))
            )
        }
    }

    companion object {
        private val CHANNELS = setOf("r", "g", "b", "a")
        private val BUILTIN_NUMERIC_NAMES = setOf(
            "pi", "tau", "e", "time", "frame", "tracked", "groups",
            "image_width", "image_height", "aspect", "x", "y", "ix", "iy", "r", "g", "b", "a", "loop"
        )
        private val REMOVED_EFFECT_COMMANDS = setOf(
            "skeleton", "connections", "dots", "magnify", "bulge", "pixelate", "tint",
            "circle", "line", "rect", "text", "invert", "grayscale", "sepia", "blur", "sharpen"
        )

        private const val MAX_SCRIPT_CHARS = 65_536
        private const val MAX_FUNCTIONS = 64
        private const val MAX_FUNCTION_PARAMETERS = 16
        private const val MAX_STATEMENTS = 2_048
        private const val MAX_REPEAT = 1_000
        private const val MAX_CALL_DEPTH = 16
        private const val MAX_FRAME_OPERATIONS = 5_000_000
        private const val MAX_PIXEL_VISITS = 1_000_000
    }
}
