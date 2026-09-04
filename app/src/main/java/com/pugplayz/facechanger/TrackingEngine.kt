package com.pugplayz.facechanger

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.max

/**
 * MediaPipe wrapper kept on the camera worker thread.
 *
 * Tracking returns every landmark from the selected MediaPipe task. The detector does not need the
 * full 640x480 overlay bitmap, though: MediaPipe's models resize internally anyway. Feeding a
 * bounded 512px-long-edge copy cuts memory bandwidth and detector work while normalized landmark
 * coordinates still map exactly back onto the full effect frame.
 */
class TrackingEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var face: FaceLandmarker? = null
    private var hand: HandLandmarker? = null
    private var pose: PoseLandmarker? = null
    private var lastTimestampMs = 0L

    @Synchronized
    fun detect(bitmap: Bitmap, mode: TrackingMode): TrackingFrame {
        val trackingBitmap = trackingCopy(bitmap)
        val image = BitmapImageBuilder(trackingBitmap).build()
        val timestampMs = nextTimestampMs()
        try {
            val groups = when (mode) {
                TrackingMode.FACE -> {
                    val detector = face ?: FaceLandmarker.createFromOptions(
                        appContext,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumFaces(1)
                            .build()
                    ).also { face = it }
                    detector.detectForVideo(image, timestampMs).faceLandmarks().map { list ->
                        list.mapIndexed { index, point -> Point3(point.x(), point.y(), point.z(), index) }
                    }
                }

                TrackingMode.HAND -> {
                    val detector = hand ?: HandLandmarker.createFromOptions(
                        appContext,
                        HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumHands(2)
                            .build()
                    ).also { hand = it }
                    detector.detectForVideo(image, timestampMs).landmarks().map { list ->
                        list.mapIndexed { index, point -> Point3(point.x(), point.y(), point.z(), index) }
                    }
                }

                TrackingMode.BODY -> {
                    val detector = pose ?: PoseLandmarker.createFromOptions(
                        appContext,
                        PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumPoses(1)
                            .build()
                    ).also { pose = it }
                    detector.detectForVideo(image, timestampMs).landmarks().map { list ->
                        list.mapIndexed { index, point -> Point3(point.x(), point.y(), point.z(), index) }
                    }
                }
            }
            return TrackingFrame(mode, groups, timestampMs)
        } finally {
            runCatching { image.close() }
            if (!trackingBitmap.isRecycled) trackingBitmap.recycle()
        }
    }

    private fun trackingCopy(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= TRACKING_LONG_EDGE) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = TRACKING_LONG_EDGE.toDouble() / longest.toDouble()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun nextTimestampMs(): Long {
        val now = SystemClock.uptimeMillis()
        val next = maxOf(now, lastTimestampMs + 1L)
        lastTimestampMs = next
        return next
    }

    /** Compatibility for the retired quality UI: detail is intentionally ignored. */
    fun detect(bitmap: Bitmap, mode: TrackingMode, @Suppress("UNUSED_PARAMETER") detail: DetailLevel): TrackingFrame =
        detect(bitmap, mode)

    @Synchronized
    override fun close() {
        runCatching { face?.close() }
        runCatching { hand?.close() }
        runCatching { pose?.close() }
        face = null
        hand = null
        pose = null
    }

    companion object {
        private const val TRACKING_LONG_EDGE = 512
    }
}
