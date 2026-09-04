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
 * Smooth preview + separately throttled analysis.
 *
 * Preview and analysis are bound in one UseCaseGroup with PreviewView's ViewPort, then the
 * ImageProxy cropRect is applied before MediaPipe sees the frame. That makes normalized landmarks
 * and script pixels refer to the exact same sensor area visible on screen instead of a slightly
 * wider analysis frame.
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
            addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(effectView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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

        val needsSourcePixels = scriptNeedsCameraPixels(code)
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

                val localAnalysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setTargetResolution(android.util.Size(640, 480))
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
                        val tracking = tracker.detect(cameraBitmap, mode)

                        output = if (needsSourcePixels) {
                            engine.render(cameraBitmap, tracking, program, latestValues.get())
                        } else {
                            val transparent = Bitmap.createBitmap(cameraBitmap.width, cameraBitmap.height, Bitmap.Config.ARGB_8888)
                            transparent.eraseColor(android.graphics.Color.TRANSPARENT)
                            try {
                                engine.render(transparent, tracking, program, latestValues.get())
                            } finally {
                                if (!transparent.isRecycled) transparent.recycle()
                            }
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

private fun scriptNeedsCameraPixels(code: String): Boolean =
    Regex("(?im)^\\s*(magnify|pixelate)\\b").containsMatchIn(code)

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
