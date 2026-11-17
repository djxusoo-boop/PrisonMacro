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
    /** Último paso que hizo la macro («5 · Tocar BATALLA»). */
    var lastStep by mutableStateOf("")
    /** Si la macro terminó: en qué paso y por qué («■ Se paró en el paso 5 · …»). */
    var stopInfo by mutableStateOf("")
    /** Paso donde se paró la macro por un problema (se marca en rojo en el editor). */
    var stoppedActionId by mutableStateOf<String?>(null)
    var screenshotVersion by mutableIntStateOf(0)
    /** Paso copiado o cortado (se pega como copia nueva). */
    var clipboard by mutableStateOf<Action?>(null)
    var clipboardCut by mutableStateOf(false)
    /** Bloques plegados en el editor (no se guardan en la macro). */
    var collapsed by mutableStateOf(setOf<String>())
    /** Modo ordenar: flechas grandes en cada paso. */
    var sorting by mutableStateOf(false)
    /** Modo seleccionar: casillas en cada paso para borrar varios de golpe. */
    var selecting by mutableStateOf(false)
    var selectedSteps by mutableStateOf(setOf<String>())
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
    /** Marcar en una captura guardada dónde tocar (1 punto) o deslizar (2 puntos). */
    const val POINT_PREFIX = "point:"
    const val SWIPE_PREFIX = "swipe:"
    /** Paso al que hay que volver tras capturar para [target]. */
    fun stepOfTarget(target: String?): String? = stepOfCaptureTarget(target)
    /** Última pantalla abierta en la app. Si Android cierra la ventana mientras se usa el panel
     * flotante, al volver se abre donde estaba (antes volvía siempre a la lista de macros). */
    var lastScreen: String = "HOME"
    /** Grabar dentro de un bloque: id del bloque y si es su parte «✖ Si NO se ve».
     * null = los toques grabados van al final de la macro. */
    var recordInto by mutableStateOf<Pair<String, Boolean>?>(null)
    /** Carpeta abierta en «Imágenes»: las capturas y recortes nuevos se guardan en ella ("" = ninguna). */
    var shotFolder by mutableStateOf("")
    /** Pantalla de la app que debe mostrarse al volver ("editor", "images"…). */
    var requestedScreen by mutableStateOf<String?>(null)
    /** Sube cada vez que se marca dónde está «Cerrar todo» (para que Ajustes lo enseñe al volver). */
    var deviceChanges by mutableIntStateOf(0)
    /** El panel flotante necesita el permiso de captura: "capture" (📷) o "play" (▶).
     * La app lo pide y vuelve sola a la otra app con el panel. */
    var panelNeedsCapture by mutableStateOf<String?>(null)
    private val undo=ArrayDeque<Project>(); private val redo=ArrayDeque<Project>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    fun init(c: Context) {
        if (::context.isInitialized) return
        context=c.applicationContext; store=ProjectStore(context); sync=SyncStore(context); projects=ordered(store.list())
        val active=context.getSharedPreferences("studio",0).getString("active",null)
        project=projects.find { it.id==active } ?: projects.firstOrNull() ?: Project()
        logs=runCatching { File(context.filesDir,"history.log").readLines().takeLast(200) }.getOrDefault(emptyList())
    }
    fun update(p: Project, history: Boolean=true): Boolean {
        if (p==project) return true
        val error=p.validate(); if(error!=null) { fail(error); return false }
        try { store.save(p) } catch(e: Exception) { fail("No se pudo guardar: ${e.message}"); return false }
        if(history) { undo.addLast(project); if(undo.size>30) undo.removeFirst(); redo.clear() }
        project=p; projects=ordered(store.list()); context.getSharedPreferences("studio",0).edit().putString("active",p.id).apply()
        canUndo=undo.isNotEmpty(); canRedo=redo.isNotEmpty()
        return true
    }
    fun switch(p: Project) { if(update(p,false)) { undo.clear(); redo.clear(); canUndo=false; canRedo=false; selecting=false; selectedSteps=emptySet() } }
    fun undo() { if(undo.isNotEmpty()) { val old=project; if(update(undo.last(),false)) { undo.removeLast(); redo.addLast(old) }; canUndo=undo.isNotEmpty(); canRedo=redo.isNotEmpty() } }
    fun redo() { if(redo.isNotEmpty()) { val old=project; if(update(redo.last(),false)) { redo.removeLast(); undo.addLast(old) }; canUndo=undo.isNotEmpty(); canRedo=redo.isNotEmpty() } }
    fun create(name: String): Project {
        val p=Project(name=name.trim().take(100).ifBlank { "Macro ${projects.size+1}" })
        switch(p); return p
    }
    /** Orden de la lista de macros que eligió la persona (subir, bajar, arrastrar). Se guarda en este aparato;
     * las macros que no están en el orden (nuevas o recibidas) van al final, por nombre. */
    private fun ordered(list: List<Project>): List<Project> {
        val order=context.getSharedPreferences("studio",0).getString("macroOrder","").orEmpty().split(',').filter { it.isNotBlank() }
        if(order.isEmpty()) return list
        val pos=order.withIndex().associate { it.value to it.index }
        return list.sortedWith(compareBy { pos[it.id] ?: Int.MAX_VALUE })
    }
    /** Pone la macro [id] en el puesto [to] de la lista (0 = arriba del todo). */
    fun moveProject(id: String, to: Int) {
        val ids=projects.map { it.id }.toMutableList()
        val from=ids.indexOf(id); if(from<0) return
        ids.removeAt(from); ids.add(to.coerceIn(0,ids.size),id)
        context.getSharedPreferences("studio",0).edit().putString("macroOrder",ids.joinToString(",")).apply()
        projects=ordered(projects)
    }
    /** Borra una macro guardada. Si era la activa, pasa a otra. */
    fun delete(id: String) {
        runCatching { store.delete(id) }.onFailure { fail("No se pudo borrar: ${it.message}"); return }
        projects=ordered(store.list())
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
        projects=ordered(store.list())
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
