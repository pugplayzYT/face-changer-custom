package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Host-controlled filter language. It exposes only approved drawing/pixel operations and
 * MediaPipe landmark values: no files, sockets, reflection, Android APIs, processes or shell.
 */
class ScriptEngine {
    data class Program(val inputs: List<ScriptInput>, val statements: List<Statement>)
    sealed interface Statement
    data class Let(val name: String, val expression: String) : Statement
    data class Skeleton(val color: Int, val widthExpr: String) : Statement
    data class Connections(val color: Int, val widthExpr: String) : Statement
    data class Dots(val color: Int, val radiusExpr: String) : Statement
    data class Magnify(val groupExpr: String, val pointExpr: String, val scaleExpr: String, val radiusExpr: String) : Statement
    data class Bulge(val xExpr: String, val yExpr: String, val scaleExpr: String, val radiusExpr: String) : Statement
    data class Pixelate(val sizeExpr: String) : Statement
    data class Tint(val color: Int, val amountExpr: String) : Statement
    data class Circle(val xExpr: String, val yExpr: String, val radiusExpr: String, val color: Int, val fill: Boolean) : Statement
    data class Line(val x1: String, val y1: String, val x2: String, val y2: String, val color: Int, val widthExpr: String) : Statement
    data class RectDraw(val x: String, val y: String, val w: String, val h: String, val color: Int, val fill: Boolean) : Statement
    data class TextDraw(val value: String, val x: String, val y: String, val size: String, val color: Int) : Statement
    data class Repeat(val countExpr: String, val body: List<Statement>) : Statement
    data class If(val expression: String, val yes: List<Statement>, val no: List<Statement>) : Statement

    private val counter = AtomicLong(0)
    private val startedNanos = System.nanoTime()

