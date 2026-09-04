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

private val CoreDark = Color(0xFF080C11)
private val CoreSurface = Color(0xFF121922)
private val CoreSurface2 = Color(0xFF1A2430)
private val CoreText = Color(0xFFEAF1F7)
private val CoreMuted = Color(0xFFA9B6C2)
private val CoreAccent = Color(0xFF47D7AC)
private val CoreOutline = Color(0xFF536170)

class EssentialStudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = CoreAccent,
                    onPrimary = Color(0xFF002118),
                    background = CoreDark,
                    onBackground = CoreText,
                    surface = CoreSurface,
                    onSurface = CoreText,
                    surfaceVariant = CoreSurface2,
                    onSurfaceVariant = CoreMuted,
                    outline = CoreOutline,
                    error = Color(0xFFFFB4AB),
                    onError = Color(0xFF690005)
                )
            ) { CoreStudioApp() }
        }
    }
}

private enum class CoreScreen { HOME, EDITOR, DOCS, CAMERA }

@Composable
private fun CoreStudioApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("face_changer", Context.MODE_PRIVATE) }
    var screen by remember { mutableStateOf(CoreScreen.HOME) }
    var apps by remember { mutableStateOf(loadCoreApps(prefs)) }
    var selected by remember { mutableStateOf<FilterApp?>(null) }

    when (screen) {
        CoreScreen.HOME -> CoreHome(
            apps = apps,
            onRun = { selected = it; screen = CoreScreen.CAMERA },
            onEdit = { selected = it; screen = CoreScreen.EDITOR },
            onDelete = { target ->
                apps = apps.filterNot { it.id == target.id }
                saveCoreApps(prefs, apps)
                if (selected?.id == target.id) selected = null
            },
            onAdd = { selected = null; screen = CoreScreen.EDITOR },
            onDocs = { screen = CoreScreen.DOCS }
        )
        CoreScreen.EDITOR -> CoreEditor(
            existing = selected,
            onBack = { screen = CoreScreen.HOME },
            onSave = { app ->
                apps = apps.filterNot { it.id == app.id } + app
                saveCoreApps(prefs, apps)
                screen = CoreScreen.HOME
            },
            onDocs = { screen = CoreScreen.DOCS }
        )
        CoreScreen.DOCS -> CoreDocs { screen = CoreScreen.HOME }
        CoreScreen.CAMERA -> selected?.let { app ->
            CoreCamera(app) { screen = CoreScreen.HOME }
        } ?: run { screen = CoreScreen.HOME }
    }
}

@Composable
private fun CoreHome(
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
            containerColor = CoreSurface2,
            titleContentColor = CoreText,
            textContentColor = CoreMuted,
            title = { Text("Delete ${target.name}?") },
            text = { Text("This removes the filter and its script from this device.") },
            confirmButton = {
                TextButton(onClick = { onDelete(target); pendingDelete = null }) {
                    Text("Delete", color = Color(0xFFFF9A91))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        containerColor = CoreDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Filter Studio", color = CoreText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("No premade filters — build from core primitives", color = CoreMuted)
                }
                IconButton(onClick = onDocs) { Icon(Icons.Default.MenuBook, "Docs", tint = CoreText) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                containerColor = CoreAccent,
                contentColor = Color(0xFF002118),
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add filter") }
            )
        }
    ) { pad ->
        if (apps.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(colors = CardDefaults.cardColors(containerColor = CoreSurface, contentColor = CoreText)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Code, null, tint = CoreAccent, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No filters yet", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Create one from the scripting primitives.", color = CoreMuted)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onAdd) { Text("Create filter") }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(apps, key = { it.id }) { app ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CoreSurface, contentColor = CoreText),
                        modifier = Modifier.fillMaxWidth().clickable { onRun(app) }
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).background(CoreAccent.copy(.16f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    when (app.mode) {
                                        TrackingMode.FACE -> Icons.Default.Face
                                        TrackingMode.HAND -> Icons.Default.BackHand
                                        TrackingMode.BODY -> Icons.Default.AccessibilityNew
                                    },
                                    null,
                                    tint = CoreAccent
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.name, color = CoreText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(app.mode.name, color = CoreMuted, fontSize = 12.sp)
                                Spacer(Modifier.height(5.dp))
                                Text(app.description, color = CoreMuted)
                            }
                            IconButton(onClick = { onEdit(app) }) {
                                Icon(Icons.Default.Edit, "Edit", tint = CoreText)
                            }
                            IconButton(onClick = { pendingDelete = app }) {
                                Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF9A91))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreEditor(
    existing: FilterApp?,
    onBack: () -> Unit,
    onSave: (FilterApp) -> Unit,
    onDocs: () -> Unit
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "My Filter") }
    var description by remember(existing?.id) { mutableStateOf(existing?.description ?: "Custom filter") }
    var mode by remember(existing?.id) { mutableStateOf(existing?.mode ?: TrackingMode.FACE) }
    var code by remember(existing?.id) { mutableStateOf(existing?.code ?: "# Write your filter from core primitives") }
    var error by remember { mutableStateOf<String?>(null) }
    val engine = remember { ScriptEngine() }

    Scaffold(
        containerColor = CoreDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = CoreText) }
                Text(
                    if (existing == null) "New filter" else "Edit filter",
                    color = CoreText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDocs) { Text("Docs") }
                Button(onClick = {
                    try {
                        engine.parse(code)
                        onSave(
                            FilterApp(
                                id = existing?.id ?: UUID.randomUUID().toString(),
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
                }) { Text("Save") }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CoreField(name, { name = it }, "Name")
                Spacer(Modifier.height(8.dp))
                CoreField(description, { description = it }, "Description")
            }
            item {
                Text("Tracking mode", color = CoreText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackingMode.entries.forEach { value ->
                        FilterChip(
                            selected = mode == value,
                            onClick = { mode = value },
                            label = { Text(value.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CoreSurface2,
                                labelColor = CoreText,
                                selectedContainerColor = CoreAccent.copy(.22f),
                                selectedLabelColor = CoreText
                            )
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Full MediaPipe landmarks are exposed in every mode.", color = CoreMuted, fontSize = 12.sp)
            }
            item {
                Text("Code", color = CoreText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null },
                    modifier = Modifier.fillMaxWidth().height(480.dp),
                    textStyle = LocalTextStyle.current.copy(color = CoreText, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    label = { Text("Core sandbox script") },
                    colors = coreFieldColors()
                )
                error?.let { Text(it, color = Color(0xFFFFB4AB)) }
            }
        }
    }
}

@Composable
private fun CoreField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        colors = coreFieldColors()
    )
}

@Composable
private fun coreFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = CoreText,
    unfocusedTextColor = CoreText,
    focusedLabelColor = CoreAccent,
    unfocusedLabelColor = CoreMuted,
    cursorColor = CoreAccent,
    focusedBorderColor = CoreAccent,
    unfocusedBorderColor = CoreOutline,
    focusedContainerColor = CoreSurface,
    unfocusedContainerColor = CoreSurface
)

