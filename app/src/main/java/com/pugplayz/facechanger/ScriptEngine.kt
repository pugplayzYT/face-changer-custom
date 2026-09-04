package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Small deliberately non-Turing-complete-ish, host-controlled filter language.
 * It has no filesystem, reflection, Android, network, process or shell access.
 */
class ScriptEngine {
    data class Program(val inputs: List<ScriptInput>, val statements: List<Statement>)
    sealed interface Statement
    data class Skeleton(val color: Int, val width: Float) : Statement
    data class Dots(val color: Int, val radius: Float) : Statement
    data class Magnify(val group: Int, val point: Int, val scaleExpr: String, val radiusExpr: String) : Statement
    data class Pixelate(val sizeExpr: String) : Statement
    data class Tint(val color: Int, val amountExpr: String) : Statement
    data class Repeat(val countExpr: String, val body: List<Statement>) : Statement
    data class If(val expression: String, val yes: List<Statement>, val no: List<Statement>) : Statement

    fun parse(source: String): Program {
        val lines = source.lines().map { it.substringBefore("#").trim() }.filter { it.isNotEmpty() }
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
        val type = if (p[1] == "number") InputType.NUMBER else InputType.TEXT
        return ScriptInput(
            name = p[2], label = p[3].replace('_', ' '), type = type, defaultValue = p[4],
            min = p.getOrNull(5)?.toDoubleOrNull(), max = p.getOrNull(6)?.toDoubleOrNull()
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
                "skeleton" -> out += Skeleton(parseColor(p[1]), p.getOrElse(2) { "3" }.toFloat())
                "dots" -> out += Dots(parseColor(p[1]), p.getOrElse(2) { "5" }.toFloat())
                "magnify" -> out += Magnify(p[1].toInt(), p[2].toInt(), p[3], p[4])
                "pixelate" -> out += Pixelate(p[1])
                "tint" -> out += Tint(parseColor(p[1]), p[2])
                "repeat" -> {
                    val (child, end) = parseBlock(lines, i + 1, setOf("end"))
                    require(end < lines.size && lines[end] == "end") { "repeat missing end" }
                    out += Repeat(p[1], child); i = end
                }
                "if" -> {
                    val (yes, split) = parseBlock(lines, i + 1, setOf("else", "end"))
                    var no = emptyList<Statement>()
                    var end = split
                    if (split < lines.size && lines[split] == "else") {
                        val parsed = parseBlock(lines, split + 1, setOf("end")); no = parsed.first; end = parsed.second
                    }
                    require(end < lines.size && lines[end] == "end") { "if missing end" }
                    out += If(line.removePrefix("if "), yes, no); i = end
                }
                else -> error("Unknown command: ${p[0]}")
            }
            i++
        }
        return out to i
    }

    fun render(source: Bitmap, frame: TrackingFrame, program: Program, values: Map<String, String>): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val vars = program.inputs.associate { it.name to (values[it.name] ?: it.defaultValue) }
        execute(output, frame, program.statements, vars)
        return output
    }

    private fun execute(bitmap: Bitmap, frame: TrackingFrame, statements: List<Statement>, vars: Map<String, String>) {
        val canvas = Canvas(bitmap)
        statements.forEach { s ->
            when (s) {
                is Skeleton -> drawSkeleton(canvas, bitmap, frame, s.color, s.width)
                is Dots -> drawDots(canvas, bitmap, frame, s.color, s.radius)
                is Magnify -> magnify(bitmap, frame, s.group, s.point, num(s.scaleExpr, vars), num(s.radiusExpr, vars))
                is Pixelate -> pixelate(bitmap, max(2, num(s.sizeExpr, vars).toInt()))
                is Tint -> {
                    val paint = Paint().apply { color = s.color; alpha = (255 * num(s.amountExpr, vars).coerceIn(0.0, 1.0)).toInt() }
                    canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
                }
                is Repeat -> repeat(num(s.countExpr, vars).toInt().coerceIn(0, 1000)) { execute(bitmap, frame, s.body, vars) }
                is If -> execute(bitmap, frame, if (condition(s.expression, vars, frame)) s.yes else s.no, vars)
            }
        }
    }

    private fun drawDots(canvas: Canvas, bitmap: Bitmap, frame: TrackingFrame, color: Int, radius: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        frame.groups.flatten().forEach { canvas.drawCircle(it.x * bitmap.width, it.y * bitmap.height, radius, p) }
    }

    private fun drawSkeleton(canvas: Canvas, bitmap: Bitmap, frame: TrackingFrame, color: Int, width: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = width; style = Paint.Style.STROKE }
        frame.groups.forEach { group ->
            group.zipWithNext().forEach { (a, b) -> canvas.drawLine(a.x * bitmap.width, a.y * bitmap.height, b.x * bitmap.width, b.y * bitmap.height, p) }
        }
    }

    private fun magnify(bitmap: Bitmap, frame: TrackingFrame, groupIndex: Int, pointIndex: Int, scale: Double, radiusFraction: Double) {
        val group = frame.groups.getOrNull(groupIndex) ?: return
        val point = group.getOrNull(pointIndex) ?: return
        val cx = (point.x * bitmap.width).toInt(); val cy = (point.y * bitmap.height).toInt()
        val r = (min(bitmap.width, bitmap.height) * radiusFraction.coerceIn(0.01, 0.5)).toInt()
        val src = Rect(max(0, cx-r), max(0, cy-r), min(bitmap.width, cx+r), min(bitmap.height, cy+r))
        if (src.width() < 2 || src.height() < 2) return
        val patch = Bitmap.createBitmap(bitmap, src.left, src.top, src.width(), src.height())
        val destW = (src.width() * scale.coerceIn(0.2, 4.0)).toFloat(); val destH = (src.height() * scale.coerceIn(0.2, 4.0)).toFloat()
        Canvas(bitmap).drawBitmap(patch, null, RectF(cx-destW/2, cy-destH/2, cx+destW/2, cy+destH/2), Paint(Paint.FILTER_BITMAP_FLAG))
        patch.recycle()
    }

    private fun pixelate(bitmap: Bitmap, size: Int) {
        val w = max(1, bitmap.width / size); val h = max(1, bitmap.height / size)
        val tiny = Bitmap.createScaledBitmap(bitmap, w, h, false)
        Canvas(bitmap).drawBitmap(tiny, null, Rect(0, 0, bitmap.width, bitmap.height), Paint().apply { isFilterBitmap = false })
        tiny.recycle()
    }

    private fun num(expr: String, vars: Map<String, String>): Double = vars[expr]?.toDoubleOrNull() ?: expr.toDoubleOrNull() ?: 0.0
    private fun condition(expr: String, vars: Map<String, String>, frame: TrackingFrame): Boolean {
        val p = expr.split(Regex("\\s+"))
        if (p.size == 1) return when (p[0]) { "tracked" -> frame.groups.isNotEmpty(); else -> (vars[p[0]] ?: p[0]).toBooleanStrictOrNull() ?: false }
        if (p.size < 3) return false
        val a = vars[p[0]] ?: p[0]; val b = vars[p[2]] ?: p[2]
        return when (p[1]) { "==" -> a == b; "!=" -> a != b; ">" -> (a.toDoubleOrNull() ?: 0.0) > (b.toDoubleOrNull() ?: 0.0); "<" -> (a.toDoubleOrNull() ?: 0.0) < (b.toDoubleOrNull() ?: 0.0); ">=" -> (a.toDoubleOrNull() ?: 0.0) >= (b.toDoubleOrNull() ?: 0.0); "<=" -> (a.toDoubleOrNull() ?: 0.0) <= (b.toDoubleOrNull() ?: 0.0); else -> false }
    }

    private fun parseColor(text: String): Int = Color.parseColor(if (text.startsWith("#")) text else "#$text")
}
