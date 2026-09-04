@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package com.pugplayz.facechanger

import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
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
 *
 * Performance is FPS-only: LOW=15, MEDIUM=30 and MAX=60. The setting never changes model quality,
 * landmark count, smoothing, resolution or script semantics. Tracking runs on its own worker so a
 * slow MediaPipe inference cannot block the render/analyzer worker and turn the overlay into a
 * slideshow.
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

    val framePacer = remember { FramePacer(FilterPerformance.MAX) }

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
    val performanceView = remember {
        TextView(context).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.argb(205, 10, 15, 20))
            textSize = 12f
            setPadding(24, 14, 24, 14)
            text = performanceText(framePacer.level)
            contentDescription = "Filter performance. Tap to switch between 15, 30 and 60 FPS."
            setOnClickListener {
                text = performanceText(framePacer.cycle())
            }
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
            addView(
                performanceView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = (70 * resources.displayMetrics.density).toInt()
                    marginEnd = (12 * resources.displayMetrics.density).toInt()
                }
            )
        }
    }

    AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())

    DisposableEffect(front, mode, code, lifecycle) {
        val renderWorker = Executors.newSingleThreadExecutor()
        val trackerWorker = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val tracker = TrackingEngine(context.applicationContext)
        val active = AtomicBoolean(true)
        val trackingBusy = AtomicBoolean(false)
        val latestTracking = AtomicReference(TrackingFrame(mode, emptyList(), 0L))
        val uiFramePending = AtomicBoolean(false)
        val lastError = AtomicReference<String?>(null)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var displayed: Bitmap? = null
        var smoothedTracking: TrackingFrame? = null

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
        // Compile once when the filter is applied, before camera frames start arriving. Numeric
        // pixel programs then execute from DoubleArray slots + stack bytecode instead of walking
        // String maps and expression ASTs for every single pixel. Unsupported scripts stay on the
        // compatibility interpreter automatically.
        val compiledPixelProgram = if (program.usesPixels) compilePixelBytecode(program) else null
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

            localAnalysis.setAnalyzer(renderWorker) { proxy ->
                if (!active.get()) {
                    proxy.close()
                    return@setAnalyzer
                }

                // The performance control changes only this deadline. Resolution, model, landmark
                // count and all visual semantics stay identical at LOW, MEDIUM and MAX.
                if (!framePacer.shouldProcess(System.nanoTime())) {
                    proxy.close()
                    return@setAnalyzer
                }

                var cameraBitmap: Bitmap? = null
                var output: Bitmap? = null
                try {
                    val frameBitmap = proxyToDisplayBitmap(proxy, front)
                    cameraBitmap = frameBitmap

                    // MediaPipe no longer blocks the render loop. At most one inference is in flight;
                    // when it finishes, the latest normalized landmarks atomically replace the old
                    // ones. KEEP_ONLY_LATEST plus this busy gate means there is never a stale queue.
                    if (needsTracking && trackingBusy.compareAndSet(false, true)) {
                        val trackingInput = frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        if (trackingInput == null) {
                            trackingBusy.set(false)
                        } else {
                            trackerWorker.execute {
                                try {
                                    latestTracking.set(tracker.detect(trackingInput, mode))
                                } catch (t: Throwable) {
                                    showError("MediaPipe ${mode.name.lowercase()} tracking could not run", t)
                                } finally {
                                    if (!trackingInput.isRecycled) trackingInput.recycle()
                                    trackingBusy.set(false)
                                }
                            }
                        }
                    }

                    val tracking = if (needsTracking) {
                        val newest = latestTracking.get()
                        smoothTrackingFrame(
                            previous = smoothedTracking,
                            target = newest,
                            alpha = TRACKING_SMOOTHING_ALPHA
                        ).also { smoothedTracking = it }
                    } else {
                        TrackingFrame(mode, emptyList(), System.currentTimeMillis())
                    }

                    // The bundled Face/Hand/Body examples use a native Canvas fast path.
                    val bundledOverlay = if (!program.usesPixels && needsTracking) {
                        renderBundledTrackingOverlay(
                            code = code,
                            mode = mode,
                            frame = tracking,
                            width = frameBitmap.width,
                            height = frameBitmap.height
                        )
                    } else null

                    // Keep the exact tracked-eye kernel on its tight native loop. The generic
                    // bytecode compiler remains the path for arbitrary numeric pixel programs.
                    val optimizedTrackedPixels = if (program.usesPixels && needsTracking) {
                        renderOptimizedTrackedPixelOverlay(
                            code = code,
                            mode = mode,
                            frame = tracking,
                            source = frameBitmap
                        )
                    } else null

                    val bytecodePixels = if (optimizedTrackedPixels == null && compiledPixelProgram != null) {
                        compiledPixelProgram.render(frameBitmap, tracking, latestValues.get()).also {
                            makeDifferenceOverlay(frameBitmap, it)
                        }
                    } else null

                    output = bundledOverlay ?: optimizedTrackedPixels ?: bytecodePixels ?: if (program.usesPixels) {
                        engine.render(frameBitmap, tracking, program, latestValues.get()).also {
                            makeDifferenceOverlay(frameBitmap, it)
                        }
                    } else {
                        engine.renderOverlay(frameBitmap, tracking, program, latestValues.get())
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
                val camera = cameraProvider.bindToLifecycle(
                    lifecycle,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    localAnalysis
                )
                // Keep auto-exposure free to lower capture FPS in dim light. The engine's
                // 60-FPS ceiling must not force the sensor into fixed 60-FPS exposure.
                requestBestSourceFps(camera, 60)
                clearError()
            } catch (t: Throwable) {
                showError("Camera bind failed", t)
            }
        }, mainExecutor)

        onDispose {
            active.set(false)
            runCatching { analysis?.clearAnalyzer() }
            runCatching { provider?.unbindAll() }

            renderWorker.shutdown()
            runCatching { renderWorker.awaitTermination(500, TimeUnit.MILLISECONDS) }
            if (!renderWorker.isTerminated) renderWorker.shutdownNow()

            trackerWorker.shutdown()
            runCatching { trackerWorker.awaitTermination(500, TimeUnit.MILLISECONDS) }
            if (!trackerWorker.isTerminated) trackerWorker.shutdownNow()

            runCatching { tracker.close() }
            effectView.setImageDrawable(null)
            statusView.text = ""
            statusView.visibility = View.GONE
            displayed?.let { if (!it.isRecycled) it.recycle() }
            displayed = null
        }
    }
}

