package com.pugplayz.facechanger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val StudioDark = Color(0xFF080C11)
private val StudioSurface = Color(0xFF121922)
private val StudioSurface2 = Color(0xFF1A2430)
private val StudioText = Color(0xFFEAF1F7)
private val StudioMuted = Color(0xFFA9B6C2)
private val StudioAccent = Color(0xFF47D7AC)
private val StudioBlue = Color(0xFF56A8FF)
private val StudioOutline = Color(0xFF536170)

class StudioMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = StudioAccent,
                    onPrimary = Color(0xFF002118),
                    primaryContainer = Color(0xFF123A31),
                    onPrimaryContainer = Color(0xFFC4F5E4),
                    secondary = StudioBlue,
                    onSecondary = Color(0xFF001D35),
                    secondaryContainer = Color(0xFF19324A),
                    onSecondaryContainer = Color(0xFFD2E8FF),
                    background = StudioDark,
                    onBackground = StudioText,
                    surface = StudioSurface,
                    onSurface = StudioText,
                    surfaceVariant = StudioSurface2,
                    onSurfaceVariant = StudioMuted,
                    outline = StudioOutline,
                    error = Color(0xFFFFB4AB),
                    onError = Color(0xFF690005),
                    errorContainer = Color(0xFF93000A),
                    onErrorContainer = Color(0xFFFFDAD6)
                )
            ) { StudioApp() }
        }
    }
}

private enum class StudioScreen { HOME, EDITOR, DOCS, CAMERA }

@Composable
private fun StudioApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("face_changer", Context.MODE_PRIVATE) }
    var screen by remember { mutableStateOf(StudioScreen.HOME) }
    var customApps by remember { mutableStateOf(loadStudioApps(prefs)) }
    var selected by remember { mutableStateOf<FilterApp?>(null) }

    when (screen) {
        StudioScreen.HOME -> StudioHome(
            apps = studioBuiltIns() + customApps,
            onRun = { selected = it; screen = StudioScreen.CAMERA },
            onEdit = { selected = it; screen = StudioScreen.EDITOR },
            onDelete = { target ->
                customApps = customApps.filterNot { it.id == target.id }
                saveStudioApps(prefs, customApps)
                if (selected?.id == target.id) selected = null
            },
            onAdd = { selected = null; screen = StudioScreen.EDITOR },
            onDocs = { screen = StudioScreen.DOCS }
        )
        StudioScreen.EDITOR -> StudioEditor(
            existing = selected,
            onBack = { screen = StudioScreen.HOME },
            onSave = { app ->
                customApps = customApps.filterNot { it.id == app.id } + app
                saveStudioApps(prefs, customApps)
                screen = StudioScreen.HOME
            },
            onDocs = { screen = StudioScreen.DOCS }
        )
        StudioScreen.DOCS -> StudioDocs { screen = StudioScreen.HOME }
        StudioScreen.CAMERA -> selected?.let { app ->
            StudioCamera(app) { screen = StudioScreen.HOME }
        } ?: run { screen = StudioScreen.HOME }
    }
}

