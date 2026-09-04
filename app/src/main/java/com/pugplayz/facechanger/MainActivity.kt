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
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                    primary = Accent, secondary = Blue, background = Dark, surface = Surface,
                    onBackground = Color(0xFFEAF1F7), onSurface = Color(0xFFEAF1F7)
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
            if (key == SIGN_IN_KEY) { prefs.edit().putBoolean("signed_in", true).apply(); signedIn = true; true } else false
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
            onSignOut = { prefs.edit().putBoolean("signed_in", false).apply(); signedIn = false }
        )
        Screen.EDITOR -> EditorScreen(selected, onBack = { screen = Screen.HOME }, onSave = { app ->
            apps = apps.filterNot { it.id == app.id } + app
            saveApps(prefs, apps); screen = Screen.HOME
        }, onDocs = { screen = Screen.DOCS })
        Screen.DOCS -> DocsScreen { screen = Screen.HOME }
        Screen.CAMERA -> selected?.let { CameraScreen(it) { screen = Screen.HOME } } ?: run { screen = Screen.HOME }
    }
}

@Composable
private fun SignInScreen(onTry: (String) -> Boolean) {
    var key by remember { mutableStateOf("") }; var bad by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Dark).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(52.dp))
            Text("Face Changer Custom", fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("Open-source MediaPipe filter lab", color = Color.Gray)
            OutlinedTextField(key, { key = it; bad = false }, label = { Text("Sign-in key") }, isError = bad, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { bad = !onTry(key) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Enter studio") }
            if (bad) Text("That key doesn't match.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun HomeScreen(apps: List<FilterApp>, onRun: (FilterApp)->Unit, onEdit: (FilterApp)->Unit, onAdd:()->Unit, onDocs:()->Unit, onSignOut:()->Unit) {
    Scaffold(containerColor = Dark, topBar = {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Filter Studio", fontSize = 28.sp, fontWeight = FontWeight.Black); Text("MediaPipe + your code", color = Color.Gray) }
            IconButton(onClick = onDocs) { Icon(Icons.Default.MenuBook, "Docs") }
            IconButton(onClick = onSignOut) { Icon(Icons.Default.Logout, "Sign out") }
        }
    }, floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add app") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(apps, key = { it.id }) { app ->
                Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth().clickable { onRun(app) }) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp).background(if(app.builtIn) Blue.copy(.18f) else Accent.copy(.18f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(if(app.mode==TrackingMode.FACE) Icons.Default.Face else if(app.mode==TrackingMode.HAND) Icons.Default.BackHand else Icons.Default.AccessibilityNew, null, tint = if(app.builtIn) Blue else Accent) }
                            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(app.name, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("${app.mode} • ${app.detail}", color = Color.Gray, fontSize = 12.sp) }
                            if (!app.builtIn) IconButton(onClick = { onEdit(app) }) { Icon(Icons.Default.Edit, "Edit") }
                        }
                        Spacer(Modifier.height(10.dp)); Text(app.description, color = Color(0xFFB8C3CD))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorScreen(existing: FilterApp?, onBack:()->Unit, onSave:(FilterApp)->Unit, onDocs:()->Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "My Filter") }
    var desc by remember { mutableStateOf(existing?.description ?: "Custom filter") }
    var mode by remember { mutableStateOf(existing?.mode ?: TrackingMode.FACE) }
    var detail by remember { mutableStateOf(existing?.detail ?: DetailLevel.HIGH) }
    var code by remember { mutableStateOf(existing?.code ?: "input number strength Strength 1.8 0.5 3.0\nif tracked\n  magnify 0 33 strength 0.10\n  dots #47D7AC 3\nend") }
    var error by remember { mutableStateOf<String?>(null) }
    val engine = remember { ScriptEngine() }
    Scaffold(containerColor = Dark, topBar = { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}; Text(existing?.let{"Edit app"}?:"New app",fontWeight=FontWeight.Bold,fontSize=22.sp,modifier=Modifier.weight(1f)); TextButton(onClick=onDocs){Text("Docs")}; Button(onClick={ try { engine.parse(code); onSave(FilterApp(existing?.id?:UUID.randomUUID().toString(),name,desc,mode,detail,code,false)) } catch(t:Throwable){ error=t.message } }){Text("Save")} } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(name,{name=it},label={Text("Name")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(desc,{desc=it},label={Text("Description")},modifier=Modifier.fillMaxWidth()) }
            item { Text("Tracking mode",fontWeight=FontWeight.Bold); Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TrackingMode.entries.forEach{ FilterChip(selected=mode==it,onClick={mode=it},label={Text(it.name)}) }}; Spacer(Modifier.height(6.dp)); Text("Detail",fontWeight=FontWeight.Bold); Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){DetailLevel.entries.forEach{FilterChip(selected=detail==it,onClick={detail=it},label={Text(it.name)})}} }
            item { Text("Code",fontWeight=FontWeight.Bold); OutlinedTextField(code,{code=it;error=null},modifier=Modifier.fillMaxWidth().height(390.dp),textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=13.sp),label={Text("Sandboxed filter script")}); error?.let{Text(it,color=MaterialTheme.colorScheme.error)} }
        }
    }
}

@Composable
private fun DocsScreen(onBack:()->Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val docs = remember { context.assets.open("SCRIPTING.md").bufferedReader().readText() }
    Scaffold(containerColor=Dark, topBar={Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)};Text("Scripting docs",fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={ val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(android.content.ClipData.newPlainText("Face Changer docs",docs)) }){Icon(Icons.Default.ContentCopy,"Copy docs")}}}) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding=PaddingValues(20.dp)){item{Text(docs,fontFamily=FontFamily.Monospace,lineHeight=20.sp,color=Color(0xFFD7E1E8))}}
    }
}

