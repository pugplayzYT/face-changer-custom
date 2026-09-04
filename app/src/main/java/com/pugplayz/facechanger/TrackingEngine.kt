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

/**
 * MediaPipe wrapper kept on the camera worker thread.
 *
 * Tracking always returns the complete landmark set. MediaPipe gets its own bitmap copy because
 * closing BitmapImageBuilder's MPImage can recycle the bitmap backing it. VIDEO mode is used rather
 * than treating every camera frame as an unrelated still image; MediaPipe can then reuse temporal
 * tracking between frames instead of doing the full still-image path over and over.
 */
class TrackingEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var face: FaceLandmarker? = null
    private var hand: HandLandmarker? = null
    private var pose: PoseLandmarker? = null
    private var lastTimestampMs = 0L

    @Synchronized
    fun detect(bitmap: Bitmap, mode: TrackingMode): TrackingFrame {
        // BitmapImageBuilder/MPImage owns the bitmap it wraps. If we wrap the camera bitmap itself,
        // image.close() can recycle it and ScriptEngine.render() immediately fails at getPixels().
        // Give MediaPipe a private copy so its lifetime can never invalidate the renderer's source.
        val trackingBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
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
            // Some MediaPipe implementations recycle the wrapped bitmap on close; some merely
            // release their reference. Cover both cases without ever double-recycling it.
            if (!trackingBitmap.isRecycled) trackingBitmap.recycle()
        }
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
}