private fun performanceText(level: FilterPerformance): String =
    "${level.label} • up to ${level.targetFps} FPS"

/** Ask Camera2 for a real advertised source range rather than inventing an unsupported FPS range. */
private fun requestBestSourceFps(camera: Camera, desiredFps: Int) {
    runCatching {
        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
        val supported = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        )?.toList().orEmpty()
        if (supported.isEmpty()) return

        val selected = chooseExposureFriendlyFps(
            supported.map { SourceFpsRange(it.lower, it.upper) }, desiredFps
        ) ?: return // Leave CameraX's device defaults when no suitable adaptive range exists.
        val chosen = Range(selected.lower, selected.upper)

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen)
            .build()
        val request = Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options)
        request.addListener({
            runCatching { request.get() }
                .onSuccess { Log.i(TAG, "Adaptive camera FPS: $chosen (engine ceiling $desiredFps FPS)") }
                .onFailure { Log.w(TAG, "Camera rejected adaptive FPS request", it) }
        }, java.util.concurrent.Executor { it.run() })
    }.onFailure { Log.w(TAG, "Could not request camera source FPS", it) }
}

/**
 * Interpolate the latest tracking result at render cadence. MediaPipe may complete less often than
 * MAX's 60 render ticks, but the visible landmarks move toward each new result every rendered frame
 * instead of teleporting once per inference.
 */
private fun smoothTrackingFrame(
    previous: TrackingFrame?,
    target: TrackingFrame,
    alpha: Float
): TrackingFrame {
    if (target.groups.isEmpty() || previous == null || previous.mode != target.mode) return target
    if (previous.groups.size != target.groups.size || alpha >= 0.999f) return target

    val groups = target.groups.mapIndexed { groupIndex, targetGroup ->
        val previousGroup = previous.groups[groupIndex]
        if (previousGroup.size != targetGroup.size) {
            targetGroup
        } else {
            targetGroup.mapIndexed { pointIndex, point ->
                val old = previousGroup[pointIndex]
                if (old.index != point.index) {
                    point
                } else {
                    Point3(
                        x = old.x + (point.x - old.x) * alpha,
                        y = old.y + (point.y - old.y) * alpha,
                        z = old.z + (point.z - old.z) * alpha,
                        index = point.index
                    )
                }
            }
        }
    }
    return target.copy(groups = groups)
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

private const val TRACKING_SMOOTHING_ALPHA = 0.55f
private const val TAG = "FaceChangerCamera"
