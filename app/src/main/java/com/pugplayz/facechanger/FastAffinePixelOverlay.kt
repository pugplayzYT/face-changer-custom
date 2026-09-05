package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Native Skia fast path for large, point-wise affine colour filters.
 *
 * The bytecode VM can prove that a single pixels block is channel-independent via usesColorLookup.
 * For those programs we cheaply probe the exact VM result on five source values. If every output
 * channel is affine, the live camera frame is drawn through Android's native ColorMatrix pipeline
 * instead of visiting every camera pixel in Kotlin and then doing a second full-frame diff pass.
 *
 * This keeps arbitrary/non-linear scripts on the exact bytecode path. It is especially useful for
 * invert/brightness/contrast style filters where making the pixels rectangle larger should not turn
 * the render thread into a per-pixel interpreter benchmark.
 */
internal fun compileFastAffinePixelOverlay(
    code: String,
    program: ScriptEngine.Program,
    compiled: PixelBytecodeProgram?
): FastAffinePixelOverlay? {
    if (compiled?.usesColorLookup != true) return null
    val pixels = program.statements.singleOrNull() as? ScriptEngine.Pixels ?: return null

    // The tiny probe uses a synthetic image. Geometry/time dependent colour math would therefore
    // observe different constants, so retain the exact VM for those uncommon scripts.
    if (Regex("(?i)\\b(image_width|image_height|aspect|time|frame)\\b").containsMatchIn(code)) return null

    return FastAffinePixelOverlay(program, pixels, compiled)
}

internal class FastAffinePixelOverlay(
    private val program: ScriptEngine.Program,
    private val pixels: ScriptEngine.Pixels,
    private val compiled: PixelBytecodeProgram
) {
    fun render(
        source: Bitmap,
        frame: TrackingFrame,
        values: Map<String, String>
    ): Bitmap? {
        val matrix = deriveColorMatrix(frame, values) ?: return null
        val bounds = pixelBounds(source.width, source.height, frame, values) ?: return transparent(source)
        if (bounds.left >= bounds.right || bounds.top >= bounds.bottom) return transparent(source)

        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            output.setHasAlpha(true)
            val canvas = Canvas(output)
            canvas.save()
            canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            canvas.drawBitmap(source, 0f, 0f, paint)
            canvas.restore()
        }
    }

    private fun deriveColorMatrix(frame: TrackingFrame, values: Map<String, String>): ColorMatrix? {
        // Five points catch curved transforms while keeping the probe tiny. The probe is still run
        // by the exact bytecode program, so user inputs and script arithmetic remain the authority.
        val samples = intArrayOf(0, 64, 128, 192, 255)
        val width = 32
        val height = 32
        val bounds = pixelBounds(width, height, frame, values) ?: return null
        if (bounds.right - bounds.left < samples.size || bounds.bottom <= bounds.top) return null

        val source = IntArray(width * height) { 0xff000000.toInt() }
        val y = bounds.top.coerceIn(0, height - 1)
        val sampleIndices = IntArray(samples.size)
        for (i in samples.indices) {
            val x = (bounds.left + i).coerceIn(0, width - 1)
            val value = samples[i]
            sampleIndices[i] = y * width + x
            source[sampleIndices[i]] = pack(value, value, value, value)
        }

        val rendered = compiled.renderPixels(source, width, height, frame, values)
        val outR = IntArray(samples.size)
        val outG = IntArray(samples.size)
        val outB = IntArray(samples.size)
        val outA = IntArray(samples.size)
        for (i in samples.indices) {
            val color = rendered[sampleIndices[i]]
            outR[i] = (color ushr 16) and 255
            outG[i] = (color ushr 8) and 255
            outB[i] = color and 255
            outA[i] = color ushr 24
        }

        val red = affine(samples, outR) ?: return null
        val green = affine(samples, outG) ?: return null
        val blue = affine(samples, outB) ?: return null
        val alpha = affine(samples, outA) ?: return null

        return ColorMatrix(
            floatArrayOf(
                red.scale, 0f, 0f, 0f, red.bias,
                0f, green.scale, 0f, 0f, green.bias,
                0f, 0f, blue.scale, 0f, blue.bias,
                0f, 0f, 0f, alpha.scale, alpha.bias
            )
        )
    }

    private fun pixelBounds(
        width: Int,
        height: Int,
        frame: TrackingFrame,
        values: Map<String, String>
    ): PixelBounds? {
        if (width <= 0 || height <= 0) return null
        val vars = program.inputs.associate { input ->
            input.name to (values[input.name] ?: input.defaultValue)
        }.toMutableMap()
        vars["image_width"] = width.toString()
        vars["image_height"] = height.toString()
        vars["aspect"] = (width.toDouble() / max(1, height)).toString()

        fun eval(expression: String?): Double? {
            if (expression == null) return null
            return runCatching {
                ExpressionEvaluator(
                    source = expression,
                    vars = vars,
                    tracking = frame,
                    frameIndex = 0L,
                    elapsedSeconds = 0.0
                ).eval()
            }.getOrNull()
        }

        val nx = eval(pixels.x) ?: 0.0
        val ny = eval(pixels.y) ?: 0.0
        val nw = eval(pixels.width) ?: 1.0
        val nh = eval(pixels.height) ?: 1.0

        val x0n = min(nx, nx + nw).coerceIn(0.0, 1.0)
        val x1n = max(nx, nx + nw).coerceIn(0.0, 1.0)
        val y0n = min(ny, ny + nh).coerceIn(0.0, 1.0)
        val y1n = max(ny, ny + nh).coerceIn(0.0, 1.0)

        return PixelBounds(
            left = floor(x0n * width).toInt().coerceIn(0, width),
            right = ceil(x1n * width).toInt().coerceIn(0, width),
            top = floor(y0n * height).toInt().coerceIn(0, height),
            bottom = ceil(y1n * height).toInt().coerceIn(0, height)
        )
    }

    private fun transparent(source: Bitmap): Bitmap =
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }
}

private data class PixelBounds(val left: Int, val right: Int, val top: Int, val bottom: Int)
private data class AffineChannel(val scale: Float, val bias: Float)

private fun affine(input: IntArray, output: IntArray): AffineChannel? {
    val span = (input.last() - input.first()).toFloat()
    if (span == 0f) return null
    val scale = (output.last() - output.first()) / span
    val bias = output.first() - scale * input.first()

    for (i in input.indices) {
        val expected = scale * input[i] + bias
        if (kotlin.math.abs(expected - output[i]) > 1.5f) return null
    }
    return AffineChannel(scale, bias)
}

private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
    (a shl 24) or (r shl 16) or (g shl 8) or b
