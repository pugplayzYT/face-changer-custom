package com.pugplayz.facechanger

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

class TrackingEngine(private val context: Context) : AutoCloseable {
    private var face: FaceLandmarker? = null
    private var hand: HandLandmarker? = null
    private var pose: PoseLandmarker? = null

    fun detect(bitmap: Bitmap, mode: TrackingMode, detail: DetailLevel): TrackingFrame {
        val image = BitmapImageBuilder(bitmap).build()
        val groups = when (mode) {
            TrackingMode.FACE -> {
                val detector = face ?: FaceLandmarker.createFromOptions(
                    context,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                        .setNumFaces(1)
                        .build()
                ).also { face = it }
                detector.detect(image).faceLandmarks().map { list ->
                    list.mapIndexed { index, p -> Point3(p.x(), p.y(), p.z(), index) }
                }
            }
            TrackingMode.HAND -> {
                val detector = hand ?: HandLandmarker.createFromOptions(
                    context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                        .setNumHands(2)
                        .build()
                ).also { hand = it }
                detector.detect(image).landmarks().map { list ->
                    list.mapIndexed { index, p -> Point3(p.x(), p.y(), p.z(), index) }
                }
            }
            TrackingMode.BODY -> {
                val detector = pose ?: PoseLandmarker.createFromOptions(
                    context,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").build())
                        .setNumPoses(1)
                        .build()
                ).also { pose = it }
                detector.detect(image).landmarks().map { list ->
                    list.mapIndexed { index, p -> Point3(p.x(), p.y(), p.z(), index) }
                }
            }
        }
        return TrackingFrame(mode, groups.map { reduceDetail(it, detail) }, System.currentTimeMillis())
    }

    private fun reduceDetail(points: List<Point3>, detail: DetailLevel): List<Point3> {
        if (detail == DetailLevel.HIGH || points.isEmpty()) return points
        val target = when (detail) {
            DetailLevel.LOW -> when {
                points.size > 100 -> 40
                points.size > 30 -> 16
                else -> 11
            }
            DetailLevel.MEDIUM -> when {
                points.size > 100 -> 140
                points.size > 30 -> 28
                else -> 17
            }
            DetailLevel.HIGH -> points.size
        }
        if (target >= points.size) return points
        val step = points.size.toDouble() / target
        return (0 until target).map { points[(it * step).toInt().coerceAtMost(points.lastIndex)] }
    }

    override fun close() {
        face?.close(); hand?.close(); pose?.close()
    }
}
