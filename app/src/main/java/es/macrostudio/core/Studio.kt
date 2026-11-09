package es.macrostudio.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import es.macrostudio.projects.ProjectStore
import es.macrostudio.sync.SyncStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Studio {
    lateinit var context: Context; private set
    private lateinit var store: ProjectStore
    lateinit var sync: SyncStore; private set
    private val main=Handler(Looper.getMainLooper())
    var project by mutableStateOf(Project()); private set
    var projects by mutableStateOf(emptyList<Project>()); private set
    var logs by mutableStateOf(emptyList<String>()); private set
    var status by mutableStateOf("Preparado")
    var captureReady by mutableStateOf(false)
    var accessibilityReady by mutableStateOf(false)
    var running by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var recording by mutableStateOf(false)
    var detecting by mutableStateOf(false)
    var currentAction by mutableStateOf("")
    var lastMatch by mutableStateOf("Sin pruebas todavía")
    var screenshotVersion by mutableIntStateOf(0)
    /** Condition that requested the next screen capture. Kept only in memory so a
     * cancelled workflow cannot silently alter a project after an app restart. */
    var pendingTemplateActionId by mutableStateOf<String?>(null)
    /** Paso cuyo editor debe volver a abrirse al regresar a la app (tras
     * capturar, recortar o elegir un punto en pantalla). */
    var reopenActionId by mutableStateOf<String?>(null)
    /** Identificador especial: la captura será la imagen de inicio de la macro. */
    const val START_IMAGE_TARGET = "__inicio__"
    /** Prefijo de destino: la captura sirve para elegir el color de este paso. */
    const val COLOR_PREFIX = "color:"
    /** Paso al que hay que volver tras capturar para [target]. */
    fun stepOfTarget(target: String?): String? = stepOfCaptureTarget(target)
    /** Pantalla de la app que debe mostrarse al volver ("editor", "images"…). */
    var requestedScreen by mutableStateOf<String?>(null)
    /** El panel flotante necesita el permiso de captura: "capture" (📷) o "play" (▶).
     * La app lo pide y vuelve sola a la otra app con el panel. */
    var panelNeedsCapture by mutableStateOf<String?>(null)
    private val undo=ArrayDeque<Project>(); private val redo=ArrayDeque<Project>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    fun init(c: Context) {
        if (::context.isInitialized) return
        context=c.applicationContext; store=ProjectStore(context); sync=SyncStore(context); projects=store.list()
        val active=context.getSharedPreferences("studio",0).getString("active",null)
        project=projects.find { it.id==active } ?: projects.firstOrNull() ?: Project()
        logs=runCatching { File(context.filesDir,"history.log").readLines().takeLast(200) }.getOrDefault(emptyList())
    }
    fun update(p: Project, history: Boolean=true): Boolean {
        if (p==project) return true
        val error=p.validate(); if(error!=null) { fail(error); return false }
        try { store.save(p) } catch(e: Exception) { fail("No se pudo guardar: ${e.message}"); return false }
        if(history) { undo.addLast(project); if(undo.size>30) undo.removeFirst(); redo.clear() }
        project=p; projects=store.list(); context.getSharedPreferences("studio",0).edit().putString("active",p.id).apply()
        canUndo=undo.isNotEmpty(); canRedo=redo.isNotEmpty()
        return true
    }
    fun switch(p: Project) { if(update(p,false)) { undo.clear(); redo.clear(); canUndo=false; canRedo=false } }
    fun undo() { if(undo.isNotEmpty()) { val old=project; if(update(undo.last(),false)) { undo.removeLast(); redo.addLast(old) }; canUndo=undo.isNotEmpty(); canRedo=redo.isNotEmpty() } }
    fun redo() { if(redo.isNotEmpty()) { val old=project; if(update(redo.last(),false)) { redo.removeLast(); undo.addLast(old) }; canUndo=undo.isNotEmpty(); canRedo=redo.isNotEmpty() } }
    fun create(name: String): Project {
        val p=Project(name=name.trim().take(100).ifBlank { "Macro ${projects.size+1}" })
        switch(p); return p
    }
    /** Borra una macro guardada. Si era la activa, pasa a otra. */
    fun delete(id: String) {
        runCatching { store.delete(id) }.onFailure { fail("No se pudo borrar: ${it.message}"); return }
        projects=store.list()
        if(project.id==id) {
            undo.clear(); redo.clear(); canUndo=false; canRedo=false
            project=projects.firstOrNull() ?: Project()
            context.getSharedPreferences("studio",0).edit().putString("active",project.id).apply()
        }
        es.macrostudio.projects.Backup.deleteOrphanImages(context,projects)
        log("Macro borrada")
    }
    /** Guarda macros importadas o restauradas. Si una ya existe con el mismo id, se reemplaza. */
    fun saveAll(items: List<Project>) {
        items.forEach { p -> p.validate()?.let { error("«${p.name}»: $it") } }
        items.forEach { store.save(it) }
        projects=store.list()
        projects.find { it.id==project.id }?.let { project=it }
        es.macrostudio.projects.Backup.deleteOrphanImages(context,projects)
    }
    fun log(message: String) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { log(message) }; return }
        val line=SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(Date())+"  "+message
        logs=(logs+line).takeLast(200)
        if(::context.isInitialized) runCatching { File(context.filesDir,"history.log").writeText(logs.joinToString("\n")) }
    }
    /** Error visible: se guarda en el registro y se muestra un aviso, también encima del juego. */
    fun fail(message: String) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { fail(message) }; return }
        status=message; log(message)
        if(::context.isInitialized) android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_LONG).show()
    }
}
