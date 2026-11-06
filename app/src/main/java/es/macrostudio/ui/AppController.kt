package es.macrostudio.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import es.macrostudio.capture.CaptureService
import es.macrostudio.capture.ScreenCaptureRequest
import es.macrostudio.core.*
import es.macrostudio.editor.CropActivity
import es.macrostudio.engine.Engine
import es.macrostudio.gestures.MacroAccessibilityService
import es.macrostudio.projects.Backup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, EDITOR, IMAGES, SETTINGS, CLOUD, LOG }

sealed interface AppDialog {
    data object NeedAccessibility : AppDialog
    data class Issues(val project: Project, val issues: List<String>) : AppDialog
    data class ConfirmRestore(val bytes: ByteArray, val count: Int) : AppDialog
    data class Message(val title: String, val text: String) : AppDialog
}

/**
 * Todas las acciones de la app que salen de la pantalla (permisos, panel
 * flotante, captura, archivos) pasan por aquí, para que cualquier botón haga
 * exactamente lo mismo.
 */
class AppController(val activity: ComponentActivity, private val scope: CoroutineScope) {
    var screen by mutableStateOf(Screen.HOME)
    /** Paso abierto en el editor de pasos. */
    var editingId by mutableStateOf<String?>(null)
    /** Paso recién creado: si se cancela su edición, se elimina. */
    var draftId by mutableStateOf<String?>(null)
    var dialog by mutableStateOf<AppDialog?>(null)

    internal var afterCapturePermission: (() -> Unit)? = null
    internal lateinit var capturePermission: ActivityResultLauncher<Intent>
    internal lateinit var pickImageLauncher: ActivityResultLauncher<Array<String>>
    internal var pickImageTarget: String? = null
    internal lateinit var exportOneLauncher: ActivityResultLauncher<String>
    internal var exportProjectId: String? = null
    internal lateinit var exportAllLauncher: ActivityResultLauncher<String>
    internal lateinit var openFileLauncher: ActivityResultLauncher<Array<String>>

    fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    var previous: Screen = Screen.HOME; private set
    fun open(screen: Screen) { if (screen != this.screen) previous = this.screen; this.screen = screen }

    fun openEditor(p: Project) { if (p.id != Studio.project.id) Studio.switch(p); open(Screen.EDITOR) }

    fun editAction(id: String, isDraft: Boolean = false) { draftId = if (isDraft) id else null; editingId = id }

    fun closeActionEditor(discardDraft: Boolean) {
        val id = editingId
        if (discardDraft && id != null && id == draftId) Studio.update(Studio.project.copy(actions = updateAction(Studio.project.actions, id) { null }))
        draftId = null; editingId = null
    }

    private fun accessibility(): MacroAccessibilityService? {
        val s = MacroAccessibilityService.instance
        if (s == null) dialog = AppDialog.NeedAccessibility
        return s
    }

    fun openAccessibilitySettings() { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    /** Ejecuta [then] cuando la captura de pantalla está autorizada (la pide si hace falta). */
    fun withCapture(then: () -> Unit) {
        if (Studio.captureReady) then()
        else { afterCapturePermission = then; capturePermission.launch(ScreenCaptureRequest.createIntent(activity)) }
    }

    fun requestCaptureOnly() = withCapture { toast("Captura de pantalla autorizada") }

    fun stopCapture() { CaptureService.instance?.stopSelf() }

    /** Va a otra app con el panel en modo captura. La imagen se asigna a [target]. */
    fun captureFor(target: String?) {
        val s = accessibility() ?: return
        withCapture { s.startCaptureMode(target); activity.moveTaskToBack(true) }
    }

    fun importImageFor(target: String?) {
        pickImageTarget = target
        pickImageLauncher.launch(arrayOf("image/*"))
    }

    fun recropImage(templateId: String, returnTo: String?) {
        activity.startActivity(Intent(activity, CropActivity::class.java).putExtra("templateId", templateId).putExtra("actionId", returnTo))
    }

    /** Muestra el panel flotante para la macro y deja al usuario en su app. */
    fun play(p: Project) {
        if (p.id != Studio.project.id) Studio.switch(p)
        val issues = Studio.project.executionIssues()
        if (issues.isNotEmpty()) { dialog = AppDialog.Issues(Studio.project, issues); return }
        val s = accessibility() ?: return
        val go = {
            s.showBar(); activity.moveTaskToBack(true)
            toast("Abre tu app o juego y pulsa ▶ en el panel flotante")
        }
        if (Studio.project.needsScreenCapture()) withCapture(go) else go()
    }

    fun record() {
        val s = accessibility() ?: return
        s.showBar(); activity.moveTaskToBack(true)
        toast("Abre tu app y pulsa ● en el panel para grabar. Pulsa ■ para terminar.")
    }

    /** Ejecuta solo este paso sobre la app que haya debajo. */
    fun testStep(a: Action) {
        accessibility() ?: return
        val go = {
            Engine.start(false, false, listOf(a))
            if (Studio.running) { activity.moveTaskToBack(true); toast("Probando «${a.name}» en 1,5 s…") }
        }
        if (Studio.project.copy(actions = listOf(a)).needsScreenCapture()) withCapture(go) else go()
    }

    fun testImage(templateId: String) = withCapture {
        Engine.testImage(templateId); activity.moveTaskToBack(true)
        toast("Ve a la pantalla: se comprobará en 2,5 s")
    }

    fun pickPoint(actionId: String, swipe: Boolean = false) {
        val s = accessibility() ?: return
        s.startPointPicker(actionId, swipe); activity.moveTaskToBack(true)
    }

    /** Captura la pantalla del juego y deja tocar el color que hay que buscar. */
    fun pickColor(actionId: String) = captureFor(Studio.COLOR_PREFIX + actionId)

    // ---------- Archivos y copias de seguridad ----------

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9áéíóúñÁÉÍÓÚÑ _-]"), "").trim().ifBlank { "macro" }.take(40)

