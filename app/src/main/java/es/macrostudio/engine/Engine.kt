package es.macrostudio.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import es.macrostudio.capture.CaptureService
import es.macrostudio.core.*
import es.macrostudio.gestures.MacroAccessibilityService
import es.macrostudio.vision.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object Engine {
    private val main=Handler(Looper.getMainLooper()); private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var generation=0
    private var resume: (() -> Unit)?=null
    private var steps=0; private var started=0L
    private var pendingDetection: Runnable?=null
    fun scheduleDetection(delayMs: Long=2000) {
        if(Studio.running || Studio.recording || Studio.detecting) return
        pendingDetection?.let { main.removeCallbacks(it) }
        val snapshot=Studio.project
        val token=generation
        val task=Runnable {
            pendingDetection=null
            if(token==generation && Studio.project==snapshot && !Studio.running && !Studio.recording && !Studio.detecting) detect(snapshot)
        }
        pendingDetection=task
        main.postDelayed(task,delayMs.coerceIn(0,60_000))
    }
    fun stop(reason: String="Detenido") {
        generation++; resume=null; pendingDetection=null; main.removeCallbacksAndMessages(null)
        if(Studio.running) Studio.log(reason)
        Studio.running=false; Studio.paused=false; Studio.currentAction=""; Studio.status=reason
        MacroAccessibilityService.instance?.refreshBar()
    }
    fun pause() {
        if(!Studio.running) return
        Studio.paused=!Studio.paused; Studio.status=if(Studio.paused) "Pausado (el gesto actual termina)" else "En ejecución"
        if(!Studio.paused) { val next=resume; resume=null; next?.invoke() }
        MacroAccessibilityService.instance?.refreshBar()
    }
    private fun later(token: Int, delay: Long=0, action: ()->Unit) {
        main.postDelayed({ if(token==generation && Studio.running) {
            if(SystemClock.elapsedRealtime()-started > 30*60*1000) stop("Límite de seguridad: 30 minutos")
            else if(Studio.paused) resume={ later(token,0,action) } else action()
        } },delay)
    }
    fun start(triggered: Boolean=true, simulation: Boolean=false, only: List<Action>?=null) {
        if(Studio.detecting) { Studio.fail("Espera a que termine la detección en curso"); return }
        if(Studio.recording) { Studio.fail("Termina la grabación antes de ejecutar"); return }
        val p=Studio.project
        p.validate()?.let { Studio.fail(it); return }
        fun missingConditionImage(items: List<Action>): Boolean=items.filter { it.enabled }.any { action ->
            (action.kind in setOf(Kind.IF_IMAGE,Kind.CLICK_IMAGE,Kind.WAIT_IMAGE) && p.templates.none { it.id==action.templateId }) ||
                missingConditionImage(action.children) || missingConditionImage(action.otherwise)
        }
        val actions=only ?: p.actions
        if(missingConditionImage(actions)) { Studio.fail("Elige una imagen guardada en cada condición o espera por imagen"); return }
        if(!simulation && MacroAccessibilityService.instance==null) { Studio.fail("Activa la accesibilidad de PrisonMacro"); return }
        if(triggered && (p.template==null || !Studio.captureReady)) { Studio.fail("Elige una imagen de inicio rápido y autoriza la captura"); return }
        if(actions.none { it.enabled }) { Studio.fail("Añade al menos una acción activa"); return }
        stop(); val token=generation; steps=0; started=SystemClock.elapsedRealtime(); Studio.running=true; Studio.status="Preparando ejecución"
        MacroAccessibilityService.instance?.showBar(); Studio.log(if(simulation) "Simulación iniciada: no se envían gestos" else "Ejecución iniciada")
        fun cycle(number: Int) {
            val deadline=SystemClock.elapsedRealtime()+p.timeoutMs
            fun execute() { later(token,p.delayMs) { sequence(actions,token,p,simulation) {
                Studio.log("Secuencia $number finalizada")
                if(triggered && p.intervalMs>0 && number<p.maxRuns) later(token,p.intervalMs) { cycle(number+1) } else stop("Completado: $number ejecución(es)")
            } } }
            fun poll() {
                detect(p) { found -> later(token) {
                    if(found) execute() else if(SystemClock.elapsedRealtime()>=deadline) stop("Tiempo agotado: ${Studio.lastMatch}") else later(token,700) { poll() }
                } }
            }
            if(triggered) poll() else execute()
        }
        later(token,1500) { cycle(1) }
    }
    private fun sequence(actions: List<Action>, token: Int, p: Project, simulation: Boolean, done: ()->Unit) {
        fun next(index: Int) { later(token) {
            if(index>=actions.size) { done(); return@later }
            val a=actions[index]; if(!a.enabled) { next(index+1); return@later }
            if(++steps>10_000) { stop("Límite de seguridad: 10.000 instrucciones"); return@later }
            Studio.currentAction=a.id; Studio.status=a.name; Studio.log("Paso $steps · ${a.name}")
            val finish={ next(index+1) }
            when(a.kind) {
                Kind.WAIT -> later(token,a.durationMs,finish)
                Kind.WAIT_IMAGE -> if(simulation) later(token,300,finish) else waitForImage(a,token,p,finish)
                Kind.GESTURE -> if(simulation) later(token,a.durationMs,finish) else {
                    val service=MacroAccessibilityService.instance
                    if(service==null) stop("Accesibilidad desconectada") else service.perform(a) { success ->
                        later(token) { if(success) finish() else stop("Android canceló o rechazó el gesto") }
                    }
                }
                Kind.REPEAT -> { fun repeat(i: Int) { if(i>=a.count) finish() else sequence(a.children,token,p,simulation) { repeat(i+1) } }; repeat(0) }
                Kind.CLICK_IMAGE -> if(simulation) later(token,a.durationMs,finish) else clickImage(a,token,p,finish)
                Kind.IF_IMAGE -> {
                    // Una condición siempre usa su captura elegida. No debe heredar en
                    // silencio la imagen principal del proyecto.
                    val selected=p.templates.firstOrNull { it.id==a.templateId }?.template
                    detect(p,selected) { found -> later(token) { sequence(if(found) a.children else a.otherwise,token,p,simulation,finish) } }
                }
                Kind.IF_COLOR -> detectColor(a) { found -> later(token) { sequence(if(found) a.children else a.otherwise,token,p,simulation,finish) } }
                Kind.CLOSE_ACTIVE -> if(simulation) later(token,a.durationMs+700,finish) else {
                    val service=MacroAccessibilityService.instance
                    if(service==null) stop("Accesibilidad desconectada") else service.closeActiveFromRecents(a.durationMs) { success ->
                        later(token) { if(success) finish() else stop("Android no pudo quitar la aplicación de Recientes") }
                    }
                }
                else -> {
                    if(simulation) later(token,300,finish)
                    else {
                        val service=MacroAccessibilityService.instance
                        val success=service?.performSystemAction(a) ?: false
                        if(success) later(token,500,finish) else stop("Android no pudo ejecutar: ${a.name}")
                    }
                }
            }
        } }; next(0)
    }
    fun detect(p: Project=Studio.project, done: (Boolean)->Unit={}) = detect(p,p.template,done)
    private fun detect(p: Project, selected: Template?, done: (Boolean)->Unit) = detectMatch(p,selected) { done(it!=null) }
    private fun detectMatch(p: Project, selected: Template?, done: (Match?)->Unit) {
        if(Studio.detecting) { if(Studio.running) stop("Ya hay una detección en curso"); done(null); return }
        val t=selected
        if(t==null) { Studio.lastMatch="No hay plantilla"; if(Studio.running) stop(Studio.lastMatch); done(null); return }
        val frame=CaptureService.instance?.frame()
        if(frame==null) { Studio.lastMatch="No hay captura disponible"; if(Studio.running) stop(Studio.lastMatch); done(null); return }
        val token=generation
        Studio.detecting=true
        worker.execute {
            val before=SystemClock.elapsedRealtime()
            var message=""; var match: Match?=null; var failed=false
            try {
                require(frame.width==t.screenWidth && frame.height==t.screenHeight) { "Cambió la resolución: vuelve a capturar y grabar" }
                require(t.file.matches(Regex("[a-zA-Z0-9-]+\\.png"))) { "Ruta de plantilla inválida" }
                val template=BitmapFactory.decodeFile(File(Studio.context.filesDir,t.file).path) ?: error("No se puede leer la plantilla")
                try {
                    val result=BitmapDetector.find(frame,template,t,p.mode) { token!=generation }
                    val found=result!=null && result.confidence>=p.threshold
                    match=if(found) result else null
                    message=if(result==null) "Sin coincidencia" else "${if(found) "Encontrada" else "No encontrada"}: ${(result.confidence*100).roundToInt()}% / umbral ${(p.threshold*100).roundToInt()}% · (${result.x}, ${result.y}) · ${SystemClock.elapsedRealtime()-before} ms"
                } finally { template.recycle() }
            } catch(e: Exception) { failed=true; message=e.message ?: "Error de detección" } finally { frame.recycle() }
            main.post { Studio.detecting=false; if(token==generation) { Studio.lastMatch=message; Studio.log(message); if(failed && Studio.running) stop("Detección detenida: $message"); done(match) } }
        }
    }
    private fun clickImage(action: Action, token: Int, p: Project, done: ()->Unit, waitedMs: Long=0) {
        val selected=p.templates.firstOrNull { it.id==action.templateId }?.template
        if(selected==null) { stop("Elige una imagen guardada para ${action.name}"); return }
        detectMatch(p,selected) { match ->
            if(match!=null) {
                val service=MacroAccessibilityService.instance
                if(service==null) { stop("Accesibilidad desconectada"); return@detectMatch }
                val (screenWidth,screenHeight)=service.screenSize()
                val centerX=match.x+selected.width/2f
                val centerY=match.y+selected.height/2f
                val targetX=when(action.clickTarget) {
                    ClickTarget.CENTER -> centerX
                    ClickTarget.OFFSET -> centerX+selected.width*(action.clickOffsetX/100f)
                    ClickTarget.FIXED -> (screenWidth-1)*action.clickOffsetX/100f
                }.coerceIn(0f,(screenWidth-1).toFloat())
                val targetY=when(action.clickTarget) {
                    ClickTarget.CENTER -> centerY
                    ClickTarget.OFFSET -> centerY+selected.height*(action.clickOffsetY/100f)
                    ClickTarget.FIXED -> (screenHeight-1)*action.clickOffsetY/100f
                }.coerceIn(0f,(screenHeight-1).toFloat())
                val gesture=Action(kind=Kind.GESTURE,name=action.name,durationMs=if(action.clickStyle==ClickStyle.LONG) maxOf(500,action.durationMs) else action.durationMs,points=listOf(Point(targetX/(screenWidth-1),targetY/(screenHeight-1))))
                fun send(remaining: Int) { service.perform(gesture) { success ->
                    later(token) {
                        if(!success) stop("Android no pudo hacer clic en la coincidencia")
                        else if(remaining>0) later(token,120) { send(remaining-1) }
                        else done()
                    }
                } }
                send(if(action.clickStyle==ClickStyle.DOUBLE) 1 else 0)
            } else if(waitedMs>=p.timeoutMs) stop("Tiempo agotado: ${Studio.lastMatch}")
            else later(token,700) { clickImage(action,token,p,done,waitedMs+700) }
        }
    }
    /** Espera a que aparezca la captura elegida para este paso. A diferencia de
     * la imagen de inicio rápido, esta captura pertenece solo a la acción y
     * permite continuar con el siguiente paso cuando aparece, aunque cambie
     * de posición si el proyecto usa "cualquier punto". */
    private fun waitForImage(action: Action, token: Int, p: Project, done: ()->Unit, deadline: Long=SystemClock.elapsedRealtime()+action.durationMs) {
        val selected=p.templates.firstOrNull { it.id==action.templateId }?.template
        if(selected==null) { stop("Elige una imagen guardada para ${action.name}"); return }
        detectMatch(p,selected) { match ->
            if(match!=null) later(token) { done() }
            else if(SystemClock.elapsedRealtime()>=deadline) stop("Tiempo agotado esperando «${p.templates.firstOrNull { it.id==action.templateId }?.name ?: action.name}»: ${Studio.lastMatch}")
            else later(token,700) { waitForImage(action,token,p,done,deadline) }
        }
    }
    private fun detectColor(action: Action, done: (Boolean)->Unit) {
        if(Studio.detecting) { if(Studio.running) stop("Ya hay una detección en curso"); done(false); return }
        val frame=CaptureService.instance?.frame()
        if(frame==null) { Studio.lastMatch="No hay captura disponible"; if(Studio.running) stop(Studio.lastMatch); done(false); return }
        val token=generation
        Studio.detecting=true
        worker.execute {
            val before=SystemClock.elapsedRealtime(); var message=""; var found=false; var failed=false
            try {
                val colors=IntArray(frame.width*frame.height); frame.getPixels(colors,0,frame.width,0,0,frame.width,frame.height)
                val exact=if(action.colorMode==SearchMode.EXACT) Pair((action.colorX*(frame.width-1)).roundToInt(),(action.colorY*(frame.height-1)).roundToInt()) else null
                val result=ColorMatcher.find(Pixels(frame.width,frame.height,colors),action.targetColor,action.colorTolerance,exact) { token!=generation }
                found=result!=null
                message=if(result==null) "Color ${rgbHex(action.targetColor)} no encontrado" else "Color ${rgbHex(action.targetColor)} encontrado · (${result.x}, ${result.y}) · apoyo ${(result.support*100).roundToInt()}% · ${SystemClock.elapsedRealtime()-before} ms"
            } catch(e: Exception) { failed=true; message=e.message ?: "Error de detección de color" } finally { frame.recycle() }
            main.post { Studio.detecting=false; if(token==generation) { Studio.lastMatch=message; Studio.log(message); if(failed && Studio.running) stop("Detección detenida: $message"); done(found) } }
        }
    }
}
