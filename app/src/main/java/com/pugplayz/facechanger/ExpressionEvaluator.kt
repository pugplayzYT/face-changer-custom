package com.pugplayz.facechanger

import kotlin.math.*

/**
 * Tiny numeric expression evaluator used by the filter sandbox.
 *
 * This intentionally has no reflection, host calls, dynamic code, files, network, Android APIs,
 * processes or allocation primitives exposed to scripts. Expressions are also bounded so a
 * pathological user expression cannot recurse forever or consume unlimited parser work.
 */
class ExpressionEvaluator(
    private val source: String,
    private val vars: Map<String, String>,
    private val tracking: TrackingFrame,
    private val frameIndex: Long,
    private val elapsedSeconds: Double
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
        var v = parseMulDiv()
        while (true) {
            tick()
            skipSpace()
            v = when {
                take('+') -> v + parseMulDiv()
                take('-') -> v - parseMulDiv()
                else -> return v
            }
        }
    }

    private fun parseMulDiv(): Double {
        var v = parsePower()
        while (true) {
            tick()
            skipSpace()
            v = when {
                take('*') -> v * parsePower()
                take('/') -> {
                    val d = parsePower()
                    if (abs(d) < 1e-12) 0.0 else v / d
                }
                take('%') -> {
                    val d = parsePower()
                    if (abs(d) < 1e-12) 0.0 else v % d
                }
                else -> return v
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
            val v = nested { parseAddSub() }
            skipSpace()
            require(take(')')) { "Missing ')'" }
            return v
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
        "e" -> Math.E
        "tau" -> Math.PI * 2.0
        "time" -> elapsedSeconds
        "frame" -> frameIndex.toDouble()
        "tracked" -> if (tracking.groups.isNotEmpty()) 1.0 else 0.0
        "groups" -> tracking.groups.size.toDouble()
        else -> vars[name]?.toDoubleOrNull() ?: 0.0
    }

    /**
     * Whitelisted pure functions only. Adding a function here can expose math/tracking data, but
     * must never call arbitrary classes or host services.
     */
    private fun call(name: String, a: List<Double>): Double = when (name.lowercase()) {
        // Scientific math
        "sin" -> sin(arg(a, 0))
        "cos" -> cos(arg(a, 0))
        "tan" -> tan(arg(a, 0))
        "asin" -> asin(arg(a, 0).coerceIn(-1.0, 1.0))
        "acos" -> acos(arg(a, 0).coerceIn(-1.0, 1.0))
        "atan" -> atan(arg(a, 0))
        "atan2" -> atan2(arg(a, 0), arg(a, 1))
        "sqrt" -> sqrt(max(0.0, arg(a, 0)))
        "cbrt" -> cbrt(arg(a, 0))
        "abs" -> abs(arg(a, 0))
        "floor" -> floor(arg(a, 0))
        "ceil" -> ceil(arg(a, 0))
        "round" -> round(arg(a, 0))
        "sign" -> sign(arg(a, 0))
        "min" -> if (a.isEmpty()) 0.0 else a.minOrNull() ?: 0.0
        "max" -> if (a.isEmpty()) 0.0 else a.maxOrNull() ?: 0.0
        "sum" -> a.sum()
        "avg", "mean" -> if (a.isEmpty()) 0.0 else a.average()
        "pow" -> arg(a, 0).pow(arg(a, 1))
        "ln" -> ln(max(1e-12, arg(a, 0)))
        "log10" -> log10(max(1e-12, arg(a, 0)))
        "exp" -> exp(arg(a, 0).coerceIn(-60.0, 60.0))
        "hypot" -> hypot(arg(a, 0), arg(a, 1))
        "deg" -> Math.toDegrees(arg(a, 0))
        "rad" -> Math.toRadians(arg(a, 0))
        "clamp" -> arg(a, 0).coerceIn(min(arg(a, 1), arg(a, 2)), max(arg(a, 1), arg(a, 2)))
        "saturate" -> arg(a, 0).coerceIn(0.0, 1.0)
        "lerp" -> arg(a, 0) + (arg(a, 1) - arg(a, 0)) * arg(a, 2)
        "inverse_lerp" -> {
            val lo = arg(a, 0)
            val hi = arg(a, 1)
            val d = hi - lo
            if (abs(d) < 1e-12) 0.0 else (arg(a, 2) - lo) / d
        }
        "map" -> {
            val inLo = arg(a, 1)
            val inHi = arg(a, 2)
            val d = inHi - inLo
            val t = if (abs(d) < 1e-12) 0.0 else (arg(a, 0) - inLo) / d
            arg(a, 3) + (arg(a, 4) - arg(a, 3)) * t
        }
        "smoothstep" -> {
            val lo = arg(a, 0)
            val hi = arg(a, 1)
            val d = hi - lo
            val t = if (abs(d) < 1e-12) 0.0 else ((arg(a, 2) - lo) / d).coerceIn(0.0, 1.0)
            t * t * (3.0 - 2.0 * t)
        }
        "step" -> if (arg(a, 1) < arg(a, 0)) 0.0 else 1.0
        "fract" -> arg(a, 0) - floor(arg(a, 0))
        "wrap" -> {
            val lo = arg(a, 1)
            val hi = arg(a, 2)
            val span = hi - lo
            if (abs(span) < 1e-12) lo else ((arg(a, 0) - lo) % span + span) % span + lo
        }
        "distance" -> hypot(arg(a, 2) - arg(a, 0), arg(a, 3) - arg(a, 1))
        "angle" -> atan2(arg(a, 3) - arg(a, 1), arg(a, 2) - arg(a, 0))

        // Safe boolean/comparison helpers. They return 1 or 0 and can be nested in `if`.
        "eq" -> bool(abs(arg(a, 0) - arg(a, 1)) < 1e-9)
        "ne" -> bool(abs(arg(a, 0) - arg(a, 1)) >= 1e-9)
        "lt" -> bool(arg(a, 0) < arg(a, 1))
        "lte" -> bool(arg(a, 0) <= arg(a, 1))
        "gt" -> bool(arg(a, 0) > arg(a, 1))
        "gte" -> bool(arg(a, 0) >= arg(a, 1))
        "and" -> bool(a.all { truthy(it) })
        "or" -> bool(a.any { truthy(it) })
        "not" -> bool(!truthy(arg(a, 0)))
        "select", "ifelse" -> if (truthy(arg(a, 0))) arg(a, 1) else arg(a, 2)

        // Deterministic animation/noise: no system RNG or external entropy.
        "noise" -> {
            val x = arg(a, 0) * 12.9898 + frameIndex * 0.071
            val n = sin(x) * 43758.5453
            n - floor(n)
        }
        "hash" -> {
            val x = arg(a, 0) * 12.9898 + arg(a, 1) * 78.233 + arg(a, 2) * 37.719
            val n = sin(x) * 43758.5453
            n - floor(n)
        }

        // Landmark access
        "landmark_count" -> group(arg(a, 0))?.size?.toDouble() ?: 0.0
        "landmark_x" -> point(arg(a, 0), arg(a, 1))?.x?.toDouble() ?: 0.0
        "landmark_y" -> point(arg(a, 0), arg(a, 1))?.y?.toDouble() ?: 0.0
        "landmark_z" -> point(arg(a, 0), arg(a, 1))?.z?.toDouble() ?: 0.0
        "point_exists" -> bool(point(arg(a, 0), arg(a, 1)) != null)
        "landmark_distance" -> {
            val p1 = point(arg(a, 0), arg(a, 1))
            val p2 = point(arg(a, 0), arg(a, 2))
            if (p1 == null || p2 == null) 0.0 else hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble())
        }
        "landmark_mid_x" -> {
            val p1 = point(arg(a, 0), arg(a, 1))
            val p2 = point(arg(a, 0), arg(a, 2))
            if (p1 == null || p2 == null) 0.0 else (p1.x + p2.x) / 2.0
        }
        "landmark_mid_y" -> {
            val p1 = point(arg(a, 0), arg(a, 1))
            val p2 = point(arg(a, 0), arg(a, 2))
            if (p1 == null || p2 == null) 0.0 else (p1.y + p2.y) / 2.0
        }
        "landmark_angle" -> landmarkAngle(arg(a, 0), arg(a, 1), arg(a, 2), arg(a, 3))

        // Group geometry makes filters scale to the tracked face/hand/body instead of screen size.
        "group_min_x" -> bounds(arg(a, 0))?.minX ?: 0.0
        "group_max_x" -> bounds(arg(a, 0))?.maxX ?: 0.0
        "group_min_y" -> bounds(arg(a, 0))?.minY ?: 0.0
        "group_max_y" -> bounds(arg(a, 0))?.maxY ?: 0.0
        "group_width" -> bounds(arg(a, 0))?.let { it.maxX - it.minX } ?: 0.0
        "group_height" -> bounds(arg(a, 0))?.let { it.maxY - it.minY } ?: 0.0
        "group_center_x" -> bounds(arg(a, 0))?.let { (it.minX + it.maxX) / 2.0 } ?: 0.0
        "group_center_y" -> bounds(arg(a, 0))?.let { (it.minY + it.maxY) / 2.0 } ?: 0.0

        else -> error("Unknown function: $name")
    }

    private fun landmarkAngle(groupIndex: Double, aIndex: Double, bIndex: Double, cIndex: Double): Double {
        val pa = point(groupIndex, aIndex) ?: return 0.0
        val pb = point(groupIndex, bIndex) ?: return 0.0
        val pc = point(groupIndex, cIndex) ?: return 0.0
        val ax = (pa.x - pb.x).toDouble()
        val ay = (pa.y - pb.y).toDouble()
        val cx = (pc.x - pb.x).toDouble()
        val cy = (pc.y - pb.y).toDouble()
        val dot = ax * cx + ay * cy
        val cross = ax * cy - ay * cx
        return abs(atan2(cross, dot))
    }

    private data class Bounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

    private fun bounds(groupIndex: Double): Bounds? {
        val points = group(groupIndex) ?: return null
        if (points.isEmpty()) return null
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        points.forEach { point ->
            minX = min(minX, point.x.toDouble())
            maxX = max(maxX, point.x.toDouble())
            minY = min(minY, point.y.toDouble())
            maxY = max(maxY, point.y.toDouble())
        }
        return Bounds(minX, maxX, minY, maxY)
    }

    private fun arg(a: List<Double>, index: Int): Double = a.getOrElse(index) { 0.0 }
    private fun truthy(value: Double): Boolean = abs(value) > 1e-12
    private fun bool(value: Boolean): Double = if (value) 1.0 else 0.0

    private fun group(index: Double): List<Point3>? = tracking.groups.getOrNull(index.toInt())

    private fun point(group: Double, originalIndex: Double): Point3? =
        this.group(group)?.firstOrNull { it.index == originalIndex.toInt() }

    private fun take(c: Char): Boolean {
        if (pos < source.length && source[pos] == c) {
            pos++
            return true
        }
        return false
    }

    private fun skipSpace() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }

    private fun tick() {
        steps++
        require(steps <= MAX_PARSE_STEPS) { "Expression is too complex" }
    }

    private inline fun <T> nested(block: () -> T): T {
        depth++
        require(depth <= MAX_NESTING) { "Expression nesting is too deep" }
        return try {
            block()
        } finally {
            depth--
        }
    }

    companion object {
        private const val MAX_EXPRESSION_CHARS = 1024
        private const val MAX_PARSE_STEPS = 4096
        private const val MAX_NESTING = 64
        private const val MAX_FUNCTION_ARGS = 16
        private const val MAX_IDENTIFIER_CHARS = 64
    }
}
