package com.pugplayz.facechanger

import android.graphics.Color
import kotlin.math.*

/**
 * Numeric expression evaluator for the sandboxed filter language.
 *
 * It intentionally exposes only pure math, tracking data, script variables and an optional
 * read-only sampler for the current camera frame. There is no reflection, host invocation,
 * filesystem, network, Android service, shell, native code or dynamic-code bridge.
 */
class ExpressionEvaluator(
    private val source: String,
    private val vars: Map<String, String>,
    private val tracking: TrackingFrame,
    private val frameIndex: Long,
    private val elapsedSeconds: Double,
    private val samplePixel: ((Double, Double) -> Int)? = null
) {
    private var pos = 0
    private var steps = 0
    private var depth = 0

    fun eval(): Double {
        require(source.length <= MAX_EXPRESSION_CHARS) {
            "Expression is too long (max $MAX_EXPRESSION_CHARS characters)"
        }
        pos = 0
        steps = 0
        depth = 0
        val value = parseAddSub()
        skipSpace()
        require(pos == source.length) { "Unexpected '${source.substring(pos)}' in expression" }
        return if (value.isFinite()) value else 0.0
    }

    private fun parseAddSub(): Double {
        var value = parseMulDiv()
        while (true) {
            tick()
            skipSpace()
            value = when {
                take('+') -> value + parseMulDiv()
                take('-') -> value - parseMulDiv()
                else -> return value
            }
        }
    }

    private fun parseMulDiv(): Double {
        var value = parsePower()
        while (true) {
            tick()
            skipSpace()
            value = when {
                take('*') -> value * parsePower()
                take('/') -> {
                    val divisor = parsePower()
                    if (abs(divisor) < 1e-12) 0.0 else value / divisor
                }
                take('%') -> {
                    val divisor = parsePower()
                    if (abs(divisor) < 1e-12) 0.0 else value % divisor
                }
                else -> return value
            }
        }
    }

    private fun parsePower(): Double {
        tick()
        var base = parseUnary()
        skipSpace()
        if (take('^')) base = base.pow(nested { parsePower() })
        return base
    }

    private fun parseUnary(): Double {
        tick()
        skipSpace()
        return when {
            take('+') -> nested { parseUnary() }
            take('-') -> -nested { parseUnary() }
            else -> parsePrimary()
        }
    }

    private fun parsePrimary(): Double {
        tick()
        skipSpace()
        if (take('(')) {
            val value = nested { parseAddSub() }
            skipSpace()
            require(take(')')) { "Missing ')'" }
            return value
        }

        if (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) {
            return parseNumber()
        }

        val name = parseIdentifier()
        if (name.isEmpty()) error("Expected number or variable near '${source.substring(pos)}'")
        require(name.length <= MAX_IDENTIFIER_CHARS) { "Identifier is too long" }

        skipSpace()
        if (take('(')) {
            val args = mutableListOf<Double>()
            skipSpace()
            if (!take(')')) {
                while (true) {
                    require(args.size < MAX_FUNCTION_ARGS) { "Too many arguments to $name(...)" }
                    args += nested { parseAddSub() }
                    skipSpace()
                    if (take(')')) break
                    require(take(',')) { "Expected ',' in $name(...)" }
                }
            }
            return call(name, args)
        }
        return variable(name)
    }

    private fun parseNumber(): Double {
        val start = pos
        var seenExponent = false
        while (pos < source.length) {
            val c = source[pos]
            if (c.isDigit() || c == '.') {
                pos++
                continue
            }
            if ((c == 'e' || c == 'E') && !seenExponent) {
                seenExponent = true
                pos++
                if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) pos++
                continue
            }
            break
        }
        return source.substring(start, pos).toDouble()
    }

    private fun parseIdentifier(): String {
        skipSpace()
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        return source.substring(start, pos)
    }

    private fun variable(name: String): Double = when (name.lowercase()) {
        "pi" -> Math.PI
        "tau" -> Math.PI * 2.0
        "e" -> Math.E
        "time" -> elapsedSeconds
        "frame" -> frameIndex.toDouble()
        "tracked" -> if (tracking.groups.isNotEmpty()) 1.0 else 0.0
        "groups" -> tracking.groups.size.toDouble()
        else -> vars[name]?.toDoubleOrNull() ?: 0.0
    }

    private fun call(name: String, args: List<Double>): Double = when (name.lowercase()) {
        "sin" -> sin(arg(args, 0))
        "cos" -> cos(arg(args, 0))
        "tan" -> tan(arg(args, 0))
        "asin" -> asin(arg(args, 0).coerceIn(-1.0, 1.0))
        "acos" -> acos(arg(args, 0).coerceIn(-1.0, 1.0))
        "atan" -> atan(arg(args, 0))
        "atan2" -> atan2(arg(args, 0), arg(args, 1))
        "sqrt" -> sqrt(max(0.0, arg(args, 0)))
        "cbrt" -> cbrt(arg(args, 0))
        "abs" -> abs(arg(args, 0))
        "floor" -> floor(arg(args, 0))
        "ceil" -> ceil(arg(args, 0))
        "round" -> round(arg(args, 0))
        "sign" -> sign(arg(args, 0))
        "min" -> if (args.isEmpty()) 0.0 else args.minOrNull() ?: 0.0
        "max" -> if (args.isEmpty()) 0.0 else args.maxOrNull() ?: 0.0
        "sum" -> args.sum()
        "avg", "mean" -> if (args.isEmpty()) 0.0 else args.average()
        "pow" -> arg(args, 0).pow(arg(args, 1))
        "ln" -> ln(max(1e-12, arg(args, 0)))
        "log10" -> log10(max(1e-12, arg(args, 0)))
        "exp" -> exp(arg(args, 0).coerceIn(-60.0, 60.0))
        "hypot" -> hypot(arg(args, 0), arg(args, 1))
        "deg" -> Math.toDegrees(arg(args, 0))
        "rad" -> Math.toRadians(arg(args, 0))
        "clamp" -> arg(args, 0).coerceIn(min(arg(args, 1), arg(args, 2)), max(arg(args, 1), arg(args, 2)))
        "saturate" -> arg(args, 0).coerceIn(0.0, 1.0)
        "lerp" -> arg(args, 0) + (arg(args, 1) - arg(args, 0)) * arg(args, 2)
        "inverse_lerp" -> {
            val lo = arg(args, 0); val hi = arg(args, 1); val span = hi - lo
            if (abs(span) < 1e-12) 0.0 else (arg(args, 2) - lo) / span
        }
        "map" -> {
            val value = arg(args, 0); val inLo = arg(args, 1); val inHi = arg(args, 2); val span = inHi - inLo
            val t = if (abs(span) < 1e-12) 0.0 else (value - inLo) / span
            arg(args, 3) + (arg(args, 4) - arg(args, 3)) * t
        }
        "smoothstep" -> {
            val lo = arg(args, 0); val hi = arg(args, 1); val span = hi - lo
            val t = if (abs(span) < 1e-12) 0.0 else ((arg(args, 2) - lo) / span).coerceIn(0.0, 1.0)
            t * t * (3.0 - 2.0 * t)
        }
        "step" -> if (arg(args, 1) < arg(args, 0)) 0.0 else 1.0
        "fract" -> arg(args, 0) - floor(arg(args, 0))
        "wrap" -> {
            val lo = arg(args, 1); val hi = arg(args, 2); val span = hi - lo
            if (abs(span) < 1e-12) lo else ((arg(args, 0) - lo) % span + span) % span + lo
        }
        "distance" -> hypot(arg(args, 2) - arg(args, 0), arg(args, 3) - arg(args, 1))
        "angle" -> atan2(arg(args, 3) - arg(args, 1), arg(args, 2) - arg(args, 0))
        "eq" -> bool(abs(arg(args, 0) - arg(args, 1)) < 1e-9)
        "ne" -> bool(abs(arg(args, 0) - arg(args, 1)) >= 1e-9)
        "lt" -> bool(arg(args, 0) < arg(args, 1))
        "lte" -> bool(arg(args, 0) <= arg(args, 1))
        "gt" -> bool(arg(args, 0) > arg(args, 1))
        "gte" -> bool(arg(args, 0) >= arg(args, 1))
        "and" -> bool(args.all { truthy(it) })
        "or" -> bool(args.any { truthy(it) })
        "not" -> bool(!truthy(arg(args, 0)))
        "select", "ifelse" -> if (truthy(arg(args, 0))) arg(args, 1) else arg(args, 2)
        "noise" -> {
            val x = arg(args, 0) * 12.9898 + frameIndex * 0.071
            val n = sin(x) * 43758.5453
            n - floor(n)
        }
        "hash" -> {
            val x = arg(args, 0) * 12.9898 + arg(args, 1) * 78.233 + arg(args, 2) * 37.719
            val n = sin(x) * 43758.5453
            n - floor(n)
        }
        "landmark_count" -> group(arg(args, 0))?.size?.toDouble() ?: 0.0
        "landmark_x" -> point(arg(args, 0), arg(args, 1))?.x?.toDouble() ?: 0.0
        "landmark_y" -> point(arg(args, 0), arg(args, 1))?.y?.toDouble() ?: 0.0
        "landmark_z" -> point(arg(args, 0), arg(args, 1))?.z?.toDouble() ?: 0.0
        "point_exists" -> bool(point(arg(args, 0), arg(args, 1)) != null)
        "landmark_distance" -> {
            val p1 = point(arg(args, 0), arg(args, 1)); val p2 = point(arg(args, 0), arg(args, 2))
            if (p1 == null || p2 == null) 0.0 else hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble())
        }
        "landmark_mid_x" -> {
            val p1 = point(arg(args, 0), arg(args, 1)); val p2 = point(arg(args, 0), arg(args, 2))
            if (p1 == null || p2 == null) 0.0 else (p1.x + p2.x) / 2.0
        }
        "landmark_mid_y" -> {
            val p1 = point(arg(args, 0), arg(args, 1)); val p2 = point(arg(args, 0), arg(args, 2))
            if (p1 == null || p2 == null) 0.0 else (p1.y + p2.y) / 2.0
        }
        "landmark_angle" -> landmarkAngle(arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3))
        "group_min_x" -> bounds(arg(args, 0))?.minX ?: 0.0
        "group_max_x" -> bounds(arg(args, 0))?.maxX ?: 0.0
        "group_min_y" -> bounds(arg(args, 0))?.minY ?: 0.0
        "group_max_y" -> bounds(arg(args, 0))?.maxY ?: 0.0
        "group_width" -> bounds(arg(args, 0))?.let { it.maxX - it.minX } ?: 0.0
        "group_height" -> bounds(arg(args, 0))?.let { it.maxY - it.minY } ?: 0.0
        "group_center_x" -> bounds(arg(args, 0))?.let { (it.minX + it.maxX) / 2.0 } ?: 0.0
        "group_center_y" -> bounds(arg(args, 0))?.let { (it.minY + it.maxY) / 2.0 } ?: 0.0
        "sample_r" -> Color.red(sampleColor(arg(args, 0), arg(args, 1))) / 255.0
        "sample_g" -> Color.green(sampleColor(arg(args, 0), arg(args, 1))) / 255.0
        "sample_b" -> Color.blue(sampleColor(arg(args, 0), arg(args, 1))) / 255.0
        "sample_a" -> Color.alpha(sampleColor(arg(args, 0), arg(args, 1))) / 255.0
        "sample_luma" -> {
            val c = sampleColor(arg(args, 0), arg(args, 1))
            (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114) / 255.0
        }
        else -> error("Unknown function: $name")
    }

    private fun sampleColor(x: Double, y: Double): Int =
        samplePixel?.invoke(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0)) ?: Color.TRANSPARENT

    private fun landmarkAngle(groupIndex: Double, aIndex: Double, bIndex: Double, cIndex: Double): Double {
        val pa = point(groupIndex, aIndex) ?: return 0.0
        val pb = point(groupIndex, bIndex) ?: return 0.0
        val pc = point(groupIndex, cIndex) ?: return 0.0
        val ax = (pa.x - pb.x).toDouble(); val ay = (pa.y - pb.y).toDouble()
        val cx = (pc.x - pb.x).toDouble(); val cy = (pc.y - pb.y).toDouble()
        return abs(atan2(ax * cy - ay * cx, ax * cx + ay * cy))
    }

    private data class Bounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

    private fun bounds(groupIndex: Double): Bounds? {
        val points = group(groupIndex) ?: return null
        if (points.isEmpty()) return null
        return Bounds(
            points.minOf { it.x.toDouble() }, points.maxOf { it.x.toDouble() },
            points.minOf { it.y.toDouble() }, points.maxOf { it.y.toDouble() }
        )
    }

    private fun arg(args: List<Double>, index: Int): Double = args.getOrElse(index) { 0.0 }
    private fun truthy(value: Double): Boolean = abs(value) > 1e-12
    private fun bool(value: Boolean): Double = if (value) 1.0 else 0.0
    private fun group(index: Double): List<Point3>? = tracking.groups.getOrNull(index.toInt())
    private fun point(group: Double, originalIndex: Double): Point3? =
        this.group(group)?.firstOrNull { it.index == originalIndex.toInt() }

    private fun take(c: Char): Boolean {
        if (pos < source.length && source[pos] == c) { pos++; return true }
        return false
    }

    private fun skipSpace() { while (pos < source.length && source[pos].isWhitespace()) pos++ }

    private fun tick() {
        steps++
        require(steps <= MAX_STEPS) { "Expression is too complex" }
    }

    private inline fun <T> nested(block: () -> T): T {
        depth++
        require(depth <= MAX_DEPTH) { "Expression nesting is too deep" }
        try { return block() } finally { depth-- }
    }

    companion object {
        private const val MAX_EXPRESSION_CHARS = 1024
        private const val MAX_IDENTIFIER_CHARS = 64
        private const val MAX_FUNCTION_ARGS = 16
        private const val MAX_STEPS = 4096
        private const val MAX_DEPTH = 64
    }
}
