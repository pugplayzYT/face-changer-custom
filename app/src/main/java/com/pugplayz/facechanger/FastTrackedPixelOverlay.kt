package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Native fast paths for expensive tracked local-pixel kernels.
 *
 * The sandbox language stays the source of truth. This only recognizes an exact, semantically
 * equivalent kernel shape and returns null for everything else, so edited scripts still fall back
 * to ScriptEngine unchanged.
 *
 * Why this exists: a tiny tracked `pixels` region can still execute thousands of interpreted
 * expressions per camera frame. When tracking and rendering share one analyzer thread, that turns
 * KEEP_ONLY_LATEST into a slideshow: CameraX correctly drops stale frames while the interpreter is
 * still finishing the previous one. The native kernel below does the same pixel math in one tight
 * loop and keeps the tracker free to consume fresh frames.
 */
internal fun renderOptimizedTrackedPixelOverlay(
    code: String,
    mode: TrackingMode,
    frame: TrackingFrame,
    source: Bitmap
): Bitmap? {
    if (mode != TrackingMode.FACE) return null
    if (!isMonochromeEyeKernel(code)) return null

    val face = frame.groups.firstOrNull() ?: return transparentBitmap(source.width, source.height)
    if (face.isEmpty()) return transparentBitmap(source.width, source.height)

    val byIndex = arrayOfNulls<Point3>((face.maxOfOrNull { it.index } ?: -1) + 1)
    face.forEach { point ->
        if (point.index in byIndex.indices) byIndex[point.index] = point
    }

    fun point(index: Int): Point3? = byIndex.getOrNull(index)
    fun midX(a: Int, b: Int): Double? {
        val p1 = point(a) ?: return null
        val p2 = point(b) ?: return null
        return (p1.x + p2.x).toDouble() * 0.5
    }
    fun midY(a: Int, b: Int): Double? {
        val p1 = point(a) ?: return null
        val p2 = point(b) ?: return null
        return (p1.y + p2.y).toDouble() * 0.5
    }

    val minX = face.minOf { it.x }.toDouble()
    val maxX = face.maxOf { it.x }.toDouble()
    val radius = (maxX - minX).coerceAtLeast(0.0) * 0.10
    if (radius <= 0.0) return transparentBitmap(source.width, source.height)

    val leftX = midX(33, 133) ?: return null
    val leftY = midY(159, 145) ?: return null
    val rightX = midX(362, 263) ?: return null
    val rightY = midY(386, 374) ?: return null

    val width = source.width
    val height = source.height
    val count = width * height
    val sourcePixels = IntArray(count)
    val outputPixels = IntArray(count)
    source.getPixels(sourcePixels, 0, width, 0, 0, width, height)

    applyMonochromeCircle(sourcePixels, outputPixels, width, height, leftX, leftY, radius)
    applyMonochromeCircle(sourcePixels, outputPixels, width, height, rightX, rightY, radius)

    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.setHasAlpha(true)
        bitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)
    }
}

private fun isMonochromeEyeKernel(code: String): Boolean {
    // Normalize only whitespace. Expression spelling stays strict so a changed/custom kernel never
    // silently gets different semantics from the interpreter.
    val compact = code.replace(Regex("\\s+"), " ").trim()
    return compact.contains("fn mono_eye cx cy radius") &&
        compact.contains("pixels cx-radius cy-radius radius*2 radius*2") &&
        compact.contains("let dx = x-cx") &&
        compact.contains("let dy = y-cy") &&
        compact.contains("let d = hypot(dx,dy)") &&
        compact.contains("if lt(d,radius)") &&
        compact.contains("let gray = r*0.299+g*0.587+b*0.114") &&
        compact.contains("set r gray") &&
        compact.contains("set g gray") &&
        compact.contains("set b gray") &&
        compact.contains("let eyeRadius = group_width(0)*0.10") &&
        compact.contains("let leftX = landmark_mid_x(0,33,133)") &&
        compact.contains("let leftY = landmark_mid_y(0,159,145)") &&
        compact.contains("let rightX = landmark_mid_x(0,362,263)") &&
        compact.contains("let rightY = landmark_mid_y(0,386,374)") &&
        compact.contains("call mono_eye leftX leftY eyeRadius") &&
        compact.contains("call mono_eye rightX rightY eyeRadius")
}

private fun applyMonochromeCircle(
    source: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    cx: Double,
    cy: Double,
    radius: Double
) {
    // Match ScriptEngine's local `pixels cx-radius cy-radius radius*2 radius*2` bounds.
    val x0n = (cx - radius).coerceIn(0.0, 1.0)
    val x1n = (cx + radius).coerceIn(0.0, 1.0)
    val y0n = (cy - radius).coerceIn(0.0, 1.0)
    val y1n = (cy + radius).coerceIn(0.0, 1.0)

    val left = floor(min(x0n, x1n) * width).toInt().coerceIn(0, width)
    val right = ceil(max(x0n, x1n) * width).toInt().coerceIn(0, width)
    val top = floor(min(y0n, y1n) * height).toInt().coerceIn(0, height)
    val bottom = ceil(max(y0n, y1n) * height).toInt().coerceIn(0, height)

    for (iy in top until bottom) {
        val ny = if (height <= 1) 0.0 else iy.toDouble() / (height - 1).toDouble()
        for (ix in left until right) {
            val nx = if (width <= 1) 0.0 else ix.toDouble() / (width - 1).toDouble()
            if (hypot(nx - cx, ny - cy) >= radius) continue

            val index = iy * width + ix
            val pixel = source[index]
            // Preserve the script's exact Rec.601 coefficients and 0..1 -> 8-bit rounding closely.
            val gray = (
                Color.red(pixel) * 0.299 +
                    Color.green(pixel) * 0.587 +
                    Color.blue(pixel) * 0.114
                ).toInt().coerceIn(0, 255)
            output[index] = Color.argb(Color.alpha(pixel), gray, gray, gray)
        }
    }
}

private fun transparentBitmap(width: Int, height: Int): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }
