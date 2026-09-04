package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
 * CameraX's ViewPort already crops ImageAnalysis to the same visible field of view as PreviewView.
 * The effect layer therefore stretches that already-cropped bitmap to the host instead of applying
 * a second CENTER_CROP, which previously made every processed filter look heavily zoomed in.
 *
 * Pixel programs are converted to a difference overlay: unchanged camera pixels become transparent
 * so local face/hand/body effects preserve the full-resolution native preview everywhere they do
 * not actually modify the image. Only genuinely changed pixels cover the preview.
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
            // ImageAnalysis has already been cropped with PreviewView's ViewPort. CENTER_CROP here
            // cropped it a second time and was the source of the giant apparent zoom.
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }
    val statusView = remember {
        TextView(context).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.argb(225, 125, 28, 28))
            textSize = 12f
            setPadding(24, 14, 24, 14)
            visibility = View.GONE
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
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                ).apply {
                    topMargin = (64 * resources.displayMetrics.density).toInt()
                    marginStart = (12 * resources.displayMetrics.density).toInt()
                    marginEnd = (12 * resources.displayMetrics.density).toInt()
                }
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
        val lastError = AtomicReference<String?>(null)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var displayed: Bitmap? = null

        fun showError(prefix: String, throwable: Throwable) {
            val detail = throwableDetails(throwable)
            val message = "$prefix: $detail"
            if (lastError.getAndSet(message) == message) return
            Log.e(TAG, message, throwable)
            mainExecutor.execute {
                if (active.get()) {
                    statusView.text = message
                    statusView.visibility = View.VISIBLE
                }
            }
        }

        fun clearError() {
            if (lastError.getAndSet(null) == null) return
            mainExecutor.execute {
                if (active.get()) {
                    statusView.text = ""
                    statusView.visibility = View.GONE
                }
            }
        }

        val hasGlobalPixels = programHasGlobalPixels(program)
        val needsTracking = scriptNeedsTracking(code)
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            if (!active.get()) return@addListener
            val cameraProvider = try {
                providerFuture.get()
            } catch (t: Throwable) {
                showError("Camera provider failed", t)
                return@addListener
            }
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

                // Local pixel effects only touch a small rectangle, so they can afford a proper
                // 640x480 analysis frame. Truly full-frame interpreted filters use a compromise
                // resolution to avoid turning the sandbox into a slideshow.
                val analysisSize = if (hasGlobalPixels) {
                    android.util.Size(480, 360)
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
                            try {
                                tracker.detect(cameraBitmap, mode)
                            } catch (t: Throwable) {
                                throw IllegalStateException(
                                    "MediaPipe ${mode.name.lowercase()} tracking could not run",
                                    t
                                )
                            }
                        } else {
                            // Color-only filters such as a user-written invert do not need to pay
                            // the MediaPipe cost every frame.
                            TrackingFrame(mode, emptyList(), System.currentTimeMillis())
                        }

                        output = if (program.usesPixels) {
                            // render() starts from an exact copy of the source camera frame. Convert
                            // that result into a transparent difference layer so a local warp does
                            // not replace the entire smooth/high-resolution preview with a low-res
                            // analysis bitmap.
                            engine.render(cameraBitmap, tracking, program, latestValues.get()).also {
                                makeDifferenceOverlay(cameraBitmap, it)
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
                                        effectView.visibility = View.VISIBLE
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
                        clearError()
                    } catch (t: Throwable) {
                        showError("Filter runtime error", t)
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

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycle,
                        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                        group
                    )
                    clearError()
                } catch (t: Throwable) {
                    showError("Camera bind failed", t)
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
            statusView.text = ""
            statusView.visibility = View.GONE
            displayed?.let { if (!it.isRecycled) it.recycle() }
            displayed = null
        }
    }
}

private fun programHasGlobalPixels(program: ScriptEngine.Program): Boolean =
    containsGlobalPixels(program.statements) || program.functions.values.any { containsGlobalPixels(it.body) }

private fun containsGlobalPixels(statements: List<ScriptEngine.Statement>): Boolean = statements.any { statement ->
    when (statement) {
        is ScriptEngine.Pixels -> statement.x == null
        is ScriptEngine.Repeat -> containsGlobalPixels(statement.body)
        is ScriptEngine.If -> containsGlobalPixels(statement.yes) || containsGlobalPixels(statement.no)
        else -> false
    }
}

/**
 * Turn a fully rendered frame into an overlay by making pixels that are byte-for-byte identical to
 * the source transparent. Local pixel filters therefore only cover the part they actually changed.
 */
private fun makeDifferenceOverlay(source: Bitmap, rendered: Bitmap) {
    require(source.width == rendered.width && source.height == rendered.height) {
        "Rendered filter size does not match camera frame"
    }
    val width = source.width
    val height = source.height
    val count = width * height
    val sourcePixels = IntArray(count)
    val renderedPixels = IntArray(count)
    source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
    rendered.getPixels(renderedPixels, 0, width, 0, 0, width, height)

    for (index in 0 until count) {
        if (renderedPixels[index] == sourcePixels[index]) {
            renderedPixels[index] = android.graphics.Color.TRANSPARENT
        }
    }

    rendered.setHasAlpha(true)
    rendered.setPixels(renderedPixels, 0, width, 0, 0, width, height)
}

private fun scriptNeedsTracking(code: String): Boolean = Regex(
    "(?i)\\b(tracked|groups|landmark_[A-Za-z0-9_]*|point_exists|group_[A-Za-z0-9_]*)\\b"
).containsMatchIn(code)

private fun throwableDetails(throwable: Throwable): String {
    val chain = generateSequence(throwable) { it.cause }.toList()
    val useful = chain.asReversed().firstOrNull { !it.message.isNullOrBlank() } ?: throwable
    val detail = useful.message?.trim().orEmpty()
    return if (detail.isNotEmpty()) {
        detail.take(220)
    } else {
        useful::class.java.simpleName.ifBlank { "Unknown error" }
    }
}

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

private const val TAG = "FaceChangerCamera"
