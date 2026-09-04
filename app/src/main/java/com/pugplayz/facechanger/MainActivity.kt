package com.pugplayz.facechanger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

private const val SIGN_IN_KEY = "I am super cool 27"

private val Dark = Color(0xFF090D12)
private val Surface = Color(0xFF121923)
private val Surface2 = Color(0xFF1A2431)
private val Accent = Color(0xFF47D7AC)
private val Blue = Color(0xFF56A8FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    secondary = Blue,
                    background = Dark,
                    surface = Surface,
                    onBackground = Color(0xFFEAF1F7),
                    onSurface = Color(0xFFEAF1F7)
                )
            ) { FaceChangerApp() }
        }
    }
}

private enum class Screen { HOME, EDITOR, DOCS, CAMERA }

@Composable
private fun FaceChangerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("face_changer", Context.MODE_PRIVATE) }
    var signedIn by remember { mutableStateOf(prefs.getBoolean("signed_in", false)) }

    if (!signedIn) {
        SignInScreen { key ->
            if (key == SIGN_IN_KEY) {
                prefs.edit().putBoolean("signed_in", true).apply()
                signedIn = true
                true
            } else false
        }
        return
    }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var apps by remember { mutableStateOf(loadApps(prefs)) }
    var selected by remember { mutableStateOf<FilterApp?>(null) }

    when (screen) {
        Screen.HOME -> HomeScreen(
            apps = builtIns() + apps,
            onRun = { selected = it; screen = Screen.CAMERA },
            onEdit = { selected = it; screen = Screen.EDITOR },
            onAdd = { selected = null; screen = Screen.EDITOR },
            onDocs = { screen = Screen.DOCS },
            onSignOut = {
                prefs.edit().putBoolean("signed_in", false).apply()
                signedIn = false
            }
        )

        Screen.EDITOR -> EditorScreen(
            existing = selected,
            onBack = { screen = Screen.HOME },
            onSave = { app ->
                apps = apps.filterNot { it.id == app.id } + app
                saveApps(prefs, apps)
                screen = Screen.HOME
            },
            onDocs = { screen = Screen.DOCS }
        )

        Screen.DOCS -> DocsScreen { screen = Screen.HOME }
        Screen.CAMERA -> selected?.let { CameraScreen(it) { screen = Screen.HOME } }
            ?: run { screen = Screen.HOME }
    }
}

