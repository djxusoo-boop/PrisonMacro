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
    fun log(message: String) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { log(message) }; return }
        val line=SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(Date())+"  "+message
        logs=(logs+line).takeLast(200)
        if(::context.isInitialized) runCatching { File(context.filesDir,"history.log").writeText(logs.joinToString("\n")) }
    }
    fun fail(message: String) { status=message; log(message) }
}