@Composable
private fun CameraScreen(app: FilterApp, onBack:()->Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted=it}
    LaunchedEffect(Unit){if(!granted) launcher.launch(Manifest.permission.CAMERA)}
    var front by remember { mutableStateOf(true) }; var frame by remember { mutableStateOf<Bitmap?>(null) }
    val engine = remember { ScriptEngine() }; val program = remember(app.code){ runCatching{engine.parse(app.code)}.getOrNull() }
    val values = remember(program){ mutableStateMapOf<String,String>().also{ m -> program?.inputs?.forEach{m[it.name]=it.defaultValue} } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if(granted && program!=null) CameraFeed(front,app,engine,program,values){ old -> frame=old } else Text(if(!granted)"Camera permission is required" else "Script error",modifier=Modifier.align(Alignment.Center))
        frame?.let { androidx.compose.foundation.Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(.45f)).padding(8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)};Column(Modifier.weight(1f)){Text(app.name,fontWeight=FontWeight.Bold);Text("${app.mode} • ${app.detail}",fontSize=11.sp,color=Color.LightGray)};IconButton(onClick={front=!front}){Icon(Icons.Default.Cameraswitch,"Switch camera")} }
        if(program?.inputs?.isNotEmpty()==true) Card(colors=CardDefaults.cardColors(containerColor=Surface.copy(.92f)),modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){program.inputs.forEach{input-> if(input.type==InputType.NUMBER){ val v=(values[input.name]?:input.defaultValue).toDoubleOrNull()?:0.0; val lo=input.min?:0.0; val hi=input.max?:10.0; Text("${input.label}: ${"%.2f".format(v)}",fontSize=12.sp);Slider(value=v.toFloat().coerceIn(lo.toFloat(),hi.toFloat()),onValueChange={values[input.name]=it.toString()},valueRange=lo.toFloat()..hi.toFloat()) }else{OutlinedTextField(values[input.name]?:"",{values[input.name]=it},label={Text(input.label)},modifier=Modifier.fillMaxWidth())}}}}
    }
}

@Composable
private fun CameraFeed(front:Boolean, app:FilterApp, engine:ScriptEngine, program:ScriptEngine.Program, values:Map<String,String>, onFrame:(Bitmap)->Unit){
    val context=androidx.compose.ui.platform.LocalContext.current; val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(front,app.id){
        val executor=Executors.newSingleThreadExecutor(); val tracker=TrackingEngine(context); var busy=false
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider=future.get(); val analysis=ImageAnalysis.Builder().setTargetResolution(android.util.Size(1280,720)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor){proxy-> if(busy){proxy.close();return@setAnalyzer};busy=true; try{ var bitmap=proxy.toBitmap(); bitmap=rotateBitmap(bitmap,proxy.imageInfo.rotationDegrees,front); val track=tracker.detect(bitmap,app.mode,app.detail); val rendered=engine.render(bitmap,track,program,values.toMap()); onFrame(rendered); if(rendered!==bitmap) bitmap.recycle() }catch(_:Throwable){}finally{busy=false;proxy.close()} }
            provider.unbindAll(); provider.bindToLifecycle(lifecycle,if(front)CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,analysis)
        },ContextCompat.getMainExecutor(context))
        onDispose{runCatching{future.get().unbindAll()};tracker.close();executor.shutdownNow()}
    }
}

private fun rotateBitmap(source:Bitmap,degrees:Int,mirror:Boolean):Bitmap{
    val m=Matrix(); if(degrees!=0)m.postRotate(degrees.toFloat()); if(mirror)m.postScale(-1f,1f)
    val out=Bitmap.createBitmap(source,0,0,source.width,source.height,m,true); if(out!==source)source.recycle(); return out
}

private fun builtIns()=listOf(
    FilterApp("builtin-face","Face Mesh","High-detail face landmarks rendered by the same script engine available to custom apps.",TrackingMode.FACE,DetailLevel.HIGH,"dots #47D7AC 2\nskeleton #56A8FF 1.5",true),
    FilterApp("builtin-hand","Hand Skeleton","Tracks up to two hands. Low/medium/high detail changes the landmark set exposed to scripts.",TrackingMode.HAND,DetailLevel.HIGH,"dots #47D7AC 5\nskeleton #47D7AC 3",true),
    FilterApp("builtin-body","Body Skeleton","Full-body pose landmarks with a clean camera overlay.",TrackingMode.BODY,DetailLevel.HIGH,"dots #56A8FF 5\nskeleton #56A8FF 3",true)
)

private fun saveApps(prefs:android.content.SharedPreferences,apps:List<FilterApp>){val a=JSONArray();apps.forEach{app->a.put(JSONObject().apply{put("id",app.id);put("name",app.name);put("description",app.description);put("mode",app.mode.name);put("detail",app.detail.name);put("code",app.code)})};prefs.edit().putString("apps",a.toString()).apply()}
private fun loadApps(prefs:android.content.SharedPreferences):List<FilterApp>{return runCatching{val a=JSONArray(prefs.getString("apps","[]"));(0 until a.length()).map{i->val o=a.getJSONObject(i);FilterApp(o.getString("id"),o.getString("name"),o.optString("description"),TrackingMode.valueOf(o.getString("mode")),DetailLevel.valueOf(o.getString("detail")),o.getString("code"),false)}}.getOrDefault(emptyList())}