@Composable
private fun CoreDocs(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val docs = remember { context.assets.open("SCRIPTING.md").bufferedReader().readText() }
    val lines = remember(docs) { docs.lines() }

    Scaffold(
        containerColor = CoreDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = CoreText) }
                Column(Modifier.weight(1f)) {
                    Text("Scripting docs", color = CoreText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Core primitives only", color = CoreMuted, fontSize = 11.sp)
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Filter Studio docs", docs))
                }) { Icon(Icons.Default.ContentCopy, "Copy docs", tint = CoreText) }
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
                        Text(raw.removePrefix("# "), color = CoreText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(8.dp))
                    }
                    raw.startsWith("## ") -> {
                        Spacer(Modifier.height(16.dp))
                        Text(raw.removePrefix("## "), color = CoreAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                    }
                    raw.startsWith("- ") -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("•", color = CoreAccent, fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(raw.removePrefix("- "), color = CoreText, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                    }
                    raw.length >= 2 && raw.startsWith("`") && raw.endsWith("`") -> Surface(
                        color = CoreSurface2,
                        contentColor = Color(0xFFBFEBDD),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(raw.removeSurrounding("`"), fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                    }
                    raw.isBlank() -> Spacer(Modifier.height(5.dp))
                    else -> Text(raw, color = CoreText, lineHeight = 20.sp)
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun CoreCamera(app: FilterApp, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(true) }
    val engine = remember { ScriptEngine() }
    val parseResult = remember(app.code) { runCatching { engine.parse(app.code) } }
    val program = parseResult.getOrNull()
    val values = remember(program) {
        mutableStateMapOf<String, String>().also { map ->
            program?.inputs?.forEach { map[it.name] = it.defaultValue }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && program != null) {
            FilterCameraView(front, app.mode, app.code, engine, program, values.toMap())
        } else {
            Text(
                if (!granted) "Camera permission is required" else (parseResult.exceptionOrNull()?.message ?: "Script error"),
                color = CoreText,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(.55f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(app.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${app.mode} • core sandbox", color = Color(0xFFD0D7DE), fontSize = 11.sp)
            }
            IconButton(onClick = { front = !front }) {
                Icon(Icons.Default.Cameraswitch, "Switch camera", tint = Color.White)
            }
        }

        if (program?.inputs?.isNotEmpty() == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CoreSurface.copy(.96f), contentColor = CoreText),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    program.inputs.forEach { input ->
                        if (input.type == InputType.NUMBER) {
                            val current = (values[input.name] ?: input.defaultValue).toDoubleOrNull() ?: 0.0
                            val low = input.min ?: 0.0
                            val high = input.max ?: 10.0
                            Text("${input.label}: ${"%.2f".format(current)}", color = CoreText, fontSize = 12.sp)
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
                                colors = coreFieldColors()
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun saveCoreApps(prefs: android.content.SharedPreferences, apps: List<FilterApp>) {
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

private fun loadCoreApps(prefs: android.content.SharedPreferences): List<FilterApp> = runCatching {
    val array = JSONArray(prefs.getString("apps", "[]"))
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        FilterApp(
            id = item.getString("id"),
            name = item.getString("name"),
            description = item.optString("description"),
            mode = TrackingMode.valueOf(item.getString("mode")),
            detail = DetailLevel.HIGH,
            code = item.getString("code"),
            builtIn = false
        )
    }
}.getOrDefault(emptyList())
