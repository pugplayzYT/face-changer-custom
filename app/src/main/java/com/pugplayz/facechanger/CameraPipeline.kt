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
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
 * Native CameraX preview plus a separately analyzed effect layer.
 *
 * Preview and analysis deliberately use the same 4:3 camera aspect ratio and both are FIT_CENTER.
 * Keeping the whole sensor frame means MediaPipe landmarks and script pixels share one simple
 * coordinate system: the upright, optionally mirrored analysis bitmap.
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
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val effectView = remember {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
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
            setBackgroundColor(android.graphics.Color.BLACK)
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

            val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

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
                    cameraBitmap = proxyToDisplayBitmap(proxy, front)
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
                        TrackingFrame(mode, emptyList(), System.currentTimeMillis())
                    }

                    // The bundled Face/Hand/Body examples used to spend most of their frame time
                    // emulating vector lines with thousands of interpreted write_pixel calls. Their
                    // exact unmodified generated signatures get a native Canvas fast-path instead.
                    // Edited/forked scripts immediately fall back to the normal sandbox renderer.
                    val bundledOverlay = if (!program.usesPixels && needsTracking) {
                        renderBundledTrackingOverlay(
                            code = code,
                            mode = mode,
                            frame = tracking,
                            width = cameraBitmap.width,
                            height = cameraBitmap.height
                        )
                    } else null

                    // Some tracked local-pixel kernels are tiny on screen but huge for an
                    // interpreter: every pixel runs several expressions, branches and channel sets.
                    // Recognized kernels can render directly into a transparent overlay, avoiding
                    // both the interpreter hot loop and the full-frame difference pass.
                    val optimizedTrackedPixels = if (program.usesPixels && needsTracking) {
                        renderOptimizedTrackedPixelOverlay(
                            code = code,
                            mode = mode,
                            frame = tracking,
                            source = cameraBitmap
                        )
                    } else null

                    output = bundledOverlay ?: optimizedTrackedPixels ?: if (program.usesPixels) {
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

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycle,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    localAnalysis
                )
                clearError()
            } catch (t: Throwable) {
                showError("Camera bind failed", t)
            }
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

/** Make unchanged pixels transparent so the native preview remains visible underneath. */
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
    return if (detail.isNotEmpty()) detail.take(220)
    else useful::class.java.simpleName.ifBlank { "Unknown error" }
}

/** Convert CameraX's buffer into exactly the upright image shown by the effect layer. */
private fun proxyToDisplayBitmap(proxy: androidx.camera.core.ImageProxy, mirror: Boolean): Bitmap {
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

    val rotated = if (proxy.imageInfo.rotationDegrees == 0) {
        cropped
    } else {
        Bitmap.createBitmap(
            cropped,
            0,
            0,
            cropped.width,
            cropped.height,
            Matrix().apply { setRotate(proxy.imageInfo.rotationDegrees.toFloat()) },
            true
        ).also {
            if (it !== cropped && !cropped.isRecycled) cropped.recycle()
        }
    }

    if (!mirror) return rotated

    return Bitmap.createBitmap(
        rotated,
        0,
        0,
        rotated.width,
        rotated.height,
        Matrix().apply { setScale(-1f, 1f) },
        true
    ).also {
        if (it !== rotated && !rotated.isRecycled) rotated.recycle()
    }
}

private const val TAG = "FaceChangerCamera"