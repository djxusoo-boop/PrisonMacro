@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package es.macrostudio

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import android.graphics.BitmapFactory
import android.util.Base64
import es.macrostudio.capture.*
import es.macrostudio.core.*
import es.macrostudio.editor.LabActivity
import es.macrostudio.engine.Engine
import es.macrostudio.gestures.MacroAccessibilityService
import es.macrostudio.projects.ProjectStore
import es.macrostudio.sync.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Studio.init(this)
        applyOrientationMode(getSharedPreferences("studio",0).getInt("orientationMode",0))
        setContent { StudioApp(this) }
        Studio.sync.handleSignInIntent(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Studio.sync.handleSignInIntent(intent)
    }
    fun applyOrientationMode(mode: Int) {
        requestedOrientation=when(mode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
}

/** Importa un JSON local o descargado de la nube y conserva sus capturas. */
private fun importProjectPayload(activity: Activity, bytes: ByteArray, source: String) {
    require(bytes.size <= 30_000_000) { "Máximo de importación: 30 MB" }
    val root=JSONObject(bytes.toString(Charsets.UTF_8))
    val legacyImage=root.optString("imageBase64")
    val images=root.optJSONObject("imagesBase64") ?: JSONObject()
    root.remove("imageBase64")
    root.remove("imagesBase64")
    var incoming=ProjectStore.decode(root.toString()).copy(id=UUID.randomUUID().toString())
    val remap=mutableMapOf<String,String>()
    val required=(incoming.templates.map { it.template }+listOfNotNull(incoming.template)).distinctBy { it.file }
    required.forEach { old ->
        val encoded=if(images.has(old.file)) images.getString(old.file) else legacyImage
        require(encoded.isNotEmpty()) { "Falta la imagen ${old.file}" }
        val data=Base64.decode(encoded,Base64.DEFAULT)
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(data,0,data.size,options)
        require(options.outWidth==old.width && options.outHeight==old.height && old.width.toLong()*old.height<=20_000_000) { "Imagen inválida" }
        val file="template-${UUID.randomUUID()}.png"
        File(activity.filesDir,file).writeBytes(data)
        remap[old.file]=file
    }
    incoming=incoming.copy(
        template=incoming.template?.let { it.copy(file=remap[it.file] ?: it.file) },
        templates=incoming.templates.map { it.copy(template=it.template.copy(file=remap[it.template.file] ?: it.template.file)) }
    )
    Studio.switch(incoming)
    Studio.log("Proyecto importado desde $source")
}

@Composable private fun StudioApp(activity: Activity) {
    var tab by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<Action?>(null) }
    var importingForAction by remember { mutableStateOf<String?>(null) }
    var addParent by remember { mutableStateOf<String?>(null) }
    var addElse by remember { mutableStateOf(false) }
    var addAfter by remember { mutableStateOf<String?>(null) }
    var addBefore by remember { mutableStateOf<String?>(null) }
    var onlyConditions by remember { mutableStateOf(false) }
    var draftActionId by remember { mutableStateOf<String?>(null) }
    var showKinds by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var renameImage by remember { mutableStateOf<SavedTemplate?>(null) }
    var deleteImage by remember { mutableStateOf<SavedTemplate?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var coachEnabled by remember { mutableStateOf(false) }
    val configuration=LocalConfiguration.current
    val portrait=configuration.screenHeightDp>configuration.screenWidthDp
    val automaticCompact=configuration.screenWidthDp>=840 || portrait
    var compactChoice by rememberSaveable { mutableIntStateOf(-1) }
    val compactView=if(compactChoice<0) automaticCompact else compactChoice==1
    var orientationMode by remember { mutableIntStateOf(activity.getSharedPreferences("studio",0).getInt("orientationMode",0)) }
    val contentScroll=rememberScrollState()
    LaunchedEffect(tab) { contentScroll.scrollTo(0) }
    val p=Studio.project; val busy=Studio.running || Studio.recording
    val executionIssues=p.executionIssues()
    val canRun=hasEnabledAction(p.actions) && executionIssues.isEmpty()
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK && result.data!=null) activity.startForegroundService(Intent(activity,CaptureService::class.java).putExtra("data",result.data))
        else Studio.fail("Captura cancelada. No se ha compartido la pantalla.")
    }
    // OpenDocument muestra el selector de archivos completo en Android/BlueStacks
    // y no se limita a la biblioteca vacía del Photo Picker.
    val pickImage=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) activity.startActivity(Intent(activity,es.macrostudio.editor.CropActivity::class.java).setData(uri).putExtra("actionId",importingForAction).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        importingForAction=null
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) runCatching {
        val root=JSONObject(ProjectStore.encode(Studio.project))
        val images=JSONObject()
        (Studio.project.templates.map { it.template }+listOfNotNull(Studio.project.template)).distinctBy { it.file }.forEach { images.put(it.file,Base64.encodeToString(File(activity.filesDir,it.file).readBytes(),Base64.NO_WRAP)) }
        root.put("imagesBase64",images)
        activity.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) } ?: error("No se pudo escribir el archivo")
        Studio.log("Proyecto exportado con su plantilla")
    }.onFailure { Studio.fail("Exportación fallida: ${it.message}") } }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) runCatching {
        val bytes=activity.contentResolver.openInputStream(uri)?.use { input ->
            val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) { val read=input.read(buffer); if(read<0) break; require(output.size()+read<=30_000_000) { "Máximo de importación: 30 MB" }; output.write(buffer,0,read) }
            output.toByteArray()
        } ?: error("No se pudo leer el archivo")
        importProjectPayload(activity,bytes,"archivo")
    }.onFailure { Studio.fail("Importación rechazada: ${it.message}") } }
    val readableTypography = Typography(
        titleLarge = androidx.compose.ui.text.TextStyle(fontSize=22.sp),
        headlineSmall = androidx.compose.ui.text.TextStyle(fontSize=25.sp),
        titleMedium = androidx.compose.ui.text.TextStyle(fontSize=19.sp),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize=18.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize=16.sp),
        bodySmall = androidx.compose.ui.text.TextStyle(fontSize=14.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontSize=16.sp),
        labelMedium = androidx.compose.ui.text.TextStyle(fontSize=15.sp),
        labelSmall = androidx.compose.ui.text.TextStyle(fontSize=13.sp)
    )
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF6DE2D1),secondary=Color(0xFFB9A5FF),background=Color(0xFF0E1320),surface=Color(0xFF171F30)), typography=readableTypography) {
        Scaffold(topBar={ if(!compactView) TopAppBar(title={ Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) { PrisonBrandMark(34.dp); Column { Text("PrisonMacro",style=MaterialTheme.typography.titleLarge); Text("${BuildConfig.VERSION_NAME} · macros locales y nube privada",style=MaterialTheme.typography.labelSmall) } } },actions={ TextButton(onClick={showHelp=true}) { Text("Ayuda") } }) },bottomBar={
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal=10.dp,vertical=5.dp)) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(Studio.status,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    if(compactView && portrait) {
                        Button(onClick={Engine.start(false); if(Studio.running) activity.moveTaskToBack(true)},enabled=!busy && canRun,modifier=Modifier.semantics { contentDescription="Ejecutar macro" }) { Text("▶") }
                        OutlinedButton(onClick={Engine.pause()},enabled=Studio.running,modifier=Modifier.semantics { contentDescription=if(Studio.paused) "Seguir macro" else "Pausar macro" }) { Text(if(Studio.paused) "▶" else "Ⅱ") }
                        OutlinedButton(onClick={MacroAccessibilityService.instance?.stopRecording(); Engine.stop("Parada manual")},enabled=busy,modifier=Modifier.semantics { contentDescription="Parar macro" }) { Text("■") }
                    } else {
                        Button(onClick={Engine.start(false); if(Studio.running) activity.moveTaskToBack(true)},enabled=!busy && canRun) { Text("▶ Ejecutar") }
                        OutlinedButton(onClick={Engine.pause()},enabled=Studio.running) { Text(if(Studio.paused) "Seguir" else "Pausa") }
                        OutlinedButton(onClick={MacroAccessibilityService.instance?.stopRecording(); Engine.stop("Parada manual")},enabled=busy) { Text("■ Parar") }
                    }
                }
            }
        }) { padding -> Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex=tab,edgePadding=if(compactView) 2.dp else 8.dp) {
                listOf("Editor","Imagen","Grabador","Entrenador","Registro","Proyectos","Nube").forEachIndexed { index,label -> Tab(selected=tab==index,onClick={tab=index},text={Text(label)}) }
                if(compactView) Tab(selected=false,onClick={showHelp=true},text={Text("Ayuda")})
            }
            Column(Modifier.weight(1f).verticalScroll(contentScroll).padding(start=if(compactView) 10.dp else 16.dp,end=if(compactView) 10.dp else 16.dp,top=if(compactView) 6.dp else 12.dp,bottom=if(compactView) 90.dp else 120.dp),verticalArrangement=Arrangement.spacedBy(if(compactView) 6.dp else 10.dp)) {
                when(tab) {
                    0 -> {
                        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.fillMaxWidth().padding(if(compactView) 10.dp else 14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    PrisonBrandMark(if(compactView) 46.dp else 54.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name,style=if(compactView) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text("${p.actions.size} paso(s) · modo edición",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                                    }
                                    Button(onClick={Engine.start(false); if(Studio.running) activity.moveTaskToBack(true)},enabled=!busy && canRun,modifier=Modifier.semantics { contentDescription="Reproducir macro ahora" }) { Text("▶ Reproducir ahora") }
                                }
                                Text("Añade una acción, condición o espera. Cada paso puede tener su propia captura y descripción.",style=MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    Button(onClick={addParent=null; addAfter=null; addBefore=null; onlyConditions=false; showKinds=true},enabled=!busy) { Text("＋ Añadir acción") }
                                    OutlinedButton(onClick={Studio.undo()},enabled=Studio.canUndo && !busy) { Text("Deshacer") }
                                    OutlinedButton(onClick={Studio.redo()},enabled=Studio.canRedo && !busy) { Text("Rehacer") }
                                    OutlinedButton(onClick={Engine.start(false,true)},enabled=!busy && canRun) { Text("Simular") }
                                    if(p.template!=null) TextButton(
                                        onClick={Engine.start(true); if(Studio.running) activity.moveTaskToBack(true)},
                                        enabled=!busy && canRun && Studio.captureReady,
                                        modifier=Modifier.semantics { contentDescription="Esperar a ver la imagen de inicio y reproducir la macro" },
                                        contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp)
                                    ) { Text("▶ Al ver imagen") }
                                    if(compactView) FilterChip(selected=true,onClick={compactChoice=0},label={Text("Compacta")})
                                    else FilterChip(selected=false,onClick={compactChoice=1},label={Text("Vista compacta")})
                                }
                            }
                        }
                        if(executionIssues.isEmpty()) {
                            Text("✓ Lista para ejecutar",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                        } else {
                            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {
                                Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    Text("Revisa antes de ejecutar",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onErrorContainer)
                                    executionIssues.take(3).forEach { Text("• $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer) }
                                    if(executionIssues.size>3) Text("• Y ${executionIssues.size-3} aviso(s) más",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                        if(p.actions.isEmpty()) Card { Text("Todavía no hay pasos. Añade una pulsación o graba tus gestos desde el panel flotante.",Modifier.padding(18.dp)) }
                        ActionList(
                            p.actions,
                            busy,
                            edit={ draftActionId=null; edit=it },
                            add={id,isElse -> addParent=id; addElse=isElse; addAfter=null; addBefore=null; onlyConditions=false; showKinds=true},
                            addAfter={action -> addParent=null; addElse=false; addAfter=action.id; addBefore=null; onlyConditions=true; showKinds=true},
                            addBefore={action -> addParent=null; addElse=false; addAfter=null; addBefore=action.id; onlyConditions=false; showKinds=true},
                            compact=compactView
                        )
                        Text("Para un solo clic al encontrar una captura, añade «Detectar imagen y hacer clic». Para varias acciones, abre Detalles y usa «＋ Añadir acción en ENTONCES».",style=MaterialTheme.typography.bodySmall)
                        Text("Guardado automático · cada SI SE VE usa la captura elegida · máximo 30 minutos por sesión",style=MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("Detector de imágenes",style=MaterialTheme.typography.headlineSmall)
                        Studio.pendingTemplateActionId?.let { pendingId ->
                            val pendingName=findAction(p.actions,pendingId)?.name ?: "la condición elegida"
                            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                    Text("La próxima captura se asignará a: $pendingName",style=MaterialTheme.typography.titleMedium)
                                    if(p.templates.isNotEmpty() && findAction(p.actions,pendingId)!=null) {
                                        Text("Elige directamente una captura ya guardada para este paso:",style=MaterialTheme.typography.bodySmall)
                                        p.templates.forEach { saved ->
                                            OutlinedButton(
                                                onClick={
                                                    val next=p.copy(actions=assignTemplateToAction(p.actions,pendingId,saved.id))
                                                    if(Studio.update(next)) Studio.pendingTemplateActionId=null
                                                },
                                                enabled=!busy,
                                                modifier=Modifier.fillMaxWidth()
                                            ) { Text("Usar esta captura en este paso · ${saved.name}",maxLines=1,overflow=TextOverflow.Ellipsis) }
                                        }
                                    }
                                    TextButton(onClick={Studio.pendingTemplateActionId=null}) { Text("Cancelar esta asignación") }
                                }
                            }
                        }
                        Text("Autoriza la pantalla completa. Abre la app objetivo y pulsa Captura en el panel flotante; después recorta el elemento.")
                        Button(onClick={permission.launch(ScreenCaptureRequest.createIntent(activity))},enabled=!Studio.captureReady && !busy) { Text(if(Studio.captureReady) "Captura activa" else "1 · Autorizar captura") }
                        OutlinedButton(onClick={val s=MacroAccessibilityService.instance; if(s==null) tab=5 else { s.showBar(); activity.moveTaskToBack(true) }},enabled=!busy) { Text("2 · Mostrar panel y capturar") }
                        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.55f))) {
                            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("Flujo correcto: detectar → abrir → esperar → cerrar",style=MaterialTheme.typography.titleMedium)
                                Text("La imagen de inicio rápido solo dispara la macro. En el Editor añade «Abrir aplicación», después «Esperar hasta ver una imagen» y elige la captura de la pantalla de llegada; al final añade «Cerrar aplicación». «Clic en imagen detectada» solo hace clic: no abre Zooba.",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("Imagen de inicio rápido",style=MaterialTheme.typography.titleMedium)
                        Text("Es independiente de las capturas elegidas dentro de cada condición. Solo la usa el botón de inicio rápido de esta pestaña.",style=MaterialTheme.typography.bodySmall)
                        val bitmap=remember(p.template?.file) { p.template?.let { BitmapFactory.decodeFile(File(activity.filesDir,it.file).path) } }
                        DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
                        if(bitmap!=null) { Image(bitmap.asImageBitmap(),"Plantilla guardada",Modifier.fillMaxWidth().heightIn(max=150.dp)); Text("${p.template!!.width}×${p.template.height} px · origen (${p.template.x}, ${p.template.y})") }
                        else Text("Aún no hay imagen de inicio rápido")
                        p.template?.let { quickTemplate ->
                            val alreadyInLibrary=p.templates.firstOrNull { it.template.file==quickTemplate.file }
                            OutlinedButton(onClick={
                                val pending=Studio.pendingTemplateActionId
                                if(alreadyInLibrary!=null) {
                                    val next=if(pending==null) p else p.copy(actions=assignTemplateToAction(p.actions,pending,alreadyInLibrary.id))
                                    if(Studio.update(next)) Studio.pendingTemplateActionId=null
                                } else {
                                    val filename="template-${UUID.randomUUID()}.png"
                                    runCatching {
                                        File(activity.filesDir,quickTemplate.file).copyTo(File(activity.filesDir,filename),overwrite=false)
                                        val saved=SavedTemplate(name="Zooba · lanzador",template=quickTemplate.copy(file=filename))
                                        val next=p.copy(
                                            templates=p.templates+saved,
                                            actions=if(pending==null) p.actions else assignTemplateToAction(p.actions,pending,saved.id)
                                        )
                                        check(Studio.update(next)) { "No se pudo guardar en la biblioteca" }
                                        Studio.pendingTemplateActionId=null
                                        Studio.log("Imagen de inicio rápido añadida a la biblioteca: ${saved.name}")
                                    }.onFailure { File(activity.filesDir,filename).delete(); Studio.fail("No se pudo añadir la imagen a la biblioteca: ${it.message}") }
                                }
                            },enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                                Text(if(alreadyInLibrary!=null) "✓ Imagen disponible para las condiciones" else "＋ Guardar también en la biblioteca")
                            }
                            Text("Así podrás elegir esta misma imagen dentro de cualquier condición, verla en el editor y recortarla de nuevo.",style=MaterialTheme.typography.bodySmall)
                        }
                        Text("Biblioteca de capturas",style=MaterialTheme.typography.titleMedium)
                        if(p.templates.isEmpty()) Text("Cuando guardes un recorte aparecerá aquí y podrás asignarlo a cualquier condición.")
                        p.templates.forEachIndexed { index,saved -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                SavedTemplatePreview(saved,Modifier.size(92.dp,64.dp))
                                Column(Modifier.weight(1f)) { Text(saved.name,style=MaterialTheme.typography.titleMedium); Text("${saved.template.width}×${saved.template.height} · (${saved.template.x}, ${saved.template.y})",style=MaterialTheme.typography.bodySmall); if(p.template?.file==saved.template.file) Text("USADA PARA INICIO RÁPIDO",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary) }
                            }
                            Studio.pendingTemplateActionId?.let { pendingId ->
                                val pendingAction=findAction(p.actions,pendingId)
                                OutlinedButton(
                                    onClick={
                                        val next=p.copy(actions=assignTemplateToAction(p.actions,pendingId,saved.id))
                                        if(Studio.update(next)) Studio.pendingTemplateActionId=null
                                    },
                                    enabled=!busy && pendingAction!=null,
                                    modifier=Modifier.fillMaxWidth()
                                ) { Text("Usar esta captura en este paso") }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                fun move(delta: Int) { val to=(index+delta).coerceIn(0,p.templates.lastIndex); if(to!=index) Studio.update(p.copy(templates=p.templates.toMutableList().apply { add(to,removeAt(index)) })) }
                                TextButton(onClick={move(-1)},enabled=!busy && index>0) { Text("↑") }
                                TextButton(onClick={move(1)},enabled=!busy && index<p.templates.lastIndex) { Text("↓") }
                                TextButton(onClick={activity.startActivity(Intent(activity,es.macrostudio.editor.CropActivity::class.java).putExtra("templateId",saved.id))},enabled=!busy) { Text("Recortar") }
                                TextButton(onClick={Studio.update(p.copy(template=saved.template))},enabled=!busy) { Text("Usar para inicio rápido") }
                                TextButton(onClick={renameImage=saved},enabled=!busy) { Text("Renombrar") }
                                TextButton(onClick={deleteImage=saved},enabled=!busy && !actionUsesTemplate(p.actions,saved.id)) { Text("Borrar") }
                            }
                        } } }
                        Row { FilterChip(selected=p.mode==SearchMode.ANYWHERE,onClick={Studio.update(p.copy(mode=SearchMode.ANYWHERE))},enabled=!busy,label={Text("Cualquier punto")}); Spacer(Modifier.width(8.dp)); FilterChip(selected=p.mode==SearchMode.EXACT,onClick={Studio.update(p.copy(mode=SearchMode.EXACT))},enabled=!busy,label={Text("Punto exacto")}) }
                        Text("Similitud requerida: ${(p.threshold*100).toInt()} %")
                        var threshold by remember(p.threshold) { mutableFloatStateOf(p.threshold.toFloat()) }
                        Slider(value=threshold,onValueChange={threshold=it},onValueChangeFinished={Studio.update(p.copy(threshold=threshold.toDouble().coerceIn(.7,1.0)))},valueRange=.7f..1f,enabled=!busy)
                        NumberField("Espera tras detectar (ms)",p.delayMs,0,600_000,!busy) { Studio.update(p.copy(delayMs=it)) }
                        NumberField("Tiempo entre finales e inicios (ms, 0 = una vez)",p.intervalMs,0,3_600_000,!busy) { Studio.update(p.copy(intervalMs=it)) }
                        NumberField("Tiempo máximo buscando (ms)",p.timeoutMs,1000,3_600_000,!busy) { Studio.update(p.copy(timeoutMs=it)) }
                        NumberField("Máximo de repeticiones",p.maxRuns.toLong(),1,1000,!busy) { Studio.update(p.copy(maxRuns=it.toInt())) }
                        OutlinedButton(onClick={activity.moveTaskToBack(true); Engine.scheduleDetection()},enabled=Studio.captureReady && p.template!=null && !busy && !Studio.detecting) { Text("Probar detección dentro de 2 s") }
                        Button(onClick={Engine.start(true); if(Studio.running) activity.moveTaskToBack(true)},enabled=Studio.captureReady && p.template!=null && !busy,modifier=Modifier.fillMaxWidth()) { Text("▶ Ejecutar cuando se vea esta imagen") }
                        Text(Studio.lastMatch,color=MaterialTheme.colorScheme.primary)
                        Text("Misma resolución, orientación y tamaño de imagen. Los fondos uniformes o animaciones pueden dar falsos positivos. Prueba antes de ejecutar.",style=MaterialTheme.typography.bodySmall)
                    }
                    2 -> {
                        Text("Graba una vez. Reproduce la secuencia.",style=MaterialTheme.typography.headlineSmall)
                        Text("No necesitas una imagen para cada clic. El grabador asistido recoge una trayectoria, la reproduce al soltar y guarda las esperas entre gestos.")
                        Text("1. Activa Accesibilidad.\n2. Abre el panel y ve a tu app.\n3. Pulsa Grabar. Haz los clics o arrastres.\n4. Pulsa FIN REC. Los pasos quedan en el editor.")
                        Button(onClick={val s=MacroAccessibilityService.instance; if(s==null) tab=5 else { s.showBar(); activity.moveTaskToBack(true) }},enabled=!busy) { Text("Abrir panel flotante") }
                        OutlinedButton(onClick={activity.startActivity(Intent(activity,LabActivity::class.java))},enabled=!busy) { Text("Abrir laboratorio seguro") }
                        Text("Limitaciones: un dedo o ratón; no captura multitáctil ni entradas de teclado. Los gestos se envían después de soltarlos. No pulses de nuevo hasta que termine su reproducción. El panel ocupa la franja superior.",style=MaterialTheme.typography.bodyMedium)
                    }
                    3 -> {
                        Text("Modo entrenador",style=MaterialTheme.typography.headlineSmall)
                        Text("Asistente local para practicar en el laboratorio",color=MaterialTheme.colorScheme.primary)
                        Text("Analiza las capturas del laboratorio y registra tus decisiones para ayudarte a mejorar. No juega partidas, no pulsa botones y no controla otras aplicaciones.")
                        Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("Funciones previstas",style=MaterialTheme.typography.titleMedium)
                            Text("• Detectar situaciones de práctica: peligro, objetivo y vida simulada")
                            Text("• Recomendar: atacar, huir, recoger o curarse")
                            Text("• Guardar tus decisiones y tiempos localmente")
                            Text("• Comparar tus rutas y repeticiones en el laboratorio")
                        } }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                            Switch(checked=coachEnabled,onCheckedChange={coachEnabled=it})
                            Text(if(coachEnabled) "Entrenador activo en laboratorio" else "Entrenador detenido")
                        }
                        Button(onClick={activity.startActivity(Intent(activity,LabActivity::class.java))},enabled=!busy) { Text("Abrir laboratorio de entrenamiento") }
                        Text("El modo entrenador no tiene acceso a controles de juegos ni ejecuta acciones fuera del laboratorio. La IA de recomendaciones se incorporará sobre escenarios simulados y datos que tú guardes.",style=MaterialTheme.typography.bodySmall)
                    }
                    4 -> {
                        Text("Depurador",style=MaterialTheme.typography.headlineSmall)
                        Text(Studio.lastMatch)
                        Text("Se guardan los últimos 200 eventos en el dispositivo. No se transmiten capturas ni registros.")
                        Studio.logs.asReversed().forEach { Text(it,style=MaterialTheme.typography.bodySmall); HorizontalDivider() }
                    }
                    5 -> {
                        Text("Mis macros",style=MaterialTheme.typography.headlineSmall)
                        Text("Cada macro se guarda por separado. Pulsa Editar para modificarla o Reproducir para ejecutarla.",style=MaterialTheme.typography.bodyMedium)
                        Studio.projects.forEach { saved ->
                            val savedCanRun=hasEnabledAction(saved.actions)
                            Card(colors=CardDefaults.cardColors(containerColor=if(saved.id==p.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                                Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(saved.name,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text("${saved.actions.size} paso(s) · ${saved.templates.size} captura(s)",style=MaterialTheme.typography.bodySmall)
                                        if(!savedCanRun) Text("Sin pasos ejecutables",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                                        if(saved.id==p.id) Text("MACRO ACTIVA",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                    }
                                    TextButton(onClick={Studio.switch(saved); tab=0},enabled=!busy) { Text("Editar") }
                                    Button(onClick={Studio.switch(saved); Engine.start(false); if(Studio.running) activity.moveTaskToBack(true)},enabled=!busy && savedCanRun) { Text("▶") }
                                }
                            }
                        }
                        Text("Proyectos y permisos",style=MaterialTheme.typography.headlineSmall)
                        Text("Orientación de PrisonMacro",style=MaterialTheme.typography.titleMedium)
                        Text("Elige Vertical solo para editar cómodo; para grabar, capturar o probar un juego horizontal, vuelve a Horizontal fijado.",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            fun setOrientation(mode: Int) { orientationMode=mode; activity.getSharedPreferences("studio",0).edit().putInt("orientationMode",mode).apply(); (activity as? MainActivity)?.applyOrientationMode(mode) }
                            FilterChip(selected=orientationMode==0,onClick={setOrientation(0)},label={Text("Horizontal fijado")})
                            FilterChip(selected=orientationMode==1,onClick={setOrientation(1)},label={Text("Vertical fijado")})
                            FilterChip(selected=orientationMode==2,onClick={setOrientation(2)},label={Text("Seguir sistema")})
                        }
                        Text("Accesibilidad: ${if(Studio.accessibilityReady) "conectada" else "pendiente"}\nCaptura: ${if(Studio.captureReady) "activa" else "pendiente"}")
                        Button(onClick={activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},enabled=!busy) { Text("Configurar accesibilidad") }
                        Text("PrisonMacro puede enviar los gestos que programes. No lee el contenido de otras ventanas. Actívalo solo si confías en tus secuencias.",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={CaptureService.instance?.stopSelf()},enabled=Studio.captureReady) { Text("Cerrar captura y detener motor") }
                        TextButton(onClick={rename=true},enabled=!busy) { Text("Cambiar nombre: ${p.name}") }
                        OutlinedButton(onClick={Studio.switch(Project(name="Proyecto ${Studio.projects.size+1}"))},enabled=!busy) { Text("Nuevo proyecto (conservar el actual)") }
                        OutlinedButton(onClick={projectMenu=true},enabled=!busy) { Text("Abrir proyecto guardado") }
                        OutlinedButton(onClick={Studio.switch(p.copy(id=UUID.randomUUID().toString(),name=(p.name+" copia").take(100)))},enabled=!busy) { Text("Duplicar proyecto") }
                        Row { TextButton(onClick={export.launch("PrisonMacro-${p.id}.json")},enabled=!busy) { Text("Exportar JSON") }; TextButton(onClick={import.launch(arrayOf("application/json","text/plain"))},enabled=!busy) { Text("Importar JSON") } }
                        Text("Esta versión incluye pasos, esperas, repetición, IF/ELSE de imagen, detector y grabación asistida. OCR, variables, funciones con parámetros y multitáctil están pendientes.",style=MaterialTheme.typography.bodySmall)
                    }
                    6 -> {
                        val sync = Studio.sync.snapshot
                        val active = activeSessionCount(sync.sessions,System.currentTimeMillis())
                        LaunchedEffect(sync.phase) { if(sync.phase==SyncPhase.CONNECTED) Studio.sync.refreshCloudProjects() }
                        Text("Sincronización de PrisonMacro",style=MaterialTheme.typography.headlineSmall)
                        Text("Biblioteca privada para editar una macro aquí y abrirla en tus otros dispositivos.",style=MaterialTheme.typography.bodyMedium)
                        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("Sesiones activas: $active",style=MaterialTheme.typography.titleLarge)
                            Text(if(sync.sessions.isEmpty()) "Todavía no hay dispositivos conectados." else "Se cuentan solo dispositivos vistos durante los últimos 3 minutos.")
                            sync.sessions.filter { !it.revoked }.forEach { device -> Text("• ${device.name} · ${device.platform}",style=MaterialTheme.typography.bodySmall) }
                        } }
                        Text("Cuenta",style=MaterialTheme.typography.titleMedium)
                        Text(if(sync.email.isBlank()) "Sin cuenta conectada. La nube no está activa y tus macros siguen solo en este dispositivo." else "${sync.email}\n${sync.message}",style=MaterialTheme.typography.bodyMedium)
                        var email by remember(sync.email) { mutableStateOf(sync.email) }
                        if(sync.phase==SyncPhase.CONNECTED) {
                            Text("Esta es una cuenta independiente. Tus amigos pueden usar sus propios correos y no verán tus proyectos.",style=MaterialTheme.typography.bodySmall)
                            Text("Proyectos sincronizados",style=MaterialTheme.typography.titleMedium)
                            Button(onClick={Studio.sync.uploadProject(Studio.project) { error -> if(error==null) Studio.log("Proyecto subido a la nube") else Studio.fail(error) }},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Subir proyecto actual") }
                            OutlinedButton(onClick={Studio.sync.refreshCloudProjects()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Actualizar lista") }
                            if(Studio.sync.cloudProjects.isEmpty()) {
                                Text("Todavía no hay proyectos en esta cuenta. Sube el proyecto actual para verlo desde otro dispositivo.",style=MaterialTheme.typography.bodySmall)
                            } else {
                                Studio.sync.cloudProjects.forEach { cloud ->
                                    Card { Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(cloud.name,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                            Text("Actualizado ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(cloud.updatedAtMs))}",style=MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick={Studio.sync.downloadProject(cloud.id) { payload,error ->
                                            if(error!=null) Studio.fail(error) else if(payload.isNullOrBlank()) Studio.fail("El proyecto no tiene contenido") else runCatching { importProjectPayload(activity,payload.toByteArray(),"nube") }.onFailure { Studio.fail("Descarga rechazada: ${it.message}") }
                                        }},enabled=!busy) { Text("Descargar") }
                                    } }
                                }
                            }
                            OutlinedButton(onClick={Studio.sync.signOut()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Cerrar sesión y cambiar de cuenta") }
                        } else {
                            OutlinedTextField(value=email,onValueChange={email=it.take(160)},label={Text("Correo de la cuenta")},singleLine=true,modifier=Modifier.fillMaxWidth())
                            Button(onClick={Studio.sync.sendEmailLink(email) { error -> if(error==null) Studio.log("Enlace de acceso enviado") else Studio.fail(error) }},enabled=email.isNotBlank() && sync.phase!=SyncPhase.WAITING_FOR_CODE,modifier=Modifier.fillMaxWidth()) { Text(if(sync.phase==SyncPhase.WAITING_FOR_CODE) "Enlace enviado · revisa tu correo" else "Enviar enlace de acceso") }
                            Text("Cada correo crea o abre su propia cuenta. El enlace recibido es de un solo uso y caduca por seguridad; para volver a entrar se solicita otro. No se guarda ninguna contraseña.",style=MaterialTheme.typography.bodySmall)
                            if(sync.phase!=SyncPhase.NOT_CONFIGURED) TextButton(onClick={Studio.sync.clearLocalSetup()}) { Text("Cancelar y usar otro correo") }
                        }
                        Text("La app solo sincroniza después de validar la cuenta. Cuando Firebase esté habilitado, aquí aparecerán los dispositivos conectados y su última actividad.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } }
        if(showKinds) KindPickerDialog(
            onlyConditions=onlyConditions,
            onDismiss={showKinds=false},
            onChoose={kind ->
                val label=kindLabel(kind)
                val a=Action(kind=kind,name=label,durationMs=when(kind) { Kind.WAIT -> 1000; Kind.WAIT_IMAGE -> 60_000; Kind.CLOSE_ACTIVE -> 1000; else -> 80 })
                val list=when {
                    addBefore!=null -> insertBefore(p.actions,addBefore!!,a)
                    addAfter!=null -> insertAfter(p.actions,addAfter!!,a)
                    addParent==null -> p.actions+a
                    else -> updateAction(p.actions,addParent!!) { if(addElse) it.copy(otherwise=it.otherwise+a) else it.copy(children=it.children+a) }
                }
                if(Studio.update(p.copy(actions=list))) { draftActionId=a.id; edit=a }
                showKinds=false
            }
        )
        edit?.let { a -> ActionDialog(
            a,
            onDismiss={
                if(draftActionId==a.id) Studio.update(Studio.project.copy(actions=updateAction(Studio.project.actions,a.id){null}))
                draftActionId=null
                edit=null
            },
            onOpenImages={draftActionId=null;Studio.pendingTemplateActionId=a.id;edit=null;tab=1},
            onImportImage={draftActionId=null;Studio.pendingTemplateActionId=null;importingForAction=a.id;edit=null;pickImage.launch(arrayOf("image/*"))},
            onPickPoint={actionId ->
                val service=MacroAccessibilityService.instance
                if(service==null || !Studio.accessibilityReady) Studio.fail("Activa Accesibilidad desde el Laboratorio antes de elegir un punto")
                else { draftActionId=null; edit=null; service.startPointPicker(actionId) }
            }
        ) { edited ->
            Studio.update(Studio.project.copy(actions=updateAction(Studio.project.actions,a.id){edited}))
            draftActionId=null
            edit=null
        } }
        if(rename) { var name by remember {mutableStateOf(p.name)}; AlertDialog(onDismissRequest={rename=false},title={Text("Nombre del proyecto")},text={OutlinedTextField(value=name,onValueChange={name=it.take(100)})},confirmButton={TextButton(onClick={Studio.update(p.copy(name=name.trim())); rename=false},enabled=name.isNotBlank()) {Text("Guardar")}}) }
        renameImage?.let { saved -> var name by remember(saved.id) { mutableStateOf(saved.name) }; AlertDialog(onDismissRequest={renameImage=null},title={Text("Nombre de la captura")},text={OutlinedTextField(value=name,onValueChange={name=it.take(100)},label={Text("Nombre")})},confirmButton={TextButton(onClick={Studio.update(Studio.project.copy(templates=Studio.project.templates.map { if(it.id==saved.id) it.copy(name=name.trim()) else it })); renameImage=null},enabled=name.isNotBlank()) {Text("Guardar")}},dismissButton={TextButton(onClick={renameImage=null}) {Text("Cancelar")}}) }
        deleteImage?.let { saved -> AlertDialog(onDismissRequest={deleteImage=null},title={Text("Borrar captura")},text={Text("¿Borrar ${saved.name}? Esta acción no se puede deshacer.")},confirmButton={TextButton(onClick={val project=Studio.project; val next=project.templates.filterNot { it.id==saved.id }; val principal=if(project.template?.file==saved.template.file) next.firstOrNull()?.template else project.template; if(Studio.update(project.copy(template=principal,templates=next))) File(activity.filesDir,saved.template.file).delete(); deleteImage=null}) {Text("Borrar")}},dismissButton={TextButton(onClick={deleteImage=null}) {Text("Cancelar")}}) }
        if(projectMenu) AlertDialog(onDismissRequest={projectMenu=false},title={Text("Mis proyectos")},text={ Column(Modifier.verticalScroll(rememberScrollState())) { Studio.projects.forEach { saved -> TextButton(onClick={Studio.switch(saved);projectMenu=false}) {Text(saved.name)} } } },confirmButton={TextButton(onClick={projectMenu=false}) {Text("Cerrar")}})
        if(showHelp) AlertDialog(onDismissRequest={showHelp=false},title={Text("Inicio rápido")},text={Text("Empieza en el Laboratorio del Grabador. Autoriza captura de pantalla completa, activa accesibilidad y usa el panel flotante. Guarda varios recortes con nombre y graba tus gestos. En el editor, cada bloque SI SE VE tiene su propio botón Elegir / cambiar imagen. La imagen de inicio rápido de la pestaña Imagen es una opción separada y no cambia las capturas de las condiciones.\n\nPARAR impide nuevos pasos; un gesto ya enviado puede terminar. No pruebes sobre compras, mensajes o partidas competitivas. Guarda una copia JSON antes de cambios importantes.")},confirmButton={TextButton(onClick={showHelp=false}){Text("Entendido")}})
    }
}

@Composable private fun KindPickerDialog(onlyConditions: Boolean,onDismiss: () -> Unit,onChoose: (Kind) -> Unit) {
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(if(onlyConditions) "Añadir condición" else "Añadir paso")},
        text={
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if(onlyConditions) "Elige qué debe comprobar este paso. Después podrás seleccionar su captura o color y añadir acciones dentro de ENTONCES y SI NO."
                    else "Elige qué quieres añadir. Las opciones están agrupadas para que encuentres rápido la acción que necesitas.",
                    style=MaterialTheme.typography.bodyMedium
                )
                if(!onlyConditions) {
                    KindSection("Gestos e imágenes",listOf(Kind.GESTURE,Kind.CLICK_IMAGE),onChoose)
                    KindSection("Condiciones",listOf(Kind.IF_IMAGE,Kind.IF_COLOR),onChoose)
                    KindSection("Secuencia",listOf(Kind.WAIT_IMAGE,Kind.WAIT,Kind.REPEAT),onChoose)
                    KindSection("Dispositivo y aplicación",listOf(Kind.OPEN_APP,Kind.BACK,Kind.HOME,Kind.RECENTS,Kind.CLOSE_ACTIVE,Kind.NOTIFICATIONS,Kind.QUICK_SETTINGS),onChoose)
                } else {
                    KindSection("Condiciones",listOf(Kind.IF_IMAGE,Kind.IF_COLOR),onChoose)
                }
            }
        },
        confirmButton={TextButton(onClick=onDismiss) { Text("Cerrar") }}
    )
}

@Composable private fun KindSection(title: String,kinds: List<Kind>,onChoose: (Kind) -> Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
        kinds.chunked(2).forEach { rowKinds ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                rowKinds.forEach { kind ->
                    FilledTonalButton(
                        onClick={onChoose(kind)},
                        modifier=Modifier.weight(1f).heightIn(min=58.dp),
                        contentPadding=PaddingValues(horizontal=10.dp,vertical=8.dp)
                    ) {
                        Column(Modifier.fillMaxWidth(),horizontalAlignment=androidx.compose.ui.Alignment.Start) {
                            Text(kindPickerLabel(kind),maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(kindHint(kind),style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                if(rowKinds.size==1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun kindHint(kind: Kind): String = when(kind) {
    Kind.GESTURE -> "Indica X/Y o varios puntos"
    Kind.CLICK_IMAGE -> "Elige captura y dónde pulsar"
    Kind.WAIT_IMAGE -> "Espera hasta que aparezca una captura"
    Kind.IF_IMAGE -> "Si aparece una captura"
    Kind.IF_COLOR -> "Si aparece un color"
    Kind.WAIT -> "Espera un tiempo"
    Kind.REPEAT -> "Repite varios pasos"
    Kind.OPEN_APP -> "Abre una aplicación"
    Kind.BACK -> "Vuelve atrás"
    Kind.HOME -> "Sale a la pantalla de inicio"
    Kind.RECENTS -> "Abre aplicaciones recientes"
    Kind.CLOSE_ACTIVE -> "Cierra la tarjeta activa"
    Kind.NOTIFICATIONS -> "Despliega notificaciones"
    Kind.QUICK_SETTINGS -> "Despliega ajustes rápidos"
}

private fun kindPickerLabel(kind: Kind): String = when(kind) {
    Kind.GESTURE -> "Clic / arrastre en punto"
    Kind.CLICK_IMAGE -> "Detectar imagen y hacer clic"
    Kind.WAIT_IMAGE -> "Esperar hasta ver una imagen"
    Kind.IF_IMAGE -> "SI SE VE UNA IMAGEN"
    Kind.IF_COLOR -> "SI SE VE UN COLOR"
    Kind.WAIT -> "Esperar"
    Kind.REPEAT -> "Repetir pasos"
    Kind.OPEN_APP -> "Abrir aplicación"
    Kind.BACK -> "Volver atrás"
    Kind.HOME -> "Ir al inicio"
    Kind.RECENTS -> "Aplicaciones recientes"
    Kind.CLOSE_ACTIVE -> "Cerrar aplicación"
    Kind.NOTIFICATIONS -> "Notificaciones"
    Kind.QUICK_SETTINGS -> "Ajustes rápidos"
}

@Composable private fun NumberField(label: String, value: Long, min: Long,max: Long,enabled: Boolean=true,onSave: (Long)->Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed=text.toLongOrNull(); val valid=parsed!=null && parsed in min..max
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value=text,onValueChange={text=it.take(12)},label={Text(label)},isError=!valid,enabled=enabled,singleLine=true,modifier=Modifier.weight(1f))
        TextButton(onClick={onSave(parsed!!)},enabled=enabled && valid && parsed!=value) {Text("Aplicar")}
    }
}

@Composable private fun ActionList(actions: List<Action>,busy: Boolean,edit: (Action)->Unit,add: (String,Boolean)->Unit,addAfter: (Action)->Unit,addBefore: (Action)->Unit,depth: Int=0,compact: Boolean=false) {
    actions.forEach { a -> key(a.id) {
        var expanded by remember(a.id) { mutableStateOf(false) }
        val isCondition=a.kind==Kind.IF_IMAGE || a.kind==Kind.IF_COLOR
        val isImageStep=a.kind==Kind.IF_IMAGE || a.kind==Kind.CLICK_IMAGE || a.kind==Kind.WAIT_IMAGE
        Card(Modifier.fillMaxWidth().padding(start=(depth*(if(compact) 6 else 8)).dp),colors=CardDefaults.cardColors(containerColor=if(Studio.currentAction==a.id) Color(0xFF244D51) else MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(horizontal=if(compact) 7.dp else 10.dp,vertical=if(compact) 3.dp else 6.dp),verticalArrangement=Arrangement.spacedBy(if(compact) 1.dp else 3.dp)) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.Start) {
                    Checkbox(a.enabled,onCheckedChange={v -> Studio.update(Studio.project.copy(actions=updateAction(Studio.project.actions,a.id){it.copy(enabled=v)}))},enabled=!busy)
                    Column(Modifier.weight(1f)) {
                        Text(a.name,maxLines=if(compact) 1 else 2,overflow=TextOverflow.Ellipsis)
                        Text(kindLabel(a.kind),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    TextButton(onClick={edit(a)},enabled=!busy,contentPadding=PaddingValues(horizontal=7.dp,vertical=2.dp)) { Text("Editar") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick={Engine.start(false,true,listOf(a))},enabled=!busy,contentPadding=PaddingValues(horizontal=7.dp,vertical=2.dp)) { Text("▶ Probar") }
                    if(compact || a.kind==Kind.REPEAT || isCondition) TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(horizontal=7.dp,vertical=2.dp),modifier=Modifier.semantics { contentDescription="${if(expanded) "Ocultar" else "Detalles"} de ${a.name}" }) {Text(if(expanded) "Ocultar" else if(compact) "Opciones" else "Detalles")}
                    var moreOpen by remember(a.id) { mutableStateOf(false) }
                    Box {
                        TextButton(onClick={moreOpen=true},enabled=!busy,contentPadding=PaddingValues(horizontal=7.dp,vertical=2.dp),modifier=Modifier.semantics { contentDescription="Más opciones de ${a.name}" }) { Text("⋮ Más") }
                        DropdownMenu(expanded=moreOpen,onDismissRequest={moreOpen=false}) {
                            DropdownMenuItem(
                                text={Text("Editar acción")},
                                onClick={moreOpen=false; edit(a)}
                            )
                            if(isImageStep) {
                                DropdownMenuItem(
                                    text={Text("Cambiar imagen")},
                                    onClick={moreOpen=false; edit(a)}
                                )
                            }
                            if(a.kind==Kind.CLICK_IMAGE || a.kind==Kind.GESTURE) {
                                DropdownMenuItem(
                                    text={Text("Editar clic / coordenadas")},
                                    onClick={moreOpen=false; edit(a)}
                                )
                            }
                            DropdownMenuItem(
                                text={Text("▶ Probar acción")},
                                onClick={moreOpen=false; Engine.start(false,true,listOf(a))}
                            )
                            if(isCondition || a.kind==Kind.REPEAT) {
                                DropdownMenuItem(
                                    text={Text("＋ Añadir acción dentro")},
                                    onClick={moreOpen=false; add(a.id,false)}
                                )
                            }
                            DropdownMenuItem(
                                text={Text("＋ Añadir condición después")},
                                onClick={moreOpen=false; addAfter(a)}
                            )
                            DropdownMenuItem(
                                text={Text("＋ Añadir acción encima")},
                                onClick={moreOpen=false; addBefore(a)}
                            )
                            DropdownMenuItem(
                                text={Text("Duplicar en esta posición")},
                                onClick={moreOpen=false; Studio.update(Studio.project.copy(actions=duplicateAfter(Studio.project.actions,a.id)))}
                            )
                            DropdownMenuItem(
                                text={Text("Borrar")},
                                onClick={moreOpen=false; Studio.update(Studio.project.copy(actions=updateAction(Studio.project.actions,a.id){null}))}
                            )
                        }
                    }
                }
                if(a.kind==Kind.CLICK_IMAGE) {
                    val assigned=Studio.project.templates.firstOrNull { it.id==a.templateId }
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        SavedTemplatePreview(assigned,Modifier.size(if(compact) 48.dp else 64.dp,if(compact) 34.dp else 44.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Clic en: ${assigned?.name ?: "NINGUNA"}",maxLines=1)
                            Text(if(assigned==null) "Falta elegir imagen" else "${clickTargetLabel(a.clickTarget)} · ${clickStyleLabel(a.clickStyle)}",style=MaterialTheme.typography.bodySmall,maxLines=2)
                        }
                    }
                    if(expanded) FilledTonalButton(onClick={edit(a)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(if(assigned==null) "Elegir imagen para este clic" else "Cambiar imagen y clic") }
                } else if(a.kind==Kind.IF_IMAGE || a.kind==Kind.WAIT_IMAGE) {
                    val assigned=Studio.project.templates.firstOrNull { it.id==a.templateId }
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        SavedTemplatePreview(assigned,Modifier.size(if(compact) 48.dp else 64.dp,if(compact) 34.dp else 44.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if(a.kind==Kind.WAIT_IMAGE) "Esperar: ${assigned?.name ?: "NINGUNA"}" else "Captura: ${assigned?.name ?: "NINGUNA"}",maxLines=1)
                            Text(if(assigned==null) "Falta elegir imagen" else if(a.kind==Kind.WAIT_IMAGE) "Máximo ${readableDuration(a.durationMs)} · cualquier punto" else "${assigned.template.width}×${assigned.template.height} px · Entonces ${a.children.size} · Si no ${a.otherwise.size}",style=MaterialTheme.typography.bodySmall,maxLines=2)
                        }
                    }
                    if(expanded) FilledTonalButton(onClick={edit(a)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(if(assigned==null) "Elegir imagen para este paso" else "Cambiar imagen de este paso") }
                } else if(a.kind==Kind.IF_COLOR) {
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(44.dp).background(Color(a.targetColor)))
                        Column(Modifier.weight(1f)) { Text("Color: ${rgbHex(a.targetColor)}"); Text("Tolerancia ${a.colorTolerance} · ${if(a.colorMode==SearchMode.ANYWHERE) "cualquier punto" else "punto exacto"} · Entonces ${a.children.size} · Si no ${a.otherwise.size}",style=MaterialTheme.typography.bodySmall,maxLines=2) }
                    }
                    if(expanded) FilledTonalButton(onClick={edit(a)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Cambiar color de esta condición") }
                } else Text(when(a.kind) { Kind.WAIT -> "${a.durationMs} ms"; Kind.GESTURE -> "${a.points.size} punto(s) · ${a.durationMs} ms"; Kind.REPEAT -> "Repetir ${a.count} veces"; Kind.OPEN_APP -> a.packageName; Kind.CLOSE_ACTIVE -> "Recientes · espera ${a.durationMs} ms · desliza · Inicio"; else -> kindLabel(a.kind) },style=MaterialTheme.typography.bodySmall)
                if(isCondition) {
                    ConditionFlowSummary(a)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick={add(a.id,false)},
                            enabled=!busy && depth<7,
                            modifier=Modifier.weight(1f),
                            contentPadding=PaddingValues(horizontal=8.dp,vertical=6.dp)
                        ) {
                            Text("＋ Añadir acción en ENTONCES",maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick={add(a.id,true)},
                            enabled=!busy && depth<7,
                            modifier=Modifier.weight(1f),
                            contentPadding=PaddingValues(horizontal=8.dp,vertical=6.dp)
                        ) {
                            Text("＋ Añadir acción en SI NO",maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                    Text("SI NO vacío = continuar con el siguiente paso",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(a.description.isNotBlank() && (!compact || expanded)) Text(a.description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=if(expanded) Int.MAX_VALUE else 2)
                if(!compact || expanded) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    val padding=PaddingValues(horizontal=8.dp,vertical=2.dp)
                    TextButton(onClick={Studio.update(Studio.project.copy(actions=moveAction(Studio.project.actions,a.id,-1)))},enabled=!busy,contentPadding=padding) {Text("↑")}
                    TextButton(onClick={Studio.update(Studio.project.copy(actions=moveAction(Studio.project.actions,a.id,1)))},enabled=!busy,contentPadding=padding) {Text("↓")}
                    TextButton(onClick={Studio.update(Studio.project.copy(actions=duplicateAfter(Studio.project.actions,a.id)))},enabled=!busy,contentPadding=padding) {Text("Duplicar")}
                    if(!compact) TextButton(onClick={addAfter(a)},enabled=!busy,contentPadding=padding) {Text("+ Condición")}
                    TextButton(onClick={Engine.start(false,true,listOf(a))},enabled=!busy,contentPadding=padding) {Text("Simular")}
                    TextButton(onClick={Studio.update(Studio.project.copy(actions=updateAction(Studio.project.actions,a.id){null}))},enabled=!busy,contentPadding=padding) {Text("Quitar")}
                }
                if(compact) {
                    TextButton(onClick={addAfter(a)},enabled=!busy,contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp),modifier=Modifier.semantics { contentDescription="+ Condición" }) { Text("+ Condición") }
                }
                if(expanded && (a.kind==Kind.REPEAT || isCondition)) {
                    if(isCondition) Text(if(a.kind==Kind.IF_IMAGE) "ENTONCES · si encuentra la imagen" else "ENTONCES · si encuentra el color")
                    ActionList(a.children,busy,edit,add,addAfter,addBefore,depth+1,compact)
                    TextButton(onClick={add(a.id,false)},enabled=!busy && depth<7) {Text("+ Añadir otro paso en ENTONCES")}
                    if(isCondition) { Text("SI NO · opcional; vacío = continuar con el siguiente paso"); ActionList(a.otherwise,busy,edit,add,addAfter,addBefore,depth+1,compact); TextButton(onClick={add(a.id,true)},enabled=!busy && depth<7) {Text(if(a.kind==Kind.IF_IMAGE) "+ Añadir paso si NO encuentra la imagen" else "+ Añadir paso si NO encuentra el color")} }
                }
            }
        }
    } }
}

@Composable private fun ConditionFlowSummary(action: Action) {
    fun branch(items: List<Action>, empty: String): String {
        val active=items.filter { it.enabled }
        if(active.isEmpty()) return empty
        val shown=active.take(3).joinToString("  →  ") { readableActionSummary(it) }
        return if(active.size>3) "$shown  +${active.size-3}" else shown
    }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha=.35f),RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement=Arrangement.spacedBy(3.dp)
    ) {
        Text("ENTONCES: ${branch(action.children,"sin acciones")}",style=MaterialTheme.typography.bodySmall)
        Text("SI NO: ${branch(action.otherwise,"continuar con el siguiente paso")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun readableActionSummary(action: Action): String = when(action.kind) {
    Kind.WAIT -> "esperar ${readableDuration(action.durationMs)}"
    Kind.OPEN_APP -> "abrir ${action.name.removePrefix("Abrir ").ifBlank { "aplicación" }}"
    Kind.CLOSE_ACTIVE -> "cerrar aplicación"
    Kind.GESTURE -> if(action.points.size<=1) "pulsar" else "hacer trayectoria"
    Kind.CLICK_IMAGE -> "clic en imagen"
    Kind.WAIT_IMAGE -> "esperar hasta ver imagen"
    Kind.IF_IMAGE -> "comprobar imagen"
    Kind.IF_COLOR -> "comprobar color"
    Kind.REPEAT -> "repetir ${action.count} veces"
    Kind.HOME -> "ir al inicio"
    Kind.BACK -> "volver"
    Kind.RECENTS -> "abrir recientes"
    Kind.NOTIFICATIONS -> "abrir notificaciones"
    Kind.QUICK_SETTINGS -> "abrir ajustes rápidos"
}

private fun readableDuration(ms: Long): String = when {
    ms%60_000L==0L -> "${ms/60_000L} min"
    ms%1_000L==0L -> "${ms/1_000L} s"
    else -> "$ms ms"
}

@Composable private fun ActionDialog(a: Action,onDismiss: ()->Unit,onOpenImages: ()->Unit,onImportImage: ()->Unit,onPickPoint: (String)->Unit,onSave: (Action)->Unit) {
    var packageName by remember {mutableStateOf(a.packageName)}
    // Nunca elijas una captura por el usuario. Una condición nueva empieza realmente
    // sin imagen aunque el proyecto ya tenga una biblioteca con varias entradas.
    var selectedTemplateId by remember(a.id,a.templateId) {mutableStateOf(a.templateId)}
    var templateQuery by remember(a.id) {mutableStateOf("")}
    var chooseApp by remember {mutableStateOf(false)}
    var showOtherProjectCaptures by remember(a.id) {mutableStateOf(false)}
    val context=androidx.compose.ui.platform.LocalContext.current
    if(chooseApp) {
        val apps=remember {context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).distinctBy {it.activityInfo.packageName}.sortedBy {it.loadLabel(context.packageManager).toString()}}
        var appQuery by remember {mutableStateOf("")}
        val matchingApps=apps.filter { appQuery.isBlank() || it.loadLabel(context.packageManager).toString().contains(appQuery,ignoreCase=true) || it.activityInfo.packageName.contains(appQuery,ignoreCase=true) }
        AlertDialog(onDismissRequest={chooseApp=false},title={Text("Elegir aplicación")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(value=appQuery,onValueChange={appQuery=it.take(100)},label={Text("Buscar aplicación o paquete")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(matchingApps.isEmpty()) Text("No hay coincidencias instaladas. Puedes escribir el paquete manualmente.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            matchingApps.forEach {info -> TextButton(onClick={packageName=info.activityInfo.packageName; chooseApp=false},modifier=Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth(),horizontalAlignment=androidx.compose.ui.Alignment.Start) { Text(info.loadLabel(context.packageManager).toString()); Text(info.activityInfo.packageName,style=MaterialTheme.typography.bodySmall) } } }
        }},confirmButton={TextButton(onClick={chooseApp=false}) {Text("Cerrar")}})
    }
    var name by remember {mutableStateOf(a.name)}; var description by remember {mutableStateOf(a.description)}; var duration by remember {mutableStateOf(a.durationMs.toString())}; var count by remember {mutableStateOf(a.count.toString())}
    var x by remember {mutableStateOf(((a.points.firstOrNull()?.x ?: .5f)*100).toString())}; var y by remember {mutableStateOf(((a.points.firstOrNull()?.y ?: .5f)*100).toString())}
    var colorHex by remember {mutableStateOf(rgbHex(a.targetColor))}; var colorTolerance by remember {mutableStateOf(a.colorTolerance.toString())}; var colorMode by remember {mutableStateOf(a.colorMode)}
    var colorX by remember {mutableStateOf((a.colorX*100).toString())}; var colorY by remember {mutableStateOf((a.colorY*100).toString())}
    var clickTarget by remember {mutableStateOf(a.clickTarget)}; var clickStyle by remember {mutableStateOf(a.clickStyle)}
    var clickOffsetX by remember {mutableStateOf(a.clickOffsetX.toString())}; var clickOffsetY by remember {mutableStateOf(a.clickOffsetY.toString())}
    val d=duration.toLongOrNull(); val c=count.toIntOrNull(); val px=x.toFloatOrNull(); val py=y.toFloatOrNull()
    val parsedColor=parseRgbHex(colorHex); val tolerance=colorTolerance.toIntOrNull(); val cpx=colorX.toFloatOrNull(); val cpy=colorY.toFloatOrNull(); val clickX=clickOffsetX.toFloatOrNull(); val clickY=clickOffsetY.toFloatOrNull()
    val validColor=a.kind!=Kind.IF_COLOR || (parsedColor!=null && tolerance!=null && tolerance in 0..255 && cpx!=null && cpy!=null && cpx.isFinite() && cpy.isFinite() && cpx in 0f..100f && cpy in 0f..100f)
    val validClick=a.kind!=Kind.CLICK_IMAGE || when(clickTarget) {
        ClickTarget.CENTER -> true
        ClickTarget.OFFSET -> clickX!=null && clickY!=null && clickX.isFinite() && clickY.isFinite() && clickX in -100f..100f && clickY in -100f..100f
        ClickTarget.FIXED -> clickX!=null && clickY!=null && clickX.isFinite() && clickY.isFinite() && clickX in 0f..100f && clickY in 0f..100f
    }
    val validDuration=d!=null && when(a.kind) { Kind.GESTURE -> d in 1..60_000; Kind.CLICK_IMAGE -> d in 1..60_000 && (clickStyle!=ClickStyle.LONG || d>=500); Kind.WAIT_IMAGE -> d in 1_000..600_000; Kind.CLOSE_ACTIVE -> d in 500..3_000; else -> d in 1..600_000 }
    val validPackage=a.kind!=Kind.OPEN_APP || packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
    val valid=name.isNotBlank() && validDuration && c!=null && c in 1..100 && px!=null && py!=null && px.isFinite() && py.isFinite() && px in 0f..100f && py in 0f..100f && validColor && validClick && validPackage
    val selectedImageName=Studio.project.templates.firstOrNull { it.id==selectedTemplateId }?.name
    val selectedTemplate=Studio.project.templates.firstOrNull { it.id==selectedTemplateId }
    val normalizedQuery=templateQuery.trim()
    val matchingTemplates=Studio.project.templates.filter { normalizedQuery.isEmpty() || it.name.contains(normalizedQuery,ignoreCase=true) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Editar instrucción")},text={Column(Modifier.semantics {
        if(a.kind==Kind.IF_IMAGE || a.kind==Kind.CLICK_IMAGE || a.kind==Kind.WAIT_IMAGE) contentDescription="Imagen elegida: ${selectedImageName ?: "NINGUNA"}"
    }.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(a.kind==Kind.IF_IMAGE || a.kind==Kind.CLICK_IMAGE || a.kind==Kind.WAIT_IMAGE) {
            Text(when(a.kind) { Kind.CLICK_IMAGE -> "Imagen que debe encontrar para hacer clic"; Kind.WAIT_IMAGE -> "Imagen que debe aparecer para continuar"; else -> "Captura de esta condición" },style=MaterialTheme.typography.titleMedium)
            Text("Elige una captura solo para este paso. No necesita ser la imagen de inicio rápido y no cambiará las demás condiciones.",style=MaterialTheme.typography.bodySmall)
            Text("Imagen elegida ahora",style=MaterialTheme.typography.labelLarge)
            if(selectedTemplate==null) Text("NINGUNA · elige una captura antes de guardar",color=MaterialTheme.colorScheme.error)
            else Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    SavedTemplatePreview(selectedTemplate,Modifier.size(80.dp,54.dp))
                    Column(Modifier.weight(1f)) { Text(selectedTemplate.name,maxLines=2); Text("${selectedTemplate.template.width}×${selectedTemplate.template.height} px",style=MaterialTheme.typography.bodySmall) }
                    Text("ELEGIDA",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                }
            }
            Text("Biblioteca de este proyecto · ${Studio.project.templates.size} captura(s)",style=MaterialTheme.typography.labelLarge)
            if(Studio.project.templates.isEmpty()) Text("Este proyecto todavía no tiene capturas. Puedes crear una, traerla desde Mac/Galería o copiarla de otro proyecto.",color=MaterialTheme.colorScheme.error)
            else {
                OutlinedTextField(value=templateQuery,onValueChange={templateQuery=it.take(100)},label={Text("Buscar captura por nombre")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text("${matchingTemplates.size} resultado(s)",style=MaterialTheme.typography.bodySmall)
                if(matchingTemplates.isEmpty()) Text("No hay capturas que coincidan. Borra parte del texto o crea una nueva.",color=MaterialTheme.colorScheme.error)
                Text("Pulsa «ELEGIR» en la captura que quieras usar en este paso. La actual también aparece para que puedas comprobarla.",style=MaterialTheme.typography.bodySmall)
                matchingTemplates.forEach { saved ->
                    val isSelected=saved.id==selectedTemplateId
                    Card(
                        Modifier.fillMaxWidth().clickable { selectedTemplateId=saved.id },
                        colors=CardDefaults.cardColors(containerColor=if(isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    ) { Row(Modifier.padding(6.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        RadioButton(selected=isSelected,onClick={selectedTemplateId=saved.id})
                        SavedTemplatePreview(saved,Modifier.size(64.dp,44.dp))
                        Column(Modifier.weight(1f)) { Text(saved.name,maxLines=1); Text("${saved.template.width}×${saved.template.height} px",style=MaterialTheme.typography.bodySmall) }
                        Text(if(isSelected) "ELEGIDA" else "ELEGIR",style=MaterialTheme.typography.labelSmall,color=if(isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                }
            }
            OutlinedButton(onClick=onOpenImages,modifier=Modifier.fillMaxWidth()) { Text("＋ Hacer una captura nueva y recortarla") }
            OutlinedButton(onClick=onImportImage,modifier=Modifier.fillMaxWidth()) { Text("Traer imagen desde Archivos / Galería / Mac") }
            Text("En BlueStacks, elige “Pick From Mac”. Ese selector es externo y no muestra la biblioteca interna de PrisonMacro.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
            val otherProjects=Studio.projects.filter { source ->
                source.id!=Studio.project.id && source.templates.any { saved -> normalizedQuery.isEmpty() || saved.name.contains(normalizedQuery,ignoreCase=true) }
            }
            if(otherProjects.isNotEmpty()) {
                OutlinedButton(onClick={showOtherProjectCaptures=!showOtherProjectCaptures},modifier=Modifier.fillMaxWidth()) {
                    Text(if(showOtherProjectCaptures) "Ocultar capturas de otros proyectos" else "Mostrar capturas de otros proyectos (${otherProjects.sumOf { it.templates.count { saved -> normalizedQuery.isEmpty() || saved.name.contains(normalizedQuery,ignoreCase=true) } }})")
                }
                if(showOtherProjectCaptures) otherProjects.forEach { source ->
                    Text("Capturas de otro proyecto · ${source.name}",style=MaterialTheme.typography.titleMedium)
                    source.templates.filter { normalizedQuery.isEmpty() || it.name.contains(normalizedQuery,ignoreCase=true) }.forEach { saved ->
                        OutlinedButton(onClick={
                            runCatching {
                                val newFile="template-${UUID.randomUUID()}.png"
                                File(context.filesDir,saved.template.file).copyTo(File(context.filesDir,newFile))
                                val imported=saved.copy(id=UUID.randomUUID().toString(),template=saved.template.copy(file=newFile))
                                if(Studio.update(Studio.project.copy(templates=Studio.project.templates+imported))) selectedTemplateId=imported.id
                                else File(context.filesDir,newFile).delete()
                            }.onFailure { Studio.fail("No se pudo traer la captura: ${it.message}") }
                        },modifier=Modifier.fillMaxWidth()) {
                            SavedTemplatePreview(saved,Modifier.size(64.dp,44.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copiar y usar ${saved.name}")
                        }
                    }
                }
            }
            Text("Imagen elegida: ${selectedTemplate?.name ?: "NINGUNA"}",style=MaterialTheme.typography.labelLarge,color=if(selectedTemplate==null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick={context.startActivity(Intent(context,es.macrostudio.editor.CropActivity::class.java).putExtra("templateId",selectedTemplateId))},enabled=selectedTemplateId.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("Recortar esta imagen") }
        }
        if(a.kind==Kind.CLICK_IMAGE) {
            Text("Qué hacer al encontrarla",style=MaterialTheme.typography.titleMedium)
            Text("La macro buscará la captura hasta el tiempo máximo del proyecto y, al encontrarla, hará el clic configurado.",style=MaterialTheme.typography.bodySmall)
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                FilterChip(selected=clickTarget==ClickTarget.CENTER,onClick={clickTarget=ClickTarget.CENTER},label={Text("Centro")})
                FilterChip(selected=clickTarget==ClickTarget.OFFSET,onClick={clickTarget=ClickTarget.OFFSET},label={Text("Desplazamiento")})
                FilterChip(selected=clickTarget==ClickTarget.FIXED,onClick={clickTarget=ClickTarget.FIXED},label={Text("Punto fijo")})
            }
            if(clickTarget==ClickTarget.OFFSET) {
                Text("Desplazamiento relativo al tamaño de la captura · -100 a 100 %",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value=clickOffsetX,onValueChange={clickOffsetX=it.take(8)},label={Text("X (%)")},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(value=clickOffsetY,onValueChange={clickOffsetY=it.take(8)},label={Text("Y (%)")},singleLine=true,modifier=Modifier.weight(1f))
                }
            }
            if(clickTarget==ClickTarget.FIXED) {
                Text("Punto fijo de toda la pantalla · 0 arriba/izquierda, 100 abajo/derecha",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value=clickOffsetX,onValueChange={clickOffsetX=it.take(8)},label={Text("Punto X (%)")},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(value=clickOffsetY,onValueChange={clickOffsetY=it.take(8)},label={Text("Punto Y (%)")},singleLine=true,modifier=Modifier.weight(1f))
                }
            }
            Text("Tipo de clic",style=MaterialTheme.typography.labelLarge)
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                FilterChip(selected=clickStyle==ClickStyle.SINGLE,onClick={clickStyle=ClickStyle.SINGLE},label={Text("Un clic")})
                FilterChip(selected=clickStyle==ClickStyle.DOUBLE,onClick={clickStyle=ClickStyle.DOUBLE},label={Text("Doble clic")})
                FilterChip(selected=clickStyle==ClickStyle.LONG,onClick={clickStyle=ClickStyle.LONG},label={Text("Pulsación larga")})
            }
            OutlinedTextField(value=duration,onValueChange={duration=it.take(8)},label={Text(if(clickStyle==ClickStyle.LONG) "Duración de la pulsación (ms · mínimo 500)" else "Duración del clic (ms)")},singleLine=true)
            OutlinedButton(onClick={onPickPoint(a.id)},enabled=Studio.accessibilityReady,modifier=Modifier.fillMaxWidth()) {
                Text(if(clickTarget==ClickTarget.FIXED) "Volver a elegir punto fijo en pantalla" else "Elegir punto tocando la pantalla")
            }
        }
        if(a.kind==Kind.IF_COLOR) {
            Text("Color de esta condición",style=MaterialTheme.typography.titleMedium)
            Text("Escribe el color RGB en hexadecimal. La tolerancia admite pequeñas variaciones de brillo.",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(58.dp).background(Color(parsedColor ?: 0xFF000000.toInt())))
                OutlinedTextField(value=colorHex,onValueChange={colorHex=it.take(7).uppercase()},label={Text("Color, por ejemplo #FF5500")},singleLine=true,modifier=Modifier.weight(1f))
            }
            Text("Colores rápidos",style=MaterialTheme.typography.labelLarge)
            listOf("Rojo" to "#FF0000","Verde" to "#00FF00","Azul" to "#0080FF","Amarillo" to "#FFFF00").forEach { (label,sample) ->
                OutlinedButton(onClick={colorHex=sample},modifier=Modifier.fillMaxWidth()) { Box(Modifier.size(20.dp).background(Color(parseRgbHex(sample)!!))); Spacer(Modifier.width(8.dp)); Text("$label · $sample") }
            }
            OutlinedTextField(value=colorTolerance,onValueChange={colorTolerance=it.filter(Char::isDigit).take(3)},label={Text("Tolerancia RGB (0–255)")},singleLine=true)
            Row { FilterChip(selected=colorMode==SearchMode.ANYWHERE,onClick={colorMode=SearchMode.ANYWHERE},label={Text("Cualquier punto")}); Spacer(Modifier.width(8.dp)); FilterChip(selected=colorMode==SearchMode.EXACT,onClick={colorMode=SearchMode.EXACT},label={Text("Punto exacto")}) }
            if(colorMode==SearchMode.EXACT) {
                OutlinedTextField(value=colorX,onValueChange={colorX=it.take(8)},label={Text("X del punto (%)")},singleLine=true)
                OutlinedTextField(value=colorY,onValueChange={colorY=it.take(8)},label={Text("Y del punto (%)")},singleLine=true)
            }
            Text("Para evitar ruido, PrisonMacro exige varios píxeles vecinos de color parecido.",style=MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(value=name,onValueChange={name=it.take(100)},label={Text("Nombre")})
        OutlinedTextField(value=description,onValueChange={description=it.take(500)},label={Text("Descripción (opcional)" )},minLines=2)
        if(a.kind==Kind.OPEN_APP) {
            Text("Aplicación que debe abrir",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(value=packageName,onValueChange={packageName=it.take(255)},label={Text("Paquete Android")},placeholder={Text("com.ejemplo.aplicacion")},singleLine=true,modifier=Modifier.fillMaxWidth(),supportingText={Text("Si no aparece en la lista, escríbelo aquí. Para Zooba: com.wildlife.games.battle.royale.free.zooba")})
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={chooseApp=true}) {Text("Elegir app instalada")}
                OutlinedButton(onClick={packageName="com.wildlife.games.battle.royale.free.zooba"}) {Text("Usar Zooba")}
            }
            Text("Abre la app; no espera a que termine de cargar. Añade «Esperar hasta ver una imagen» y elige la captura de la pantalla que debe aparecer.")
        }
        if(a.kind==Kind.HOME) Text("Sale al inicio. No fuerza la detención del juego ni borra su estado.")
        if(a.kind==Kind.RECENTS) Text("Abre aplicaciones recientes. No cierra ninguna automáticamente.")
        if(a.kind==Kind.CLOSE_ACTIVE) {
            Text("Abre Recientes, espera a que aparezca la tarjeta de la app que estaba activa, la desliza hacia arriba y vuelve a Inicio. No equivale a Forzar detención: Android puede conservar parte del proceso en memoria.")
            OutlinedTextField(value=duration,onValueChange={duration=it.filter(Char::isDigit).take(4)},label={Text("Espera para mostrar Recientes (500–3000 ms)")},singleLine=true)
        }
        if(a.kind==Kind.GESTURE || a.kind==Kind.WAIT || a.kind==Kind.WAIT_IMAGE) OutlinedTextField(value=duration,onValueChange={duration=it.take(8)},label={Text(if(a.kind==Kind.WAIT_IMAGE) "Tiempo máximo esperando la imagen (ms)" else "Duración (ms)")})
        if(a.kind==Kind.REPEAT) OutlinedTextField(value=count,onValueChange={count=it.take(3)},label={Text("Repeticiones (1–100)")})
        if(a.kind==Kind.GESTURE) {
            Text("Coordenadas relativas: 0–100 %. En trayectorias grabadas se conservan todos los puntos.")
            if(a.points.size<=1) {
                OutlinedTextField(value=x,onValueChange={x=it.take(8)},label={Text("X (%)")})
                OutlinedTextField(value=y,onValueChange={y=it.take(8)},label={Text("Y (%)")})
                OutlinedButton(onClick={onPickPoint(a.id)},enabled=Studio.accessibilityReady,modifier=Modifier.fillMaxWidth()) { Text("Elegir X/Y tocando la pantalla") }
            }
        }
        if(a.kind==Kind.IF_IMAGE || a.kind==Kind.IF_COLOR) Text("Si la encuentra, ejecuta ENTONCES; si no, ejecuta SI NO. Si SI NO está vacío, continúa con el siguiente paso.")
        if(a.kind==Kind.WAIT_IMAGE) Text("Comprueba esta captura cada 700 ms. Cuando aparezca, continúa con el siguiente paso; si se agota el tiempo, la macro se detiene.",style=MaterialTheme.typography.bodySmall)
        if(a.kind==Kind.CLICK_IMAGE) Text("Busca la captura elegida y ejecuta el clic configurado. Si no aparece antes del tiempo máximo, la macro se detiene.",style=MaterialTheme.typography.bodySmall)
        if(!valid) Text("Revisa los números y el nombre",color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={onSave(a.copy(name=name,description=description,packageName=packageName,templateId=selectedTemplateId,durationMs=d!!,count=c!!,points=if(a.points.size<=1) listOf(Point(px!!/100,py!!/100)) else a.points,clickTarget=clickTarget,clickStyle=clickStyle,clickOffsetX=clickX ?: 0f,clickOffsetY=clickY ?: 0f,targetColor=parsedColor ?: a.targetColor,colorTolerance=tolerance ?: a.colorTolerance,colorMode=colorMode,colorX=(cpx ?: 50f)/100,colorY=(cpy ?: 50f)/100))},enabled=valid && (a.kind!=Kind.IF_IMAGE && a.kind!=Kind.CLICK_IMAGE && a.kind!=Kind.WAIT_IMAGE || selectedTemplateId.isNotBlank())) {Text("Guardar")}},dismissButton={TextButton(onClick=onDismiss) {Text("Cancelar")}})
}

@Composable private fun PrisonBrandMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(0xFF071018))
            .border(1.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
            .padding(3.dp),
        contentAlignment=androidx.compose.ui.Alignment.Center
    ) {
        Image(
            painter=painterResource(R.drawable.prison_logo),
            contentDescription="Logo de PrisonMacro",
            modifier=Modifier.fillMaxSize(),
            contentScale=androidx.compose.ui.layout.ContentScale.Crop
        )
    }
}

@Composable private fun SavedTemplatePreview(saved: SavedTemplate?, modifier: Modifier=Modifier) {
    val bitmap=remember(saved?.template?.file) { saved?.let { BitmapFactory.decodeFile(File(Studio.context.filesDir,it.template.file).path) } }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    if(bitmap!=null) Image(bitmap.asImageBitmap(),saved?.name ?: "Captura",modifier.background(Color(0xFF0E1320)),contentScale=androidx.compose.ui.layout.ContentScale.Fit)
    else Box(modifier.background(Color(0xFF0E1320)),contentAlignment=androidx.compose.ui.Alignment.Center) { Text("Sin imagen",style=MaterialTheme.typography.bodySmall) }
}

private fun actionUsesTemplate(actions: List<Action>, id: String): Boolean = actions.any { it.templateId==id || actionUsesTemplate(it.children,id) || actionUsesTemplate(it.otherwise,id) }

private fun hasEnabledAction(actions: List<Action>): Boolean = actions.any { it.enabled || hasEnabledAction(it.children) || hasEnabledAction(it.otherwise) }

private fun findAction(actions: List<Action>, id: String): Action? {
    actions.forEach { action ->
        if(action.id==id) return action
        findAction(action.children,id)?.let { return it }
        findAction(action.otherwise,id)?.let { return it }
    }
    return null
}

private fun kindLabel(kind: Kind): String = when(kind) {
    Kind.GESTURE -> "Pulsación / trayectoria"
    Kind.WAIT -> "Esperar"
    Kind.WAIT_IMAGE -> "Esperar hasta ver una imagen"
    Kind.REPEAT -> "Repetir grupo"
    Kind.CLICK_IMAGE -> "Clic en imagen detectada"
    Kind.IF_IMAGE -> "SI SE VE UNA IMAGEN"
    Kind.IF_COLOR -> "SI SE VE UN COLOR"
    Kind.HOME -> "Salir al inicio (no forzar cierre)"
    Kind.BACK -> "Volver atrás"
    Kind.RECENTS -> "Abrir aplicaciones recientes"
    Kind.CLOSE_ACTIVE -> "Cerrar app activa desde Recientes"
    Kind.NOTIFICATIONS -> "Desplegar notificaciones"
    Kind.QUICK_SETTINGS -> "Desplegar ajustes rápidos"
    Kind.OPEN_APP -> "Abrir aplicación / juego"
}

private fun clickTargetLabel(value: ClickTarget): String = when(value) {
    ClickTarget.CENTER -> "Centro de la coincidencia"
    ClickTarget.OFFSET -> "Desplazamiento relativo"
    ClickTarget.FIXED -> "Punto fijo de pantalla"
}

private fun clickStyleLabel(value: ClickStyle): String = when(value) {
    ClickStyle.SINGLE -> "Un clic"
    ClickStyle.DOUBLE -> "Doble clic"
    ClickStyle.LONG -> "Pulsación larga"
}
