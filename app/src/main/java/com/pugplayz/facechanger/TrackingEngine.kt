package com.pugplayz.facechanger

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/**
 * MediaPipe wrapper kept on the camera worker thread.
 *
 * Tracking now always returns the complete landmark set. The old low/medium point sampling was
 * fundamentally wrong for programmable filters because valid MediaPipe indexes simply vanished.
 * Every MPImage is still closed in finally and detect/close remain synchronized for safe teardown.
 */
class TrackingEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var face: FaceLandmarker? = null
    private var hand: HandLandmarker? = null
    private var pose: PoseLandmarker? = null

    @Synchronized
    fun detect(bitmap: Bitmap, mode: TrackingMode): TrackingFrame {
        val image = BitmapImageBuilder(bitmap).build()
        try {
            val groups = when (mode) {
                TrackingMode.FACE -> {
                    val detector = face ?: FaceLandmarker.createFromOptions(
                        appContext,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                            .setNumFaces(1)
                            .build()
                    ).also { face = it }
                    detector.detect(image).faceLandmarks().map { list ->
                        list.mapIndexed { index, point -> Point3(point.x(), point.y(), point.z(), index) }
                    }
                }

                TrackingMode.HAND -> {
                    val detector = hand ?: HandLandmarker.createFromOptions(
                        appContext,
                        HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                            .setNumHands(2)
                            .build()
                    ).also { hand = it }
                    detector.detect(image).landmarks().map { list ->
                        list.mapIndexed { index, point -> Point3(point.x(), point.y(), point.z(), index) }
                    }
                }

                TrackingMode.BODY -> {
                    val detector = pose ?: PoseLandmarker.createFromOptions(
                        appContext,
                        PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").build())
                            .setNumPoses(1)
                            .build()
                    ).also { pose = it }
                    detector.detect(image).landmarks().map { list ->
                        list.mapIndexed { index, point -> Point3(point.x(), point.y(), point.z(), index) }
                    }
                }
            }
            return TrackingFrame(mode, groups, System.currentTimeMillis())
        } finally {
            runCatching { image.close() }
        }
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