@Composable
private fun SignInScreen(onTry: (String) -> Boolean) {
    var key by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Dark).padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    Modifier.size(58.dp).background(Accent.copy(alpha = .14f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(32.dp))
                }
                Text("Face Changer Custom", fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("Open-source MediaPipe filter lab", color = Color(0xFF94A3AF))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; bad = false },
                    label = { Text("Sign-in key") },
                    isError = bad,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { bad = !onTry(key) },
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) { Text("Enter studio") }
                if (bad) Text("That key doesn't match.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    apps: List<FilterApp>,
    onRun: (FilterApp) -> Unit,
    onEdit: (FilterApp) -> Unit,
    onAdd: () -> Unit,
    onDocs: () -> Unit,
    onSignOut: () -> Unit
) {
    Scaffold(
        containerColor = Dark,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Filter Studio", fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("MediaPipe + your code", color = Color(0xFF8E9AA6))
                }
                IconButton(onClick = onDocs) { Icon(Icons.Default.MenuBook, "Docs") }
                IconButton(onClick = onSignOut) { Icon(Icons.Default.Logout, "Sign out") }
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
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    modifier = Modifier.fillMaxWidth().clickable { onRun(app) }
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).background(
                                    if (app.builtIn) Blue.copy(.16f) else Accent.copy(.16f),
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
                                    tint = if (app.builtIn) Blue else Accent
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    if (app.builtIn) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            color = Blue.copy(.14f),
                                            shape = RoundedCornerShape(50)
                                        ) { Text("BUILT-IN", color = Blue, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)) }
                                    }
                                }
                                Text("${app.mode} • ${app.detail}", color = Color(0xFF8E9AA6), fontSize = 12.sp)
                            }
                            IconButton(onClick = { onEdit(app) }) {
                                Icon(if (app.builtIn) Icons.Default.Code else Icons.Default.Edit, if (app.builtIn) "View source" else "Edit")
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
private fun EditorScreen(
    existing: FilterApp?,
    onBack: () -> Unit,
    onSave: (FilterApp) -> Unit,
    onDocs: () -> Unit
) {
    val isFork = existing?.builtIn == true
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "My Filter") }
    var desc by remember(existing?.id) { mutableStateOf(existing?.description ?: "Custom filter") }
    var mode by remember(existing?.id) { mutableStateOf(existing?.mode ?: TrackingMode.FACE) }
    var detail by remember(existing?.id) { mutableStateOf(existing?.detail ?: DetailLevel.HIGH) }
    var code by remember(existing?.id) {
        mutableStateOf(
            existing?.code ?: "input number strength Eye_Size 1.8 0.5 3.0\nlet pulse = strength+sin(time*5)*0.12\nif point_exists(0,33) > 0\n  magnify 0 33 pulse 0.10\n  connections #47D7AC 2\nend"
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    val engine = remember { ScriptEngine() }

    Scaffold(
        containerColor = Dark,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    TrackingMode.entries.forEach {
                        FilterChip(selected = mode == it, onClick = { mode = it }, label = { Text(it.name) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Detail", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailLevel.entries.forEach {
                        FilterChip(selected = detail == it, onClick = { detail = it }, label = { Text(it.name) })
                    }
                }
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
private fun DocsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val docs = remember { context.assets.open("SCRIPTING.md").bufferedReader().readText() }
    val lines = remember(docs) { docs.lines() }

    Scaffold(
        containerColor = Dark,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                        Text(raw.removePrefix("# "), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                    }
                    raw.startsWith("## ") -> {
                        Spacer(Modifier.height(16.dp))
                        Text(raw.removePrefix("## "), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Accent)
                        Spacer(Modifier.height(4.dp))
                    }
                    raw.startsWith("- ") -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("•", color = Accent, fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(raw.removePrefix("- "), color = Color(0xFFD1DAE2), lineHeight = 20.sp, modifier = Modifier.weight(1f))
                    }
                    raw.length >= 2 && raw.startsWith("`") && raw.endsWith("`") -> Surface(
                        color = Surface2,
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
private fun CameraScreen(app: FilterApp, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(true) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    val engine = remember { ScriptEngine() }
    val program = remember(app.code) { runCatching { engine.parse(app.code) }.getOrNull() }
    val values = remember(program) {
        mutableStateMapOf<String, String>().also { map -> program?.inputs?.forEach { map[it.name] = it.defaultValue } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && program != null) {
            CameraFeed(front, app, engine, program, values) { frame = it }
        } else {
            Text(
                if (!granted) "Camera permission is required" else "Script error",
                modifier = Modifier.align(Alignment.Center)
            )
        }

        frame?.let {
            androidx.compose.foundation.Image(
                it.asImageBitmap(),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(.48f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            Column(Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.Bold)
                Text("${app.mode} • ${app.detail}", fontSize = 11.sp, color = Color.LightGray)
            }
            IconButton(onClick = { front = !front }) { Icon(Icons.Default.Cameraswitch, "Switch camera") }
        }

        if (program?.inputs?.isNotEmpty() == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface.copy(.93f)),
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
private fun CameraFeed(
    front: Boolean,
    app: FilterApp,
    engine: ScriptEngine,
    program: ScriptEngine.Program,
    values: Map<String, String>,
    onFrame: (Bitmap) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(front, app.id) {
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val tracker = TrackingEngine(context)
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                try {
                    var bitmap = proxy.toBitmap()
                    bitmap = rotateBitmap(bitmap, proxy.imageInfo.rotationDegrees, front)
                    val tracking = tracker.detect(bitmap, app.mode, app.detail)
                    val rendered = engine.render(bitmap, tracking, program, values.toMap())
                    mainExecutor.execute { onFrame(rendered) }
                    if (rendered !== bitmap) bitmap.recycle()
                } catch (_: Throwable) {
                    // Keep the camera stream alive if one frame or custom expression fails.
                } finally {
                    proxy.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycle,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                analysis
            )
        }, mainExecutor)

        onDispose {
            runCatching { future.get().unbindAll() }
            tracker.close()
            executor.shutdownNow()
        }
    }
}

private fun rotateBitmap(source: Bitmap, degrees: Int, mirror: Boolean): Bitmap {
    val matrix = Matrix()
    if (degrees != 0) matrix.postRotate(degrees.toFloat())
    if (mirror) matrix.postScale(-1f, 1f)
    val out = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (out !== source) source.recycle()
    return out
}

private fun builtIns() = listOf(
    FilterApp(
        "builtin-face",
        "Face Mesh",
        "High-detail face contour, eyes and lip connections. Open source to see the exact filter script.",
        TrackingMode.FACE,
        DetailLevel.HIGH,
        "connections #56A8FF 2\ndots #47D7AC 2",
        true
    ),
    FilterApp(
        "builtin-hand",
        "Hand Skeleton",
        "Tracks up to two hands and draws the standard hand-bone connections.",
        TrackingMode.HAND,
        DetailLevel.HIGH,
        "connections #47D7AC 4\ndots #56A8FF 4",
        true
    ),
    FilterApp(
        "builtin-body",
        "Body Skeleton",
        "Full-body pose landmarks with standard shoulder, limb and torso connections.",
        TrackingMode.BODY,
        DetailLevel.HIGH,
        "connections #56A8FF 4\ndots #47D7AC 4",
        true
    )
)

private fun saveApps(prefs: android.content.SharedPreferences, apps: List<FilterApp>) {
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

private fun loadApps(prefs: android.content.SharedPreferences): List<FilterApp> = runCatching {
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