    fun parse(source: String): Program {
        val lines = source.lines().map { it.substringBefore("# ").trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val inputs = mutableListOf<ScriptInput>()
        val bodyLines = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("input ")) inputs += parseInput(line) else bodyLines += line
        }
        return Program(inputs, parseBlock(bodyLines, 0, emptySet()).first)
    }

    private fun parseInput(line: String): ScriptInput {
        val p = line.split(Regex("\\s+"))
        require(p.size >= 5) { "input syntax: input number|text name label default [min max]" }
        val type = when (p[1]) {
            "number" -> InputType.NUMBER
            "text" -> InputType.TEXT
            else -> error("input type must be number or text")
        }
        return ScriptInput(
            name = p[2],
            label = p[3].replace('_', ' '),
            type = type,
            defaultValue = p[4].replace('_', ' '),
            min = p.getOrNull(5)?.toDoubleOrNull(),
            max = p.getOrNull(6)?.toDoubleOrNull()
        )
    }

    private fun parseBlock(lines: List<String>, start: Int, stops: Set<String>): Pair<List<Statement>, Int> {
        val out = mutableListOf<Statement>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line in stops) break
            val p = line.split(Regex("\\s+"))
            when (p[0]) {
                "let" -> {
                    require(p.size >= 3) { "let syntax: let name = expression" }
                    val name = p[1]
                    val expr = line.substringAfter(name).trim().removePrefix("=").trim()
                    require(expr.isNotEmpty()) { "let requires an expression" }
                    out += Let(name, expr)
                }
                "skeleton" -> out += Skeleton(parseColor(p[1]), p.getOrElse(2) { "3" })
                "connections" -> out += Connections(parseColor(p[1]), p.getOrElse(2) { "3" })
                "dots" -> out += Dots(parseColor(p[1]), p.getOrElse(2) { "5" })
                "magnify" -> {
                    require(p.size >= 5) { "magnify syntax: magnify GROUP POINT SCALE RADIUS" }
                    out += Magnify(p[1], p[2], p[3], p[4])
                }
                "bulge" -> {
                    require(p.size >= 5) { "bulge syntax: bulge X Y SCALE RADIUS" }
                    out += Bulge(p[1], p[2], p[3], p[4])
                }
                "pixelate" -> out += Pixelate(p[1])
                "tint" -> out += Tint(parseColor(p[1]), p[2])
                "circle" -> {
                    require(p.size >= 5)
                    out += Circle(p[1], p[2], p[3], parseColor(p[4]), p.getOrNull(5) != "stroke")
                }
                "line" -> {
                    require(p.size >= 6)
                    out += Line(p[1], p[2], p[3], p[4], parseColor(p[5]), p.getOrElse(6) { "3" })
                }
                "rect" -> {
                    require(p.size >= 6)
                    out += RectDraw(p[1], p[2], p[3], p[4], parseColor(p[5]), p.getOrNull(6) != "stroke")
                }
                "text" -> {
                    require(p.size >= 6)
                    out += TextDraw(p[1], p[2], p[3], p[4], parseColor(p[5]))
                }
                "repeat" -> {
                    val (child, end) = parseBlock(lines, i + 1, setOf("end"))
                    require(end < lines.size && lines[end] == "end") { "repeat missing end" }
                    out += Repeat(line.removePrefix("repeat ").trim(), child)
                    i = end
                }
                "if" -> {
                    val (yes, split) = parseBlock(lines, i + 1, setOf("else", "end"))
                    var no = emptyList<Statement>()
                    var end = split
                    if (split < lines.size && lines[split] == "else") {
                        val parsed = parseBlock(lines, split + 1, setOf("end"))
                        no = parsed.first
                        end = parsed.second
                    }
                    require(end < lines.size && lines[end] == "end") { "if missing end" }
                    out += If(line.removePrefix("if "), yes, no)
                    i = end
                }
                else -> error("Unknown command: ${p[0]}")
            }
            i++
        }
        return out to i
    }

    fun render(source: Bitmap, frame: TrackingFrame, program: Program, values: Map<String, String>): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        runProgram(source, output, frame, program, values)
        return output
    }

    /**
     * Render only local/drawing effects into a transparent bitmap. CameraPipeline uses this for
     * bulge/magnify/drawing filters so the native CameraX preview stays visible and smooth.
     */
    fun renderOverlay(source: Bitmap, frame: TrackingFrame, program: Program, values: Map<String, String>): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        output.eraseColor(Color.TRANSPARENT)
        runProgram(source, output, frame, program, values)
        return output
    }

    private fun runProgram(source: Bitmap, output: Bitmap, frame: TrackingFrame, program: Program, values: Map<String, String>) {
        val vars = program.inputs.associate { it.name to (values[it.name] ?: it.defaultValue) }.toMutableMap()
        val frameIndex = counter.getAndIncrement()
        val elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000.0
        execute(source, output, frame, program.statements, vars, frameIndex, elapsed)
    }

    private fun execute(
        source: Bitmap,
        output: Bitmap,
        frame: TrackingFrame,
        statements: List<Statement>,
        vars: MutableMap<String, String>,
        frameIndex: Long,
        elapsed: Double
    ) {
        val canvas = Canvas(output)
        fun n(expr: String) = ExpressionEvaluator(expr, vars, frame, frameIndex, elapsed).eval()

        statements.forEach { s ->
            when (s) {
                is Let -> vars[s.name] = n(s.expression).toString()
                is Skeleton -> drawSkeleton(canvas, output, frame, s.color, n(s.widthExpr).toFloat())
                is Connections -> drawConnections(canvas, output, frame, s.color, n(s.widthExpr).toFloat())
                is Dots -> drawDots(canvas, output, frame, s.color, n(s.radiusExpr).toFloat())
                is Magnify -> magnify(
                    source,
                    output,
                    frame,
                    n(s.groupExpr).toInt(),
                    n(s.pointExpr).toInt(),
                    n(s.scaleExpr),
                    n(s.radiusExpr)
                )
                is Bulge -> bulge(
                    source,
                    output,
                    n(s.xExpr),
                    n(s.yExpr),
                    n(s.scaleExpr),
                    n(s.radiusExpr)
                )
                is Pixelate -> pixelate(output, max(2, n(s.sizeExpr).toInt()))
                is Tint -> {
                    val paint = Paint().apply {
                        color = s.color
                        alpha = (255 * n(s.amountExpr).coerceIn(0.0, 1.0)).toInt()
                    }
                    canvas.drawRect(0f, 0f, output.width.toFloat(), output.height.toFloat(), paint)
                }
                is Circle -> {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = s.color
                        style = if (s.fill) Paint.Style.FILL else Paint.Style.STROKE
                        strokeWidth = 3f
                    }
                    canvas.drawCircle(
                        (n(s.xExpr) * output.width).toFloat(),
                        (n(s.yExpr) * output.height).toFloat(),
                        (n(s.radiusExpr) * min(output.width, output.height)).toFloat(),
                        paint
                    )
                }
                is Line -> {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = s.color
                        style = Paint.Style.STROKE
                        strokeWidth = n(s.widthExpr).toFloat().coerceAtLeast(0.5f)
                    }
                    canvas.drawLine(
                        (n(s.x1) * output.width).toFloat(),
                        (n(s.y1) * output.height).toFloat(),
                        (n(s.x2) * output.width).toFloat(),
                        (n(s.y2) * output.height).toFloat(),
                        paint
                    )
                }
                is RectDraw -> {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = s.color
                        style = if (s.fill) Paint.Style.FILL else Paint.Style.STROKE
                        strokeWidth = 3f
                    }
                    val x = (n(s.x) * output.width).toFloat()
                    val y = (n(s.y) * output.height).toFloat()
                    val w = (n(s.w) * output.width).toFloat()
                    val h = (n(s.h) * output.height).toFloat()
                    canvas.drawRect(x, y, x + w, y + h, paint)
                }
                is TextDraw -> {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = s.color
                        textSize = n(s.size).toFloat().coerceAtLeast(6f)
                    }
                    canvas.drawText(
                        resolveText(s.value, vars),
                        (n(s.x) * output.width).toFloat(),
                        (n(s.y) * output.height).toFloat(),
                        paint
                    )
                }
                is Repeat -> {
                    val count = n(s.countExpr).toInt().coerceIn(0, 1000)
                    val old = vars["loop"]
                    repeat(count) { index ->
                        vars["loop"] = index.toString()
                        execute(source, output, frame, s.body, vars, frameIndex, elapsed)
                    }
                    if (old == null) vars.remove("loop") else vars["loop"] = old
                }
                is If -> execute(
                    source,
                    output,
                    frame,
                    if (condition(s.expression, vars, frame, frameIndex, elapsed)) s.yes else s.no,
                    vars,
                    frameIndex,
                    elapsed
                )
            }
        }
    }

    private fun drawDots(canvas: Canvas, bitmap: Bitmap, frame: TrackingFrame, color: Int, radius: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        frame.groups.flatten().forEach {
            canvas.drawCircle(it.x * bitmap.width, it.y * bitmap.height, radius.coerceAtLeast(0.5f), p)
        }
    }

    private fun drawSkeleton(canvas: Canvas, bitmap: Bitmap, frame: TrackingFrame, color: Int, width: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = width.coerceAtLeast(0.5f)
            style = Paint.Style.STROKE
        }
        frame.groups.forEach { group ->
            group.zipWithNext().forEach { (a, b) ->
                canvas.drawLine(a.x * bitmap.width, a.y * bitmap.height, b.x * bitmap.width, b.y * bitmap.height, p)
            }
        }
    }

    private fun drawConnections(canvas: Canvas, bitmap: Bitmap, frame: TrackingFrame, color: Int, width: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = width.coerceAtLeast(0.5f)
            style = Paint.Style.STROKE
        }
        val edges = when (frame.mode) {
            TrackingMode.HAND -> HAND_EDGES
            TrackingMode.BODY -> BODY_EDGES
            TrackingMode.FACE -> FACE_EDGES
        }
        frame.groups.forEach { group ->
            val points = group.associateBy { it.index }
            edges.forEach { (aIndex, bIndex) ->
                val a = points[aIndex]
                val b = points[bIndex]
                if (a != null && b != null) {
                    canvas.drawLine(a.x * bitmap.width, a.y * bitmap.height, b.x * bitmap.width, b.y * bitmap.height, paint)
                }
            }
        }
    }

    /**
     * Landmark-centered smooth lens. Unlike the old implementation this does not stretch a square
     * crop. Pixels are radially warped and smoothly return to their original position at the edge.
     */
    private fun magnify(
        source: Bitmap,
        output: Bitmap,
        frame: TrackingFrame,
        groupIndex: Int,
        originalPointIndex: Int,
        scale: Double,
        radiusFraction: Double
    ) {
        val point = frame.groups.getOrNull(groupIndex)?.firstOrNull { it.index == originalPointIndex } ?: return
        bulge(source, output, point.x.toDouble(), point.y.toDouble(), scale, radiusFraction)
    }

    /** Smooth radial bulge centered at normalized camera coordinates. */
    private fun bulge(
        source: Bitmap,
        output: Bitmap,
        normalizedX: Double,
        normalizedY: Double,
        scale: Double,
        radiusFraction: Double
    ) {
        if (source.width != output.width || source.height != output.height) return

        val width = source.width
        val height = source.height
        val cx = (normalizedX * width).toInt()
        val cy = (normalizedY * height).toInt()
        val radius = (min(width, height) * radiusFraction.coerceIn(0.01, 0.5)).toInt().coerceAtLeast(2)
        if (cx < -radius || cy < -radius || cx >= width + radius || cy >= height + radius) return

        val left = max(0, cx - radius)
        val top = max(0, cy - radius)
        val right = min(width, cx + radius + 1)
        val bottom = min(height, cy + radius + 1)
        val patchWidth = right - left
        val patchHeight = bottom - top
        if (patchWidth < 2 || patchHeight < 2) return

        val sourcePixels = IntArray(patchWidth * patchHeight)
        val outputPixels = IntArray(patchWidth * patchHeight)
        source.getPixels(sourcePixels, 0, patchWidth, left, top, patchWidth, patchHeight)
        output.getPixels(outputPixels, 0, patchWidth, left, top, patchWidth, patchHeight)

        val localCx = cx - left
        val localCy = cy - top
        val safeScale = scale.coerceIn(0.5, 4.0)
        val radiusD = radius.toDouble()

        for (y in 0 until patchHeight) {
            val dy = y - localCy
            for (x in 0 until patchWidth) {
                val dx = x - localCx
                val distance = sqrt((dx * dx + dy * dy).toDouble())
                if (distance >= radiusD) continue

                val d = distance / radiusD
                val falloff = 1.0 - d
                val localScale = 1.0 + (safeScale - 1.0) * falloff * falloff
                val sampleX = localCx + dx / localScale
                val sampleY = localCy + dy / localScale
                val sampled = bilinear(sourcePixels, patchWidth, patchHeight, sampleX, sampleY)

                // Fade the last 12% into the live preview. This hides any tiny timing difference
                // between the analysis frame and the independently rendered CameraX preview.
                val feather = ((1.0 - d) / 0.12).coerceIn(0.0, 1.0).toFloat()
                val index = y * patchWidth + x
                outputPixels[index] = mixColor(outputPixels[index], sampled, feather)
            }
        }

        output.setPixels(outputPixels, 0, patchWidth, left, top, patchWidth, patchHeight)
    }

    private fun bilinear(pixels: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
        val sx = x.coerceIn(0.0, (width - 1).toDouble())
        val sy = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = sx.toInt()
        val y0 = sy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val tx = (sx - x0).toFloat()
        val ty = (sy - y0).toFloat()
        val top = mixColor(pixels[y0 * width + x0], pixels[y0 * width + x1], tx)
        val bottom = mixColor(pixels[y1 * width + x0], pixels[y1 * width + x1], tx)
        return mixColor(top, bottom, ty)
    }

    private fun mixColor(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int): Int = (x + (y - x) * f).toInt().coerceIn(0, 255)
        return Color.argb(
            mix(Color.alpha(a), Color.alpha(b)),
            mix(Color.red(a), Color.red(b)),
            mix(Color.green(a), Color.green(b)),
            mix(Color.blue(a), Color.blue(b))
        )
    }

    private fun pixelate(bitmap: Bitmap, size: Int) {
        val w = max(1, bitmap.width / size)
        val h = max(1, bitmap.height / size)
        val tiny = Bitmap.createScaledBitmap(bitmap, w, h, false)
        Canvas(bitmap).drawBitmap(
            tiny,
            null,
            Rect(0, 0, bitmap.width, bitmap.height),
            Paint().apply { isFilterBitmap = false }
        )
        tiny.recycle()
    }

    private fun condition(
        expr: String,
        vars: Map<String, String>,
        frame: TrackingFrame,
        frameIndex: Long,
        elapsed: Double
    ): Boolean {
        val trimmed = expr.trim()
        if (trimmed == "tracked") return frame.groups.isNotEmpty()
        val match = Regex("^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").matchEntire(trimmed)
        if (match != null) {
            val (left, op, right) = match.destructured
            val ln = numberOrNull(left, vars, frame, frameIndex, elapsed)
            val rn = numberOrNull(right, vars, frame, frameIndex, elapsed)
            if (ln != null && rn != null) return when (op) {
                "==" -> abs(ln - rn) < 1e-9
                "!=" -> abs(ln - rn) >= 1e-9
                ">" -> ln > rn
                "<" -> ln < rn
                ">=" -> ln >= rn
                "<=" -> ln <= rn
                else -> false
            }
            val l = resolveText(left.trim(), vars)
            val r = resolveText(right.trim(), vars)
            return if (op == "==") l == r else if (op == "!=") l != r else false
        }
        return runCatching {
            ExpressionEvaluator(trimmed, vars, frame, frameIndex, elapsed).eval() != 0.0
        }.getOrDefault(false)
    }

    private fun numberOrNull(
        expr: String,
        vars: Map<String, String>,
        frame: TrackingFrame,
        frameIndex: Long,
        elapsed: Double
    ): Double? {
        val t = expr.trim()
        if (
            Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(t) &&
            vars[t]?.toDoubleOrNull() == null &&
            t !in setOf("pi", "e", "time", "frame", "tracked", "groups")
        ) return null
        return runCatching { ExpressionEvaluator(t, vars, frame, frameIndex, elapsed).eval() }.getOrNull()
    }

    private fun resolveText(token: String, vars: Map<String, String>): String =
        vars[token.trim()] ?: token.trim().removeSurrounding("\"").replace('_', ' ')

    private fun parseColor(text: String): Int = Color.parseColor(if (text.startsWith("#")) text else "#$text")

    companion object {
        private val HAND_EDGES = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17
        )

        private val BODY_EDGES = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 7,
            0 to 4, 4 to 5, 5 to 6, 6 to 8,
            9 to 10, 11 to 12, 11 to 13, 13 to 15,
            15 to 17, 15 to 19, 15 to 21, 17 to 19,
            12 to 14, 14 to 16, 16 to 18, 16 to 20,
            16 to 22, 18 to 20, 11 to 23, 12 to 24,
            23 to 24, 23 to 25, 25 to 27, 27 to 29,
            29 to 31, 27 to 31, 24 to 26, 26 to 28,
            28 to 30, 30 to 32, 28 to 32
        )

        private fun loop(points: List<Int>): List<Pair<Int, Int>> =
            points.zipWithNext() + listOf(points.last() to points.first())

        private val FACE_EDGES = buildList {
            addAll(loop(listOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)))
            addAll(loop(listOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)))
            addAll(loop(listOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466)))
            addAll(loop(listOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)))
            addAll(listOf(70 to 63, 63 to 105, 105 to 66, 66 to 107, 336 to 296, 296 to 334, 334 to 293, 293 to 300))
        }
    }
}