@Composable
private fun StudioHome(
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
            containerColor = StudioSurface2,
            titleContentColor = StudioText,
            textContentColor = StudioMuted,
            title = { Text("Delete ${target.name}?") },
            text = { Text("This removes the saved filter and its code from this device.") },
            confirmButton = {
                TextButton(onClick = { onDelete(target); pendingDelete = null }) { Text("Delete", color = Color(0xFFFF9A91)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        containerColor = StudioDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Filter Studio", color = StudioText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("MediaPipe effects with full landmark sets", color = StudioMuted)
                }
                IconButton(onClick = onDocs) { Icon(Icons.Default.MenuBook, "Docs", tint = StudioText) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                containerColor = StudioAccent,
                contentColor = Color(0xFF002118),
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
                    colors = CardDefaults.cardColors(containerColor = StudioSurface, contentColor = StudioText),
                    modifier = Modifier.fillMaxWidth().clickable { onRun(app) }
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).background(
                                    if (app.builtIn) StudioBlue.copy(.16f) else StudioAccent.copy(.16f),
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
                                    tint = if (app.builtIn) StudioBlue else StudioAccent
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.name, color = StudioText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    if (app.builtIn) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(color = StudioBlue.copy(.14f), shape = RoundedCornerShape(50)) {
                                            Text("BUILT-IN", color = StudioBlue, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                                        }
                                    }
                                }
                                Text(app.mode.name, color = StudioMuted, fontSize = 12.sp)
                            }
                            IconButton(onClick = { onEdit(app) }) {
                                Icon(if (app.builtIn) Icons.Default.Code else Icons.Default.Edit, if (app.builtIn) "View source" else "Edit", tint = StudioText)
                            }
                            if (!app.builtIn) {
                                IconButton(onClick = { pendingDelete = app }) {
                                    Icon(Icons.Default.Delete, "Delete filter", tint = Color(0xFFFF9A91))
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(app.description, color = StudioMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioEditor(
    existing: FilterApp?,
    onBack: () -> Unit,
    onSave: (FilterApp) -> Unit,
    onDocs: () -> Unit
) {
    val isFork = existing?.builtIn == true
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "My Filter") }
    var description by remember(existing?.id) { mutableStateOf(existing?.description ?: "Custom filter") }
    var mode by remember(existing?.id) { mutableStateOf(existing?.mode ?: TrackingMode.FACE) }
    var code by remember(existing?.id) {
        mutableStateOf(existing?.code ?: defaultStudioScript())
    }
    var error by remember { mutableStateOf<String?>(null) }
    val engine = remember { ScriptEngine() }

    Scaffold(
        containerColor = StudioDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = StudioText) }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            isFork -> "Built-in source"
                            existing != null -> "Edit app"
                            else -> "New app"
                        },
                        color = StudioText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    if (isFork) Text("Save creates your own editable copy", color = StudioMuted, fontSize = 11.sp)
                }
                TextButton(onClick = onDocs) { Text("Docs") }
                Button(onClick = {
                    try {
                        engine.parse(code)
                        onSave(
                            FilterApp(
                                id = if (isFork || existing == null) UUID.randomUUID().toString() else existing.id,
                                name = name,
                                description = description,
                                mode = mode,
                                detail = DetailLevel.HIGH,
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
                StudioField(name, { name = it }, "Name")
                Spacer(Modifier.height(8.dp))
                StudioField(description, { description = it }, "Description")
            }
            item {
                Text("Tracking mode", color = StudioText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackingMode.entries.forEach { value ->
                        FilterChip(
                            selected = mode == value,
                            onClick = { mode = value },
                            label = { Text(value.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = StudioSurface2,
                                labelColor = StudioText,
                                selectedContainerColor = StudioAccent.copy(.22f),
                                selectedLabelColor = StudioText
                            )
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("All MediaPipe landmarks are always exposed. There is no quality/LOD setting.", color = StudioMuted, fontSize = 12.sp)
            }
            item {
                Text("Code", color = StudioText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null },
                    modifier = Modifier.fillMaxWidth().height(430.dp),
                    textStyle = LocalTextStyle.current.copy(color = StudioText, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    label = { Text("Sandboxed filter script") },
                    colors = studioFieldColors()
                )
                error?.let { Text(it, color = Color(0xFFFFB4AB)) }
            }
        }
    }
}

@Composable
private fun StudioField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        colors = studioFieldColors()
    )
}

@Composable
private fun studioFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = StudioText,
    unfocusedTextColor = StudioText,
    focusedLabelColor = StudioAccent,
    unfocusedLabelColor = StudioMuted,
    cursorColor = StudioAccent,
    focusedBorderColor = StudioAccent,
    unfocusedBorderColor = StudioOutline,
    focusedContainerColor = StudioSurface,
    unfocusedContainerColor = StudioSurface
)

@Composable
private fun StudioDocs(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val docs = remember { context.assets.open("SCRIPTING.md").bufferedReader().readText() }
    val lines = remember(docs) { docs.lines() }

    Scaffold(
        containerColor = StudioDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = StudioText) }
                Column(Modifier.weight(1f)) {
                    Text("Scripting docs", color = StudioText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Bundled directly from SCRIPTING.md", color = StudioMuted, fontSize = 11.sp)
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Face Changer docs", docs))
                }) { Icon(Icons.Default.ContentCopy, "Copy docs", tint = StudioText) }
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
                        Spacer(Modifier.height(8.dp)); Text(raw.removePrefix("# "), color = StudioText, fontSize = 28.sp, fontWeight = FontWeight.Black); Spacer(Modifier.height(8.dp))
                    }
                    raw.startsWith("## ") -> {
                        Spacer(Modifier.height(16.dp)); Text(raw.removePrefix("## "), color = StudioAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp))
                    }
                    raw.startsWith("- ") -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("•", color = StudioAccent, fontSize = 18.sp); Spacer(Modifier.width(8.dp)); Text(raw.removePrefix("- "), color = StudioText, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                    }
                    raw.length >= 2 && raw.startsWith("`") && raw.endsWith("`") -> Surface(
                        color = StudioSurface2,
                        contentColor = Color(0xFFBFEBDD),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) { Text(raw.removeSurrounding("`"), fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
                    raw.isBlank() -> Spacer(Modifier.height(5.dp))
                    else -> Text(raw, color = StudioText, lineHeight = 20.sp)
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun StudioCamera(app: FilterApp, onBack: () -> Unit) {
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
            FilterCameraView(front, app.mode, app.code, engine, program, values.toMap())
        } else {
            Text(if (!granted) "Camera permission is required" else "Script error", color = StudioText, modifier = Modifier.align(Alignment.Center))
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(.55f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(app.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${app.mode} • full landmarks", color = Color(0xFFD0D7DE), fontSize = 11.sp)
            }
            IconButton(onClick = { front = !front }) { Icon(Icons.Default.Cameraswitch, "Switch camera", tint = Color.White) }
        }

        if (program?.inputs?.isNotEmpty() == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface.copy(.96f), contentColor = StudioText),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    program.inputs.forEach { input ->
                        if (input.type == InputType.NUMBER) {
                            val current = (values[input.name] ?: input.defaultValue).toDoubleOrNull() ?: 0.0
                            val low = input.min ?: 0.0
                            val high = input.max ?: 10.0
                            Text("${input.label}: ${"%.2f".format(current)}", color = StudioText, fontSize = 12.sp)
                            Slider(
                                value = current.toFloat().coerceIn(low.toFloat(), high.toFloat()),
                                onValueChange = { values[input.name] = it.toString() },
                                valueRange = low.toFloat()..high.toFloat()
                            )
                        } else {
                            OutlinedTextField(
                                value = values[input.name] ?: "",
                                onValueChange = { values[input.name] = it },
                                label = { Text(input.label) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = studioFieldColors()
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun defaultStudioScript() = """if tracked
connections #47D7AC 3
dots #56A8FF 3
end"""

private fun studioBuiltIns() = listOf(
    FilterApp("builtin-face", "Face Mesh", "Face contour, eyes and lips drawn from the complete MediaPipe face landmark set.", TrackingMode.FACE, DetailLevel.HIGH, "connections #56A8FF 2\ndots #47D7AC 2", true),
    FilterApp("builtin-hand", "Hand Skeleton", "Tracks up to two hands with the complete hand landmark set.", TrackingMode.HAND, DetailLevel.HIGH, "connections #47D7AC 4\ndots #56A8FF 4", true),
    FilterApp("builtin-body", "Body Skeleton", "Full-body pose landmarks with standard shoulder, limb and torso connections.", TrackingMode.BODY, DetailLevel.HIGH, "connections #56A8FF 4\ndots #47D7AC 4", true)
)

private fun saveStudioApps(prefs: android.content.SharedPreferences, apps: List<FilterApp>) {
    val array = JSONArray()
    apps.forEach { app ->
        array.put(JSONObject().apply {
            put("id", app.id)
            put("name", app.name)
            put("description", app.description)
            put("mode", app.mode.name)
            put("detail", DetailLevel.HIGH.name)
            put("code", app.code)
        })
    }
    prefs.edit().putString("apps", array.toString()).apply()
}

private fun loadStudioApps(prefs: android.content.SharedPreferences): List<FilterApp> = runCatching {
    val array = JSONArray(prefs.getString("apps", "[]"))
    (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        FilterApp(
            item.getString("id"),
            item.getString("name"),
            item.optString("description"),
            TrackingMode.valueOf(item.getString("mode")),
            DetailLevel.HIGH,
            item.getString("code"),
            false
        )
    }
}.getOrDefault(emptyList())
