package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Smooth native CameraX preview plus separately throttled MediaPipe/script analysis.
 *
 * Programs containing a `pixels` block are full-frame programs: their result is rendered as the
 * camera image itself. Sparse programs such as `write_pixel` remain transparent overlays.
 */
@Composable
fun FilterCameraView(
    front: Boolean,
    mode: TrackingMode,
    code: String,
    engine: ScriptEngine,
    program: ScriptEngine.Program,
    values: Map<String, String>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latestValues = remember { AtomicReference(values) }
    SideEffect { latestValues.set(values) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val effectView = remember {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }
    val host = remember {
        FrameLayout(context).apply {
            addView(
                previewView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                effectView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }

    AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())

    DisposableEffect(front, mode, code, lifecycle) {
        val worker = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val tracker = TrackingEngine(context.applicationContext)
        val active = AtomicBoolean(true)
        val uiFramePending = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var displayed: Bitmap? = null

        val needsFullFrame = program.usesPixels
        val needsTracking = scriptNeedsTracking(code)
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            if (!active.get()) return@addListener
            val cameraProvider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider

            fun bindWhenReady() {
                if (!active.get()) return
                val viewPort = previewView.viewPort
                if (viewPort == null || previewView.width == 0 || previewView.height == 0) {
                    previewView.postDelayed({ bindWhenReady() }, 16L)
                    return
                }

                val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                // The interpreter is intentionally general, not a hard-coded shader list. A lower
                // analysis size for full-frame pixel programs keeps arbitrary user pixel code
                // responsive while the native preview stays full resolution for overlay scripts.
                val analysisSize = if (needsFullFrame) {
                    android.util.Size(320, 240)
                } else {
                    android.util.Size(640, 480)
                }

                val localAnalysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setTargetResolution(analysisSize)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis = localAnalysis

                localAnalysis.setAnalyzer(worker) { proxy ->
                    if (!active.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }

                    var cameraBitmap: Bitmap? = null
                    var output: Bitmap? = null
                    try {
                        cameraBitmap = proxyToVisibleBitmap(proxy, front)
                        val tracking = if (needsTracking) {
                            tracker.detect(cameraBitmap, mode)
                        } else {
                            // Color-only filters such as a user-written invert do not need to pay
                            // the MediaPipe cost every frame.
                            TrackingFrame(mode, emptyList(), System.currentTimeMillis())
                        }

                        output = if (needsFullFrame) {
                            engine.render(cameraBitmap, tracking, program, latestValues.get()).also {
                                // Camera RGBA buffers are visually opaque. Force that contract at
                                // display time so an odd producer alpha channel can never make a
                                // correct full-frame pixel filter appear to do nothing.
                                it.setHasAlpha(false)
                            }
                        } else {
                            engine.renderOverlay(cameraBitmap, tracking, program, latestValues.get())
                        }

                        val frameToPost = output
                        if (active.get() && frameToPost != null && uiFramePending.compareAndSet(false, true)) {
                            mainExecutor.execute {
                                try {
                                    if (active.get() && !frameToPost.isRecycled) {
                                        val old = displayed
                                        effectView.setImageBitmap(frameToPost)
                                        displayed = frameToPost
                                        if (old != null && old !== frameToPost && !old.isRecycled) old.recycle()
                                    } else if (!frameToPost.isRecycled) {
                                        frameToPost.recycle()
                                    }
                                } finally {
                                    uiFramePending.set(false)
                                }
                            }
                            output = null
                        }
                    } catch (_: Throwable) {
                        // Bad frames and bad user scripts are isolated to one analyzer iteration.
                    } finally {
                        output?.let { if (!it.isRecycled) it.recycle() }
                        cameraBitmap?.let { if (!it.isRecycled) it.recycle() }
                        proxy.close()
                    }
                }

                val group = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(localAnalysis)
                    .build()

                runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycle,
                        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                        group
                    )
                }
            }

            previewView.post { bindWhenReady() }
        }, mainExecutor)

        onDispose {
            active.set(false)
            runCatching { analysis?.clearAnalyzer() }
            runCatching { provider?.unbindAll() }
            worker.shutdown()
            runCatching { worker.awaitTermination(500, TimeUnit.MILLISECONDS) }
            if (!worker.isTerminated) worker.shutdownNow()
            runCatching { tracker.close() }
            effectView.setImageDrawable(null)
            displayed?.let { if (!it.isRecycled) it.recycle() }
            displayed = null
        }
    }
}

private fun scriptNeedsTracking(code: String): Boolean = Regex(
    "(?i)\\b(tracked|groups|landmark_[A-Za-z0-9_]*|point_exists|group_[A-Za-z0-9_]*)\\b"
).containsMatchIn(code)

/** Apply CameraX's shared ViewPort crop before rotation/mirroring. */
private fun proxyToVisibleBitmap(proxy: androidx.camera.core.ImageProxy, mirror: Boolean): Bitmap {
    val raw = proxy.toBitmap()
    val crop = proxy.cropRect
    val cropped = if (
        crop.left == 0 && crop.top == 0 && crop.width() == raw.width && crop.height() == raw.height
    ) {
        raw
    } else {
        Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height()).also {
            if (it !== raw && !raw.isRecycled) raw.recycle()
        }
    }
    return rotateCameraBitmap(cropped, proxy.imageInfo.rotationDegrees, mirror)
}

private fun rotateCameraBitmap(source: Bitmap, degrees: Int, mirror: Boolean): Bitmap {
    if (degrees == 0 && !mirror) return source
    val matrix = Matrix()
    if (degrees != 0) matrix.postRotate(degrees.toFloat())
    if (mirror) matrix.postScale(-1f, 1f)
    val out = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (out !== source && !source.isRecycled) source.recycle()
    return out
}