    fun exportProject(p: Project) { exportProjectId = p.id; exportOneLauncher.launch("${safeName(p.name)}.prisonmacro.json") }

    fun backupAll() {
        if (Studio.projects.isEmpty()) { toast("Todavía no hay macros guardadas"); return }
        exportAllLauncher.launch("PrisonMacro-copia-${SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date())}.json")
    }

    /** Abre un archivo: una macro exportada o una copia de seguridad completa. */
    fun openFile() = openFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))

    internal fun writeTo(uri: android.net.Uri, produce: () -> String, done: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = produce()
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("No se pudo escribir el archivo")
                }
            }.onSuccess { Studio.log(done); toast(done) }.onFailure { Studio.fail("No se pudo guardar: ${it.message}") }
        }
    }

    internal fun readFile(uri: android.net.Uri) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val read = input.read(buffer); if (read < 0) break; require(out.size() + read <= Backup.MAX_BYTES) { "Archivo demasiado grande" }; out.write(buffer, 0, read) }
                        out.toByteArray()
                    } ?: error("No se pudo leer el archivo")
                }
            }.onSuccess { bytes ->
                if (Backup.isFullBackup(bytes)) {
                    val count = runCatching { org.json.JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("projects").length() }.getOrDefault(0)
                    dialog = AppDialog.ConfirmRestore(bytes, count)
                } else importMacro(bytes, "archivo")
            }.onFailure { Studio.fail("No se pudo abrir el archivo: ${it.message}") }
        }
    }

    /** Añade una macro como nueva (nunca reemplaza otra) y la abre. */
    fun importMacro(bytes: ByteArray, source: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Backup.read(activity, bytes, keepIds = false) } }
                .mapCatching { list -> Studio.saveAll(list); list }
                .onSuccess { list ->
                    list.firstOrNull()?.let { Studio.switch(Studio.projects.firstOrNull { p -> p.id == it.id } ?: it); screen = Screen.EDITOR }
                    Studio.log("Macro importada desde $source"); toast("Macro importada: ${list.firstOrNull()?.name}")
                }.onFailure { Studio.fail("Importación rechazada: ${it.message}") }
        }
    }

    fun restore(bytes: ByteArray) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Backup.read(activity, bytes, keepIds = true) } }
                .mapCatching { list -> Studio.saveAll(list); list }
                .onSuccess { list -> Studio.log("Copia restaurada: ${list.size} macro(s)"); toast("Copia restaurada: ${list.size} macro(s)"); screen = Screen.HOME }
                .onFailure { Studio.fail("No se pudo restaurar: ${it.message}") }
        }
    }
}

@Composable fun rememberAppController(activity: ComponentActivity): AppController {
    val scope = rememberCoroutineScope()
    val c = remember { AppController(activity, scope) }
    c.capturePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) activity.startForegroundService(Intent(activity, CaptureService::class.java).putExtra("data", result.data))
        else { c.afterCapturePermission = null; Studio.fail("Captura cancelada: no se ha compartido la pantalla") }
    }
    // Cuando el servicio de captura arranca, continúa lo que se estaba haciendo.
    LaunchedEffect(Studio.captureReady) {
        if (Studio.captureReady) c.afterCapturePermission?.let { c.afterCapturePermission = null; it() }
    }
    // OpenDocument muestra el selector completo de Android (también en BlueStacks).
    c.pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) activity.startActivity(Intent(activity, CropActivity::class.java).setData(uri).putExtra("actionId", c.pickImageTarget).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        c.pickImageTarget = null
    }
    c.exportOneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = Studio.projects.firstOrNull { it.id == c.exportProjectId } ?: Studio.project
        if (uri != null) c.writeTo(uri, { Backup.projectPayload(activity, p).toString(2) }, "Macro «${p.name}» exportada")
    }
    c.exportAllLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val all = Studio.projects
        if (uri != null) c.writeTo(uri, { Backup.fullBackup(activity, all) }, "Copia de seguridad guardada: ${all.size} macro(s)")
    }
    c.openFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) c.readFile(uri) }
    return c
}
