package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Native raster fast-path for the unmodified bundled tracking examples.
 *
 * The examples remain valid ordinary sandbox programs: this class only recognizes their exact
 * generated signatures and produces the same kind of overlay without interpreting thousands of
 * write_pixel operations every frame. As soon as a user edits/forks the source so the signature no
 * longer matches, ScriptEngine handles it normally.
 */
internal fun renderBundledTrackingOverlay(
    code: String,
    mode: TrackingMode,
    frame: TrackingFrame,
    width: Int,
    height: Int
): Bitmap? {
    val overlayStyle = bundledStyle(code, mode) ?: return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(
            (overlayStyle.lineR * 255f).toInt(),
            (overlayStyle.lineG * 255f).toInt(),
            (overlayStyle.lineB * 255f).toInt()
        )
        this.style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(
            (overlayStyle.dotR * 255f).toInt(),
            (overlayStyle.dotG * 255f).toInt(),
            (overlayStyle.dotB * 255f).toInt()
        )
        this.style = Paint.Style.FILL
    }

    val edges = when (mode) {
        TrackingMode.FACE -> FACE_EDGES
        TrackingMode.HAND -> HAND_EDGES
        TrackingMode.BODY -> BODY_EDGES
    }
    val dotRadius = when (mode) {
        TrackingMode.FACE -> 1.45f
        TrackingMode.HAND -> 2.6f
        TrackingMode.BODY -> 2.6f
    }

    frame.groups.forEach groupLoop@ { group ->
        if (group.isEmpty()) return@groupLoop
        val byIndex = arrayOfNulls<Point3>((group.maxOfOrNull { it.index } ?: -1) + 1)
        group.forEach { point ->
            if (point.index in byIndex.indices) byIndex[point.index] = point
        }

        edges.forEach edgeLoop@ { (aIndex, bIndex) ->
            val a = byIndex.getOrNull(aIndex) ?: return@edgeLoop
            val b = byIndex.getOrNull(bIndex) ?: return@edgeLoop
            canvas.drawLine(
                a.x * (width - 1),
                a.y * (height - 1),
                b.x * (width - 1),
                b.y * (height - 1),
                linePaint
            )
        }

        // Draw every point returned by MediaPipe. Face mode therefore shows the full dense point
        // cloud instead of only the small contour subset used by the previous generated script.
        group.forEach { point ->
            canvas.drawCircle(
                point.x * (width - 1),
                point.y * (height - 1),
                dotRadius,
                dotPaint
            )
        }
    }

    return bitmap
}

private data class OverlayStyle(
    val lineR: Float,
    val lineG: Float,
    val lineB: Float,
    val dotR: Float,
    val dotG: Float,
    val dotB: Float
)

/** Match only the exact color/signature emitted by premadeFilters(). */
private fun bundledStyle(code: String, mode: TrackingMode): OverlayStyle? {
    if (!code.contains("fn segment grp a b rr gg bb")) return null
    if (!code.contains("let dot_count = landmark_count(dot_grp)")) return null
    if (code.contains("pixels")) return null

    return when (mode) {
        TrackingMode.FACE -> if (
            code.contains("call segment grp 10 338 0.34 0.66 1.0") &&
            code.contains("write_pixel dot_x dot_y 0.29 0.84 0.67 1")
        ) OverlayStyle(.34f, .66f, 1f, .29f, .84f, .67f) else null

        TrackingMode.HAND -> if (
            code.contains("call segment grp 0 1 0.29 0.84 0.67") &&
            code.contains("write_pixel dot_x dot_y 0.34 0.66 1.0 1")
        ) OverlayStyle(.29f, .84f, .67f, .34f, .66f, 1f) else null

        TrackingMode.BODY -> if (
            code.contains("call segment grp 0 1 0.34 0.66 1.0") &&
            code.contains("write_pixel dot_x dot_y 0.29 0.84 0.67 1")
        ) OverlayStyle(.34f, .66f, 1f, .29f, .84f, .67f) else null
    }
}

private fun loop(points: List<Int>): List<Pair<Int, Int>> =
    if (points.size < 2) emptyList() else points.zipWithNext() + (points.last() to points.first())

private val FACE_EDGES: List<Pair<Int, Int>> = buildList {
    addAll(loop(listOf(10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109)))
    addAll(loop(listOf(33,7,163,144,145,153,154,155,133,173,157,158,159,160,161,246)))
    addAll(loop(listOf(263,249,390,373,374,380,381,382,362,398,384,385,386,387,388,466)))
    addAll(loop(listOf(61,146,91,181,84,17,314,405,321,375,291,409,270,269,267,0,37,39,40,185)))
    addAll(loop(listOf(78,95,88,178,87,14,317,402,318,324,308,415,310,311,312,13,82,81,80,191)))
    addAll(listOf(70 to 63,63 to 105,105 to 66,66 to 107,46 to 53,53 to 52,52 to 65,65 to 55))
    addAll(listOf(336 to 296,296 to 334,334 to 293,293 to 300,276 to 283,283 to 282,282 to 295,295 to 285))
    addAll(listOf(168 to 6,6 to 197,197 to 195,195 to 5,5 to 4,4 to 1,1 to 19,19 to 94,94 to 2))
    addAll(listOf(98 to 97,97 to 2,2 to 326,326 to 327,327 to 294,294 to 278,278 to 344,344 to 440,440 to 275,275 to 4,4 to 45,45 to 220,220 to 115,115 to 48,48 to 64,64 to 98))
    addAll(loop(listOf(469,470,471,472)))
    addAll(loop(listOf(474,475,476,477)))
}

private val HAND_EDGES = listOf(
    0 to 1,1 to 2,2 to 3,3 to 4,0 to 5,5 to 6,6 to 7,7 to 8,5 to 9,9 to 10,10 to 11,11 to 12,
    9 to 13,13 to 14,14 to 15,15 to 16,13 to 17,17 to 18,18 to 19,19 to 20,0 to 17
)

private val BODY_EDGES = listOf(
    0 to 1,1 to 2,2 to 3,3 to 7,0 to 4,4 to 5,5 to 6,6 to 8,9 to 10,11 to 12,11 to 13,13 to 15,
    15 to 17,15 to 19,15 to 21,17 to 19,12 to 14,14 to 16,16 to 18,16 to 20,16 to 22,18 to 20,
    11 to 23,12 to 24,23 to 24,23 to 25,25 to 27,27 to 29,29 to 31,27 to 31,24 to 26,26 to 28,
    28 to 30,30 to 32,28 to 32
)
