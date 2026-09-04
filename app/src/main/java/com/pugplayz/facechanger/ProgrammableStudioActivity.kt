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

private val AppDark = Color(0xFF080C11)
private val AppSurface = Color(0xFF121922)
private val AppSurface2 = Color(0xFF1A2430)
private val AppText = Color(0xFFEAF1F7)
private val AppMuted = Color(0xFFA9B6C2)
private val AppAccent = Color(0xFF47D7AC)
private val AppBlue = Color(0xFF56A8FF)
private val AppOutline = Color(0xFF536170)

/**
 * Studio UI with premade example filters restored.
 *
 * Important: the premades are NOT privileged engine effects. Their source is ordinary sandbox
 * code built from fn/if/repeat/write_pixel/math/landmarks, exactly like a user-created filter.
 */
class ProgrammableStudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AppAccent,
                    onPrimary = Color(0xFF002118),
                    background = AppDark,
                    onBackground = AppText,
                    surface = AppSurface,
                    onSurface = AppText,
                    surfaceVariant = AppSurface2,
                    onSurfaceVariant = AppMuted,
                    outline = AppOutline,
                    error = Color(0xFFFFB4AB),
                    onError = Color(0xFF690005)
                )
            ) { StudioRoot() }
        }
    }
}

private enum class Screen { HOME, EDITOR, DOCS, CAMERA }

@Composable
private fun StudioRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("face_changer", Context.MODE_PRIVATE) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var customApps by remember { mutableStateOf(loadApps(prefs)) }
    var selected by remember { mutableStateOf<FilterApp?>(null) }

    when (screen) {
        Screen.HOME -> HomeScreen(
            apps = premadeFilters() + customApps,
            onRun = { selected = it; screen = Screen.CAMERA },
            onEdit = { selected = it; screen = Screen.EDITOR },
            onDelete = { target ->
                customApps = customApps.filterNot { it.id == target.id }
                saveApps(prefs, customApps)
                if (selected?.id == target.id) selected = null
            },
            onAdd = { selected = null; screen = Screen.EDITOR },
            onDocs = { screen = Screen.DOCS }
        )
        Screen.EDITOR -> EditorScreen(
            existing = selected,
            onBack = { screen = Screen.HOME },
            onSave = { app ->
                customApps = customApps.filterNot { it.id == app.id } + app
                saveApps(prefs, customApps)
                screen = Screen.HOME
            },
            onDocs = { screen = Screen.DOCS }
        )
        Screen.DOCS -> DocsScreen { screen = Screen.HOME }
        Screen.CAMERA -> selected?.let { app ->
            CameraScreen(app) { screen = Screen.HOME }
        } ?: run { screen = Screen.HOME }
    }
}

