package com.pugplayz.facechanger

import kotlin.math.*

/** Tiny numeric expression evaluator used by the sandbox. No reflection or host calls. */
class ExpressionEvaluator(
    private val source: String,
    private val vars: Map<String, String>,
    private val tracking: TrackingFrame,
    private val frameIndex: Long,
    private val elapsedSeconds: Double
) {
    private var pos = 0

    fun eval(): Double {
        pos = 0
        val value = parseAddSub()
        skipSpace()
        require(pos == source.length) { "Unexpected '${source.substring(pos)}' in expression" }
        return if (value.isFinite()) value else 0.0
    }

    private fun parseAddSub(): Double {
        var v = parseMulDiv()
        while (true) {
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
            skipSpace()
            v = when {
                take('*') -> v * parsePower()
                take('/') -> {
                    val d = parsePower(); if (abs(d) < 1e-12) 0.0 else v / d
                }
                take('%') -> {
                    val d = parsePower(); if (abs(d) < 1e-12) 0.0 else v % d
                }
                else -> return v
            }
        }
    }

    private fun parsePower(): Double {
        var base = parseUnary()
        skipSpace()
        if (take('^')) base = base.pow(parsePower())
        return base
    }

    private fun parseUnary(): Double {
        skipSpace()
        return when {
            take('+') -> parseUnary()
            take('-') -> -parseUnary()
            else -> parsePrimary()
        }
    }

    private fun parsePrimary(): Double {
        skipSpace()
        if (take('(')) {
            val v = parseAddSub(); skipSpace(); require(take(')')) { "Missing ')'" }; return v
        }
        if (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) return parseNumber()
        val name = parseIdentifier()
        if (name.isEmpty()) error("Expected number or variable near '${source.substring(pos)}'")
        skipSpace()
        if (take('(')) {
            val args = mutableListOf<Double>()
            skipSpace()
            if (!take(')')) {
                while (true) {
                    args += parseAddSub(); skipSpace()
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
            if (c.isDigit() || c == '.') { pos++; continue }
            if ((c == 'e' || c == 'E') && !seenExponent) {
                seenExponent = true; pos++
                if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) pos++
                continue
            }
            break
        }
        return source.substring(start, pos).toDouble()
    }

    private fun parseIdentifier(): String {
        skipSpace(); val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        return source.substring(start, pos)
    }

    private fun variable(name: String): Double = when (name.lowercase()) {
        "pi" -> Math.PI
        "e" -> Math.E
        "time" -> elapsedSeconds
        "frame" -> frameIndex.toDouble()
        "tracked" -> if (tracking.groups.isNotEmpty()) 1.0 else 0.0
        "groups" -> tracking.groups.size.toDouble()
        else -> vars[name]?.toDoubleOrNull() ?: 0.0
    }

    private fun call(name: String, a: List<Double>): Double = when (name.lowercase()) {
        "sin" -> sin(arg(a, 0)); "cos" -> cos(arg(a, 0)); "tan" -> tan(arg(a, 0))
        "asin" -> asin(arg(a, 0).coerceIn(-1.0, 1.0)); "acos" -> acos(arg(a, 0).coerceIn(-1.0, 1.0)); "atan" -> atan(arg(a, 0))
        "atan2" -> atan2(arg(a, 0), arg(a, 1))
        "sqrt" -> sqrt(max(0.0, arg(a, 0))); "cbrt" -> cbrt(arg(a, 0)); "abs" -> abs(arg(a, 0))
        "floor" -> floor(arg(a, 0)); "ceil" -> ceil(arg(a, 0)); "round" -> round(arg(a, 0)); "sign" -> sign(arg(a, 0))
        "min" -> min(arg(a, 0), arg(a, 1)); "max" -> max(arg(a, 0), arg(a, 1)); "pow" -> arg(a, 0).pow(arg(a, 1))
        "ln" -> ln(max(1e-12, arg(a, 0))); "log10" -> log10(max(1e-12, arg(a, 0))); "exp" -> exp(arg(a, 0).coerceIn(-60.0, 60.0))
        "hypot" -> hypot(arg(a, 0), arg(a, 1)); "deg" -> Math.toDegrees(arg(a, 0)); "rad" -> Math.toRadians(arg(a, 0))
        "clamp" -> arg(a, 0).coerceIn(min(arg(a, 1), arg(a, 2)), max(arg(a, 1), arg(a, 2)))
        "lerp" -> arg(a, 0) + (arg(a, 1) - arg(a, 0)) * arg(a, 2)
        "smoothstep" -> {
            val lo = arg(a, 0); val hi = arg(a, 1); val d = hi - lo
            val t = if (abs(d) < 1e-12) 0.0 else ((arg(a, 2) - lo) / d).coerceIn(0.0, 1.0)
            t * t * (3.0 - 2.0 * t)
        }
        "fract" -> arg(a, 0) - floor(arg(a, 0))
        "noise" -> {
            val x = arg(a, 0) * 12.9898 + frameIndex * 0.071
            val n = sin(x) * 43758.5453
            n - floor(n)
        }
        "landmark_count" -> group(arg(a, 0))?.size?.toDouble() ?: 0.0
        "landmark_x" -> point(arg(a, 0), arg(a, 1))?.x?.toDouble() ?: 0.0
        "landmark_y" -> point(arg(a, 0), arg(a, 1))?.y?.toDouble() ?: 0.0
        "landmark_z" -> point(arg(a, 0), arg(a, 1))?.z?.toDouble() ?: 0.0
        "point_exists" -> if (point(arg(a, 0), arg(a, 1)) != null) 1.0 else 0.0
        else -> 0.0
    }

    private fun arg(a: List<Double>, index: Int): Double = a.getOrElse(index) { 0.0 }
    private fun group(index: Double): List<Point3>? = tracking.groups.getOrNull(index.toInt())
    private fun point(group: Double, originalIndex: Double): Point3? =
        this.group(group)?.firstOrNull { it.index == originalIndex.toInt() }

    private fun take(c: Char): Boolean {
        if (pos < source.length && source[pos] == c) { pos++; return true }
        return false
    }

    private fun skipSpace() { while (pos < source.length && source[pos].isWhitespace()) pos++ }
}
