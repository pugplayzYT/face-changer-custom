package com.pugplayz.facechanger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val SmoothDark = Color(0xFF090D12)
private val SmoothSurface = Color(0xFF121923)
private val SmoothSurface2 = Color(0xFF1A2431)
private val SmoothAccent = Color(0xFF47D7AC)
private val SmoothBlue = Color(0xFF56A8FF)

/**
 * Launcher activity using a real CameraX PreviewView for the visible camera feed.
 *
 * MediaPipe analysis is completely decoupled from preview rendering. Drawing-only scripts render
 * into a transparent overlay, so the camera remains as smooth as CameraX preview even when
 * tracking cannot keep up with every camera frame. Only scripts that truly need source pixels
 * (currently magnify/pixelate) fall back to showing processed analysis frames.
 */
class SmoothMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = SmoothAccent,
                    secondary = SmoothBlue,
                    background = SmoothDark,
                    surface = SmoothSurface,
                    onBackground = Color(0xFFEAF1F7),
                    onSurface = Color(0xFFEAF1F7)
                )
            ) { SmoothFaceChangerApp() }
        }
    }
}

private enum class SmoothScreen { HOME, EDITOR, DOCS, CAMERA }

@Composable
private fun SmoothFaceChangerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("face_changer", Context.MODE_PRIVATE) }
    var screen by remember { mutableStateOf(SmoothScreen.HOME) }
    var apps by remember { mutableStateOf(loadSmoothApps(prefs)) }
    var selected by remember { mutableStateOf<FilterApp?>(null) }

    when (screen) {
        SmoothScreen.HOME -> SmoothHomeScreen(
            apps = smoothBuiltIns() + apps,
            onRun = { selected = it; screen = SmoothScreen.CAMERA },
            onEdit = { selected = it; screen = SmoothScreen.EDITOR },
            onDelete = { target ->
                apps = apps.filterNot { it.id == target.id }
                saveSmoothApps(prefs, apps)
                if (selected?.id == target.id) selected = null
            },
            onAdd = { selected = null; screen = SmoothScreen.EDITOR },
            onDocs = { screen = SmoothScreen.DOCS }
        )

        SmoothScreen.EDITOR -> SmoothEditorScreen(
            existing = selected,
            onBack = { screen = SmoothScreen.HOME },
            onSave = { app ->
                apps = apps.filterNot { it.id == app.id } + app
                saveSmoothApps(prefs, apps)
                screen = SmoothScreen.HOME
            },
            onDocs = { screen = SmoothScreen.DOCS }
        )

        SmoothScreen.DOCS -> SmoothDocsScreen { screen = SmoothScreen.HOME }
        SmoothScreen.CAMERA -> selected?.let { SmoothCameraScreen(it) { screen = SmoothScreen.HOME } }
            ?: run { screen = SmoothScreen.HOME }
    }
}

@Composable
private fun SmoothHomeScreen(
    apps: List<FilterApp>,
    onRun: (FilterApp) -> Unit,
    onEdit: (FilterApp) -> Unit,
    onDelete: (FilterApp) -> Unit,
    onAdd: () -> Unit,
    onDocs: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<FilterApp?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("This removes the saved filter and its code from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        containerColor = SmoothDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Filter Studio", fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("Smooth CameraX preview + MediaPipe", color = Color(0xFF8E9AA6))
                }
                IconButton(onClick = onDocs) { Icon(Icons.Default.MenuBook, "Docs") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add app") }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(apps, key = { it.id }) { app ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SmoothSurface),
                    modifier = Modifier.fillMaxWidth().clickable { onRun(app) }
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).background(
                                    if (app.builtIn) SmoothBlue.copy(.16f) else SmoothAccent.copy(.16f),
                                    RoundedCornerShape(14.dp)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    when (app.mode) {
                                        TrackingMode.FACE -> Icons.Default.Face
                                        TrackingMode.HAND -> Icons.Default.BackHand
                                        TrackingMode.BODY -> Icons.Default.AccessibilityNew
                                    },
                                    null,
                                    tint = if (app.builtIn) SmoothBlue else SmoothAccent
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    if (app.builtIn) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(color = SmoothBlue.copy(.14f), shape = RoundedCornerShape(50)) {
                                            Text(
                                                "BUILT-IN",
                                                color = SmoothBlue,
                                                fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                                Text("${app.mode} • ${app.detail}", color = Color(0xFF8E9AA6), fontSize = 12.sp)
                            }
                            IconButton(onClick = { onEdit(app) }) {
                                Icon(
                                    if (app.builtIn) Icons.Default.Code else Icons.Default.Edit,
                                    if (app.builtIn) "View source" else "Edit"
                                )
                            }
                            if (!app.builtIn) {
                                IconButton(onClick = { pendingDelete = app }) {
                                    Icon(Icons.Default.Delete, "Delete filter", tint = Color(0xFFFF8A80))
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(app.description, color = Color(0xFFB8C3CD))
                    }
                }
            }
        }
    }
}