@Composable
private fun HomeScreen(
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
            containerColor = AppSurface2,
            titleContentColor = AppText,
            textContentColor = AppMuted,
            title = { Text("Delete ${target.name}?") },
            text = { Text("This removes the saved filter and its code from this device.") },
            confirmButton = {
                TextButton(onClick = { onDelete(target); pendingDelete = null }) {
                    Text("Delete", color = Color(0xFFFF9A91))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        containerColor = AppDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Filter Studio", color = AppText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("Premades are normal scripts, not special language commands", color = AppMuted)
                }
                IconButton(onClick = onDocs) { Icon(Icons.Default.MenuBook, "Docs", tint = AppText) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                containerColor = AppAccent,
                contentColor = Color(0xFF002118),
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add filter") }
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
                    colors = CardDefaults.cardColors(containerColor = AppSurface, contentColor = AppText),
                    modifier = Modifier.fillMaxWidth().clickable { onRun(app) }
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).background(
                                    if (app.builtIn) AppBlue.copy(.16f) else AppAccent.copy(.16f),
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
                                    tint = if (app.builtIn) AppBlue else AppAccent
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.name, color = AppText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    if (app.builtIn) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(color = AppBlue.copy(.14f), shape = RoundedCornerShape(50)) {
                                            Text("PREMADE", color = AppBlue, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                                        }
                                    }
                                }
                                Text(app.mode.name, color = AppMuted, fontSize = 12.sp)
                            }
                            IconButton(onClick = { onEdit(app) }) {
                                Icon(if (app.builtIn) Icons.Default.Code else Icons.Default.Edit, if (app.builtIn) "View source" else "Edit", tint = AppText)
                            }
                            if (!app.builtIn) {
                                IconButton(onClick = { pendingDelete = app }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF9A91))
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(app.description, color = AppMuted)
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
    val forkPremade = existing?.builtIn == true
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "My Filter") }
    var description by remember(existing?.id) { mutableStateOf(existing?.description ?: "Custom filter") }
    var mode by remember(existing?.id) { mutableStateOf(existing?.mode ?: TrackingMode.FACE) }
    var code by remember(existing?.id) { mutableStateOf(existing?.code ?: defaultScript()) }
    var error by remember { mutableStateOf<String?>(null) }
    val engine = remember { ScriptEngine() }

    Scaffold(
        containerColor = AppDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AppText) }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            forkPremade -> "Premade source"
                            existing != null -> "Edit filter"
                            else -> "New filter"
                        },
                        color = AppText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    if (forkPremade) Text("Saving creates your own editable copy", color = AppMuted, fontSize = 11.sp)
                }
                TextButton(onClick = onDocs) { Text("Docs") }
                Button(onClick = {
                    try {
                        engine.parse(code)
                        onSave(
                            FilterApp(
                                id = if (forkPremade || existing == null) UUID.randomUUID().toString() else existing.id,
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
                }) { Text(if (forkPremade) "Save copy" else "Save") }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Field(name, { name = it }, "Name")
                Spacer(Modifier.height(8.dp))
                Field(description, { description = it }, "Description")
            }
            item {
                Text("Tracking mode", color = AppText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackingMode.entries.forEach { value ->
                        FilterChip(
                            selected = mode == value,
                            onClick = { mode = value },
                            label = { Text(value.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = AppSurface2,
                                labelColor = AppText,
                                selectedContainerColor = AppAccent.copy(.22f),
                                selectedLabelColor = AppText
                            )
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("All MediaPipe landmarks are exposed. No quality/LOD selector.", color = AppMuted, fontSize = 12.sp)
            }
            item {
                Text("Code", color = AppText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null },
                    modifier = Modifier.fillMaxWidth().height(500.dp),
                    textStyle = LocalTextStyle.current.copy(color = AppText, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    label = { Text("Sandboxed filter script") },
                    colors = fieldColors()
                )
                error?.let { Text(it, color = Color(0xFFFFB4AB)) }
            }
        }
    }
}

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppText,
    unfocusedTextColor = AppText,
    focusedLabelColor = AppAccent,
    unfocusedLabelColor = AppMuted,
    cursorColor = AppAccent,
    focusedBorderColor = AppAccent,
    unfocusedBorderColor = AppOutline,
    focusedContainerColor = AppSurface,
    unfocusedContainerColor = AppSurface
)

@Composable
private fun DocsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val docs = remember { context.assets.open("SCRIPTING.md").bufferedReader().readText() }
    Scaffold(
        containerColor = AppDark,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AppText) }
                Text("Scripting docs", color = AppText, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Face Changer docs", docs))
                }) { Icon(Icons.Default.ContentCopy, "Copy docs", tint = AppText) }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(docs.lines()) { raw ->
                when {
                    raw.startsWith("# ") -> Text(raw.removePrefix("# "), color = AppText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    raw.startsWith("## ") -> Text(raw.removePrefix("## "), color = AppAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    raw.startsWith("- ") -> Text("• ${raw.removePrefix("- ")}", color = AppText)
                    raw.length >= 2 && raw.startsWith("`") && raw.endsWith("`") -> Surface(color = AppSurface2, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(raw.removeSurrounding("`"), color = Color(0xFFBFEBDD), fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                    }
                    raw.isBlank() -> Spacer(Modifier.height(4.dp))
                    else -> Text(raw, color = AppText, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun CameraScreen(app: FilterApp, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(true) }
    val engine = remember { ScriptEngine() }
    val parseResult = remember(app.code) { runCatching { engine.parse(app.code) } }
    val program = parseResult.getOrNull()
    val values = remember(program) {
        mutableStateMapOf<String, String>().also { map -> program?.inputs?.forEach { map[it.name] = it.defaultValue } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && program != null) {
            FilterCameraView(front, app.mode, app.code, engine, program, values.toMap())
        } else {
            Text(
                if (!granted) "Camera permission is required" else "Script error: ${parseResult.exceptionOrNull()?.message ?: "unknown"}",
                color = AppText,
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
                Text("${app.mode} • sandbox script", color = Color(0xFFD0D7DE), fontSize = 11.sp)
            }
            IconButton(onClick = { front = !front }) { Icon(Icons.Default.Cameraswitch, "Switch camera", tint = Color.White) }
        }

        if (program?.inputs?.isNotEmpty() == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppSurface.copy(.96f), contentColor = AppText),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    program.inputs.forEach { input ->
                        if (input.type == InputType.NUMBER) {
                            val current = (values[input.name] ?: input.defaultValue).toDoubleOrNull() ?: 0.0
                            val low = input.min ?: 0.0
                            val high = input.max ?: 10.0
                            Text("${input.label}: ${"%.2f".format(current)}", color = AppText, fontSize = 12.sp)
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
                                colors = fieldColors()
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun defaultScript() = """# Tiny example using only primitives
if tracked
  let px = group_center_x(0)
  let py = group_center_y(0)
  write_pixel px py 0.29 0.84 0.67 1
  write_pixel px+1/image_width py 0.29 0.84 0.67 1
  write_pixel px py+1/image_height 0.29 0.84 0.67 1
end"""

/**
 * Fast script-only line primitive.
 *
 * The old premades visited essentially every pixel along every segment and wrote three pixels per
 * sample. A face frame could therefore execute tens of thousands of interpreted write_pixel calls
 * before the next MediaPipe result was allowed through. Sampling at ~2px spacing is visually
 * continuous at the 640x480 analysis resolution while cutting the work by an order of magnitude.
 */
private fun segmentPrimitive() = """fn segment grp a b rr gg bb
  if and(point_exists(grp,a),point_exists(grp,b))
    let x1 = landmark_x(grp,a)
    let y1 = landmark_y(grp,a)
    let x2 = landmark_x(grp,b)
    let y2 = landmark_y(grp,b)
    let steps = min(28,max(2,ceil(distance(x1,y1,x2,y2)*image_width/2)))
    repeat steps
      let t = loop/max(1,steps-1)
      let px = lerp(x1,x2,t)
      let py = lerp(y1,y2,t)
      write_pixel px py rr gg bb 1
    end
  end
end"""

/** Draw every landmark, restoring the dense dot cloud the original premades displayed. */
private fun allLandmarkDots(rr: Double, gg: Double, bb: Double): String = """repeat groups
  let dot_grp = loop
  let dot_count = landmark_count(dot_grp)
  repeat dot_count
    let dot_index = loop
    let dot_x = landmark_x(dot_grp,dot_index)
    let dot_y = landmark_y(dot_grp,dot_index)
    write_pixel dot_x dot_y $rr $gg $bb 1
    write_pixel dot_x+1/image_width dot_y $rr $gg $bb 1
    write_pixel dot_x dot_y+1/image_height $rr $gg $bb 1
  end
end"""

private fun scriptForEdges(
    edges: List<Pair<Int, Int>>,
    color: Triple<Double, Double, Double>,
    dotColor: Triple<Double, Double, Double>? = null
): String = buildString {
    appendLine(segmentPrimitive())
    appendLine("repeat groups")
    appendLine("  let grp = loop")
    edges.forEach { (a, b) ->
        appendLine("  call segment grp $a $b ${color.first} ${color.second} ${color.third}")
    }
    appendLine("end")
    dotColor?.let { dots ->
        appendLine(allLandmarkDots(dots.first, dots.second, dots.third))
    }
}.trim()

private fun loopEdges(points: List<Int>): List<Pair<Int, Int>> =
    if (points.size < 2) emptyList() else points.zipWithNext() + (points.last() to points.first())

private val FACE_SAMPLE_EDGES: List<Pair<Int, Int>> = buildList {
    // Face oval.
    addAll(loopEdges(listOf(10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109)))
    // Eyes.
    addAll(loopEdges(listOf(33,7,163,144,145,153,154,155,133,173,157,158,159,160,161,246)))
    addAll(loopEdges(listOf(263,249,390,373,374,380,381,382,362,398,384,385,386,387,388,466)))
    // Outer and inner lips.
    addAll(loopEdges(listOf(61,146,91,181,84,17,314,405,321,375,291,409,270,269,267,0,37,39,40,185)))
    addAll(loopEdges(listOf(78,95,88,178,87,14,317,402,318,324,308,415,310,311,312,13,82,81,80,191)))
    // Eyebrows.
    addAll(listOf(70 to 63,63 to 105,105 to 66,66 to 107,46 to 53,53 to 52,52 to 65,65 to 55))
    addAll(listOf(336 to 296,296 to 334,334 to 293,293 to 300,276 to 283,283 to 282,282 to 295,295 to 285))
    // Nose bridge and nostril outline.
    addAll(listOf(168 to 6,6 to 197,197 to 195,195 to 5,5 to 4,4 to 1,1 to 19,19 to 94,94 to 2))
    addAll(listOf(98 to 97,97 to 2,2 to 326,326 to 327,327 to 294,294 to 278,278 to 344,344 to 440,440 to 275,275 to 4,4 to 45,45 to 220,220 to 115,115 to 48,48 to 64,64 to 98))
    // Irises are present when the model exposes the refined 478-point face set.
    addAll(loopEdges(listOf(469,470,471,472)))
    addAll(loopEdges(listOf(474,475,476,477)))
}

private val HAND_SAMPLE_EDGES = listOf(
    0 to 1,1 to 2,2 to 3,3 to 4,0 to 5,5 to 6,6 to 7,7 to 8,5 to 9,9 to 10,10 to 11,11 to 12,
    9 to 13,13 to 14,14 to 15,15 to 16,13 to 17,17 to 18,18 to 19,19 to 20,0 to 17
)

private val BODY_SAMPLE_EDGES = listOf(
    0 to 1,1 to 2,2 to 3,3 to 7,0 to 4,4 to 5,5 to 6,6 to 8,9 to 10,11 to 12,11 to 13,13 to 15,
    15 to 17,15 to 19,15 to 21,17 to 19,12 to 14,14 to 16,16 to 18,16 to 20,16 to 22,18 to 20,
    11 to 23,12 to 24,23 to 24,23 to 25,25 to 27,27 to 29,29 to 31,27 to 31,24 to 26,26 to 28,
    28 to 30,30 to 32,28 to 32
)

private fun premadeFilters() = listOf(
    FilterApp(
        "premade-face",
        "Face Mesh",
        "Dense full-landmark dots plus face, eye, lip, brow, nose and iris contours. Ordinary sandbox source you can inspect and fork.",
        TrackingMode.FACE,
        DetailLevel.HIGH,
        scriptForEdges(
            FACE_SAMPLE_EDGES,
            Triple(0.34, 0.66, 1.0),
            dotColor = Triple(0.29, 0.84, 0.67)
        ),
        true
    ),
    FilterApp(
        "premade-hand",
        "Hand Skeleton",
        "Hand bone connections plus every tracked hand landmark, implemented with ordinary sandbox primitives.",
        TrackingMode.HAND,
        DetailLevel.HIGH,
        scriptForEdges(
            HAND_SAMPLE_EDGES,
            Triple(0.29, 0.84, 0.67),
            dotColor = Triple(0.34, 0.66, 1.0)
        ),
        true
    ),
    FilterApp(
        "premade-body",
        "Body Skeleton",
        "Pose connections plus every tracked body landmark using the same language available to custom filters.",
        TrackingMode.BODY,
        DetailLevel.HIGH,
        scriptForEdges(
            BODY_SAMPLE_EDGES,
            Triple(0.34, 0.66, 1.0),
            dotColor = Triple(0.29, 0.84, 0.67)
        ),
        true
    )
)

private fun saveApps(prefs: android.content.SharedPreferences, apps: List<FilterApp>) {
    val array = JSONArray()
    apps.filterNot { it.builtIn }.forEach { app ->
        array.put(JSONObject().apply {
            put("id", app.id)
            put("name", app.name)
            put("description", app.description)
            put("mode", app.mode.name)
            put("code", app.code)
        })
    }
    prefs.edit().putString("apps", array.toString()).apply()
}

private fun loadApps(prefs: android.content.SharedPreferences): List<FilterApp> = runCatching {
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