@Composable
private fun SmoothEditorScreen(
    existing: FilterApp?,
    onBack: () -> Unit,
    onSave: (FilterApp) -> Unit,
    onDocs: () -> Unit
) {
    val isFork = existing?.builtIn == true
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "My Filter") }
    var desc by remember(existing?.id) { mutableStateOf(existing?.description ?: "Custom filter") }
    var mode by remember(existing?.id) { mutableStateOf(existing?.mode ?: TrackingMode.FACE) }
    var detail by remember(existing?.id) { mutableStateOf(existing?.detail ?: DetailLevel.MEDIUM) }
    var code by remember(existing?.id) {
        mutableStateOf(
            existing?.code
                ?: "input number strength Eye_Size 1.8 0.5 3.0\nlet pulse = strength+sin(time*5)*0.12\nif point_exists(0,33) > 0\n  magnify 0 33 pulse 0.10\n  connections #47D7AC 2\nend"
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    val engine = remember { ScriptEngine() }

    Scaffold(
        containerColor = SmoothDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            isFork -> "Built-in source"
                            existing != null -> "Edit app"
                            else -> "New app"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    if (isFork) Text("Save creates your own editable copy", color = Color(0xFF8E9AA6), fontSize = 11.sp)
                }
                TextButton(onClick = onDocs) { Text("Docs") }
                Button(onClick = {
                    try {
                        engine.parse(code)
                        onSave(
                            FilterApp(
                                id = if (isFork || existing == null) UUID.randomUUID().toString() else existing.id,
                                name = name,
                                description = desc,
                                mode = mode,
                                detail = detail,
                                code = code,
                                builtIn = false
                            )
                        )
                    } catch (t: Throwable) {
                        error = t.message ?: "Script parse error"
                    }
                }) { Text(if (isFork) "Save copy" else "Save") }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text("Tracking mode", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackingMode.entries.forEach { value ->
                        FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(value.name) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Detail / analysis workload", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailLevel.entries.forEach { value ->
                        FilterChip(selected = detail == value, onClick = { detail = value }, label = { Text(value.name) })
                    }
                }
                Text(
                    when (detail) {
                        DetailLevel.LOW -> "180p analysis • live preview stays full-speed"
                        DetailLevel.MEDIUM -> "360p analysis • balanced"
                        DetailLevel.HIGH -> "540p analysis • most tracking detail"
                    },
                    color = Color(0xFF8E9AA6),
                    fontSize = 12.sp
                )
            }
            item {
                Text("Code", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null },
                    modifier = Modifier.fillMaxWidth().height(430.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    label = { Text("Sandboxed filter script") }
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun SmoothDocsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val docs = remember { context.assets.open("SCRIPTING.md").bufferedReader().readText() }
    val lines = remember(docs) { docs.lines() }

    Scaffold(
        containerColor = SmoothDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                Column(Modifier.weight(1f)) {
                    Text("Scripting docs", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Bundled directly from SCRIPTING.md", color = Color(0xFF8E9AA6), fontSize = 11.sp)
                }
                IconButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Face Changer docs", docs))
                }) { Icon(Icons.Default.ContentCopy, "Copy docs") }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(lines) { raw ->
                when {
                    raw.startsWith("# ") -> {
                        Spacer(Modifier.height(8.dp))
                        Text(raw.removePrefix("# "), fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(8.dp))
                    }
                    raw.startsWith("## ") -> {
                        Spacer(Modifier.height(16.dp))
                        Text(raw.removePrefix("## "), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SmoothAccent)
                        Spacer(Modifier.height(4.dp))
                    }
                    raw.startsWith("- ") -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("•", color = SmoothAccent, fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(raw.removePrefix("- "), color = Color(0xFFD1DAE2), lineHeight = 20.sp, modifier = Modifier.weight(1f))
                    }
                    raw.length >= 2 && raw.startsWith("`") && raw.endsWith("`") -> Surface(
                        color = SmoothSurface2,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(
                            raw.removeSurrounding("`"),
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFBFEBDD),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    raw.isBlank() -> Spacer(Modifier.height(5.dp))
                    else -> Text(raw, color = Color(0xFFD1DAE2), lineHeight = 20.sp)
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SmoothCameraScreen(app: FilterApp, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(true) }
    val engine = remember { ScriptEngine() }
    val program = remember(app.code) { runCatching { engine.parse(app.code) }.getOrNull() }
    val values = remember(program) {
        mutableStateMapOf<String, String>().also { map -> program?.inputs?.forEach { map[it.name] = it.defaultValue } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && program != null) {
            SmoothCameraFeed(
                front = front,
                app = app,
                engine = engine,
                program = program,
                values = values.toMap()
            )
        } else {
            Text(
                if (!granted) "Camera permission is required" else "Script error",
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(.48f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            Column(Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.Bold)
                Text(
                    "${app.mode} • ${app.detail} • live preview",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
            }
            IconButton(onClick = { front = !front }) { Icon(Icons.Default.Cameraswitch, "Switch camera") }
        }

        if (program?.inputs?.isNotEmpty() == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SmoothSurface.copy(.93f)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    program.inputs.forEach { input ->
                        if (input.type == InputType.NUMBER) {
                            val v = (values[input.name] ?: input.defaultValue).toDoubleOrNull() ?: 0.0
                            val lo = input.min ?: 0.0
                            val hi = input.max ?: 10.0
                            Text("${input.label}: ${"%.2f".format(v)}", fontSize = 12.sp)
                            Slider(
                                value = v.toFloat().coerceIn(lo.toFloat(), hi.toFloat()),
                                onValueChange = { values[input.name] = it.toString() },
                                valueRange = lo.toFloat()..hi.toFloat()
                            )
                        } else {
                            OutlinedTextField(
                                values[input.name] ?: "",
                                { values[input.name] = it },
                                label = { Text(input.label) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmoothCameraFeed(
    front: Boolean,
    app: FilterApp,
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

    DisposableEffect(front, app.id, app.detail, app.code, lifecycle) {
        val worker = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val tracker = TrackingEngine(context.applicationContext)
        val active = AtomicBoolean(true)
        val uiFramePending = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var displayed: Bitmap? = null

        val needsSourcePixels = scriptNeedsSourcePixels(app.code)
        val targetSize = when (app.detail) {
            DetailLevel.LOW -> android.util.Size(320, 180)
            DetailLevel.MEDIUM -> android.util.Size(640, 360)
            DetailLevel.HIGH -> android.util.Size(960, 540)
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active.get()) return@addListener
            val cameraProvider = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val localAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(targetSize)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis = localAnalysis

            localAnalysis.setAnalyzer(worker) { proxy ->
                if (!active.get()) {
                    proxy.close()
                    return@setAnalyzer
                }

                var rotated: Bitmap? = null
                var output: Bitmap? = null
                try {
                    val raw = proxy.toBitmap()
                    rotated = rotateSmoothBitmap(raw, proxy.imageInfo.rotationDegrees, front)
                    val tracking = tracker.detect(rotated, app.mode, app.detail)

                    output = if (needsSourcePixels) {
                        engine.render(rotated, tracking, program, latestValues.get())
                    } else {
                        val transparent = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
                        transparent.eraseColor(android.graphics.Color.TRANSPARENT)
                        try {
                            engine.render(transparent, tracking, program, latestValues.get())
                        } finally {
                            if (!transparent.isRecycled) transparent.recycle()
                        }
                    }

                    val frameToPost = output
                    if (active.get() && uiFramePending.compareAndSet(false, true)) {
                        mainExecutor.execute {
                            try {
                                if (active.get() && frameToPost != null && !frameToPost.isRecycled) {
                                    val old = displayed
                                    effectView.setImageBitmap(frameToPost)
                                    displayed = frameToPost
                                    if (old != null && old !== frameToPost && !old.isRecycled) old.recycle()
                                } else if (frameToPost != null && !frameToPost.isRecycled) {
                                    frameToPost.recycle()
                                }
                            } finally {
                                uiFramePending.set(false)
                            }
                        }
                        output = null
                    }
                } catch (_: Throwable) {
                    // A broken camera frame or user script is isolated to this analyzer iteration.
                } finally {
                    if (output != null && !output!!.isRecycled) output!!.recycle()
                    if (rotated != null && !rotated!!.isRecycled) rotated!!.recycle()
                    proxy.close()
                }
            }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycle,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    localAnalysis
                )
            }
        }, mainExecutor)

        onDispose {
            active.set(false)
            runCatching { analysis?.clearAnalyzer() }
            runCatching { provider?.unbindAll() }
            worker.shutdown()
            runCatching { worker.awaitTermination(400, TimeUnit.MILLISECONDS) }
            if (!worker.isTerminated) worker.shutdownNow()
            runCatching { tracker.close() }
            effectView.setImageDrawable(null)
            displayed?.let { if (!it.isRecycled) it.recycle() }
            displayed = null
        }
    }
}

private fun scriptNeedsSourcePixels(code: String): Boolean =
    Regex("(?im)^\\s*(magnify|pixelate)\\b").containsMatchIn(code)

private fun rotateSmoothBitmap(source: Bitmap, degrees: Int, mirror: Boolean): Bitmap {
    if (degrees == 0 && !mirror) return source
    val matrix = Matrix()
    if (degrees != 0) matrix.postRotate(degrees.toFloat())
    if (mirror) matrix.postScale(-1f, 1f)
    val out = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (out !== source && !source.isRecycled) source.recycle()
    return out
}

private fun smoothBuiltIns() = listOf(
    FilterApp(
        "builtin-face",
        "Face Mesh",
        "Face contour, eyes and lip connections. Camera preview stays native-smooth while tracking draws on top.",
        TrackingMode.FACE,
        DetailLevel.MEDIUM,
        "connections #56A8FF 2\ndots #47D7AC 2",
        true
    ),
    FilterApp(
        "builtin-hand",
        "Hand Skeleton",
        "Tracks up to two hands and draws the standard hand-bone connections over the live preview.",
        TrackingMode.HAND,
        DetailLevel.MEDIUM,
        "connections #47D7AC 4\ndots #56A8FF 4",
        true
    ),
    FilterApp(
        "builtin-body",
        "Body Skeleton",
        "Full-body pose landmarks with standard shoulder, limb and torso connections.",
        TrackingMode.BODY,
        DetailLevel.MEDIUM,
        "connections #56A8FF 4\ndots #47D7AC 4",
        true
    )
)

private fun saveSmoothApps(prefs: android.content.SharedPreferences, apps: List<FilterApp>) {
    val array = JSONArray()
    apps.forEach { app ->
        array.put(JSONObject().apply {
            put("id", app.id)
            put("name", app.name)
            put("description", app.description)
            put("mode", app.mode.name)
            put("detail", app.detail.name)
            put("code", app.code)
        })
    }
    prefs.edit().putString("apps", array.toString()).apply()
}

private fun loadSmoothApps(prefs: android.content.SharedPreferences): List<FilterApp> = runCatching {
    val array = JSONArray(prefs.getString("apps", "[]"))
    (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        FilterApp(
            o.getString("id"),
            o.getString("name"),
            o.optString("description"),
            TrackingMode.valueOf(o.getString("mode")),
            DetailLevel.valueOf(o.getString("detail")),
            o.getString("code"),
            false
        )
    }
}.getOrDefault(emptyList())
