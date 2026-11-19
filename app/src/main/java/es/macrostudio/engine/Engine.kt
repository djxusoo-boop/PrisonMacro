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
    // Los resultados de las búsquedas llegan por aquí: «stop()» vacía [main] y, si se llevaba
    // también este aviso, la app se quedaba creyendo que seguía buscando («Espera a que termine…»).
    private val results=Handler(Looper.getMainLooper())
    @Volatile private var generation=0
    private var resume: (() -> Unit)?=null
    private var steps=0; private var started=0L; private var limitMs=0L
    private var round=""
    private var lastStepId=""
    private var pendingDetection: Runnable?=null
    // «⚡ Siempre que se vea»: reglas que actúan en cuanto aparece su imagen, sin importar el orden.
    private class LoopFrame(val exit: ()->Unit)
    private var rules: List<Action> = emptyList()
    private val cooldown=HashMap<String,Long>()
    private var inRule=false
    private var lastRuleCheck=0L
    private val loops=ArrayList<LoopFrame>()
    private var roundEnd: (()->Unit)?=null
    // «⏱ Reloj»: horas marcadas. Siguen entre vueltas y aunque una vuelta falle; solo se borran al parar.
    private val clock=ClockMarks()
    private var roundRestart: (()->Unit)?=null
    // «Sin fin, no parar nunca»: si algo falla, se apunta y la vuelta empieza otra vez.
    private var relaunch: ((Int)->Unit)?=null
    private var roundNumber=1
    // «🔁 Si se para sola, vuelve a empezar»: una macro sin fin con «si algo falla, vuelve a empezar» que se
    // para sin que la persona pulse ■ (accesibilidad o captura cortadas, Android cierra la app…) vuelve
    // a darle a ▶ sola. Se apunta en las preferencias para seguir aunque la app se haya cerrado.
    private val resumer=Handler(Looper.getMainLooper())
    /** Mientras se busca «Cerrar todo» antes de empezar (para no hacerlo dos veces). */
    private var findingClearAll=false
    private var resumeTries=0
    private const val RESUME_KEY="autoResume"
    const val RESUME_DELAY_MS=10_000L
    private fun prefs()=Studio.context.getSharedPreferences("studio",0)
    /** La persona ha parado (o ha cambiado de macro): ya no hay que volver a empezar. */
    fun cancelAutoResume() { resumer.removeCallbacksAndMessages(null); resumeTries=0; runCatching { prefs().edit().remove(RESUME_KEY).apply() } }
    fun resumeIsPending(): Boolean=runCatching { prefs().getString(RESUME_KEY,null)==Studio.project.id }.getOrDefault(false)
    /** Si quedó pendiente volver a empezar (también tras cerrarse la app), se intenta dentro de [delayMs]. */
    fun resumePending(delayMs: Long=RESUME_DELAY_MS) {
        val id=runCatching { prefs().getString(RESUME_KEY,null) }.getOrNull() ?: return
        resumer.removeCallbacksAndMessages(null)
        resumer.postDelayed({ tryResume(id) },delayMs)
    }
    private fun tryResume(id: String) {
        if(Studio.running || Studio.recording) { resumeTries=0; return }
        if(runCatching { prefs().getString(RESUME_KEY,null) }.getOrNull()!=id) return
        val p=Studio.project
        if(p.id!=id) { cancelAutoResume(); return } // se abrió otra macro
        val service=MacroAccessibilityService.instance
        val missing=when {
            service==null -> "falta la accesibilidad"
            p.needsScreenCapture() && !Studio.captureReady -> "falta autorizar la captura: pulsa ▶"
            Studio.detecting -> "espera a que termine la búsqueda"
            else -> ""
        }
        if(missing.isNotEmpty()) {
            // Se sigue intentando un rato (hasta 1 h); al volver la accesibilidad, empieza sola.
            resumeTries++
            Studio.status="🔁 Para volver a empezar $missing"
            service?.showBar("🔁 Se paró. Para volver a empezar $missing")
            if(resumeTries<360) resumer.postDelayed({ tryResume(id) },RESUME_DELAY_MS) else cancelAutoResume()
            return
        }
        resumeTries=0
        Studio.log("🔁 La macro se había parado sola: vuelve a empezar")
        start(p.startOnImage)
    }
    private var failures=0
    private var lastFailure=""
    private var failStepId=""
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
        val wasRunning=Studio.running
        generation++; resume=null; pendingDetection=null; main.removeCallbacksAndMessages(null)
        rules=emptyList(); cooldown.clear(); clock.clear(); inRule=false; lastRuleCheck=0L; loops.clear(); roundEnd=null; roundRestart=null; relaunch=null
        if(wasRunning) {
            // Queda dicho en qué paso estaba y por qué terminó: así se sabe qué paso para la macro.
            val where=Studio.lastStep
            val manual=reason=="Detenido" || reason=="Parada manual" || reason=="Captura detenida"
            val done=reason.startsWith("Completado")
            // Errores de la macro (falta una imagen…) o el límite de tiempo: volver a empezar no arreglaría nada.
            val noRetry=manual || done || reason.startsWith("Elige") || reason.startsWith("Parada automática") || reason=="No hay plantilla"
            val again=!noRetry && runCatching { prefs().getString(RESUME_KEY,null) }.getOrNull()==Studio.project.id
            Studio.stopInfo=when {
                where.isBlank() -> (if(done) "✓ " else "■ ")+reason
                done -> "✓ $reason\nÚltimo: $where"
                manual -> "■ Parada a mano en: $where"
                else -> "■ Se paró en: $where\n$reason"
            }+(if(failures>0) "\n⚠ Falló $failures ${if(failures==1) "vez" else "veces"} y volvió a empezar sola. Última: $lastFailure" else "")+
                (if(again) "\n🔁 Vuelve a empezar sola en ${RESUME_DELAY_MS/1000} s" else "")
            Studio.stoppedActionId=if(!manual && !done) lastStepId.takeIf { it.isNotBlank() } else failStepId.takeIf { it.isNotBlank() }
            Studio.stoppedActionId?.let { id ->
                // Despliega los bloques que lo contienen para que se vea en el editor.
                var parent=parentOf(Studio.project.actions,id); val open=HashSet<String>()
                while(parent!=null && open.add(parent.id)) parent=parentOf(Studio.project.actions,parent.id)
                Studio.collapsed=Studio.collapsed-open
            }
            Studio.log(Studio.stopInfo.replace("\n"," · "))
            // Avisa encima del juego de por qué terminó (antes se paraba sin decir nada).
            if(!manual) android.widget.Toast.makeText(Studio.context,Studio.stopInfo,android.widget.Toast.LENGTH_LONG).show()
            if(again) resumePending() else cancelAutoResume()
        } else if(reason=="Detenido" || reason=="Parada manual") cancelAutoResume()
        Studio.running=false; Studio.paused=false; Studio.currentAction=""; Studio.status=if(wasRunning) Studio.stopInfo.replace("\n"," · ") else reason
        runCatching { KeepAlive.macroRunning(Studio.context,false) }
        Watch.clear(); Watch.step=if(wasRunning) Studio.stopInfo else reason
        RunNotice.hide(Studio.context)
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
            if(limitMs>0 && SystemClock.elapsedRealtime()-started > limitMs) stop("Parada automática: ${limitMs/60_000} min")
            else if(Studio.paused) resume={ later(token,0,action) } else action()
        } },delay)
    }
    fun start(triggered: Boolean=true, simulation: Boolean=false, only: List<Action>?=null) {
        if(Studio.detecting) { Studio.fail("Espera a que termine la detección en curso"); return }
        if(Studio.recording) { Studio.fail("Termina la grabación antes de ejecutar"); return }
        val p=Studio.project
        p.validate()?.let { Studio.fail(it); return }
        fun missingConditionImage(items: List<Action>): Boolean=items.filter { it.enabled }.any { action ->
            (action.kind in setOf(Kind.IF_IMAGE,Kind.CLICK_IMAGE,Kind.WAIT_IMAGE,Kind.WHEN_IMAGE) && p.templates.none { it.id==action.templateId }) ||
                missingConditionImage(action.children) || missingConditionImage(action.otherwise)
        }
        val actions=only ?: p.actions
        if(missingConditionImage(actions)) { Studio.fail("Elige una imagen guardada en cada condición o espera por imagen"); return }
        if(!simulation && MacroAccessibilityService.instance==null) { Studio.fail("Activa la accesibilidad de PrisonMacro"); return }
        if(triggered && p.template==null) { Studio.fail("Elige la imagen con la que empieza la macro"); return }
        if(!simulation && (triggered || p.copy(actions=actions).needsScreenCapture()) && !Studio.captureReady) { Studio.fail("Autoriza la captura de pantalla: esta macro busca imágenes o colores"); return }
        if(actions.none { it.enabled }) { Studio.fail("Añade al menos una acción activa"); return }
        // «Cerrar el juego» se hace distinto en el PC y en el MacBook, y eso se guarda en cada BlueStacks:
        // en uno nuevo (o al pasarle la macro) hay que decirlo antes, si no cierra el juego mal.
        fun closes(items: List<Action>): Boolean=items.any { it.enabled && (it.kind==Kind.CLOSE_ACTIVE || closes(it.children) || closes(it.otherwise)) }
        if(!simulation && closes(actions) && !ThisDevice.ready(Studio.context)) {
            // PC Windows sin saber aún dónde está «Cerrar todo»: se busca solo (o se pide un toque) y se sigue.
            val service=MacroAccessibilityService.instance
            if(service==null || findingClearAll) return
            findingClearAll=true
            Studio.status="Buscando «Cerrar todo» en este BlueStacks…"
            Studio.log("🖥 Primera vez en este BlueStacks: busco dónde está «Cerrar todo»")
            service.findClearAllBeforeStart { found ->
                findingClearAll=false
                if(found && ThisDevice.ready(Studio.context)) start(triggered,simulation,only)
                else {
                    Studio.fail("No sé dónde está «Cerrar todo» en este BlueStacks: abre el juego, pulsa ▶ otra vez y tócalo cuando te lo pida")
                    MacroAccessibilityService.instance?.showBar("👉 Abre el juego y pulsa ▶: te pediré que toques «Cerrar todo» una vez")
                }
            }
            return
        }
        stop(); Studio.stopInfo=""; Studio.stoppedActionId=null; Studio.lastStep=""; lastStepId=""; failures=0; lastFailure=""; failStepId=""
        val first=generation; steps=0; started=SystemClock.elapsedRealtime(); limitMs=p.maxMinutes*60_000L; round=""; Studio.running=true; KeepAlive.macroRunning(Studio.context,true); Studio.status="Preparando ejecución"; RunNotice.show(Studio.context,if(only!=null) (only.firstOrNull()?.name ?: "Paso") else p.name)
        MacroAccessibilityService.instance?.showBar(); Studio.log(if(simulation) "Simulación iniciada: no se envían gestos" else "Ejecución iniciada")
        resumer.removeCallbacksAndMessages(null); resumeTries=0
        if(only==null && !simulation && p.repeatCount==0 && p.keepGoing) runCatching { prefs().edit().putString(RESUME_KEY,p.id).apply() }
        else cancelAutoResume()
        rules=ruleActions(actions)
        if(rules.isNotEmpty()) Studio.log("⚡ ${rules.size} regla(s) vigilando la pantalla")
        // Una macro hecha solo de reglas no tiene pasos en orden: vigila la pantalla hasta que la pares.
        val onlyRules=actions.none { it.enabled && it.kind!=Kind.WHEN_IMAGE }
        // Las pruebas de un paso suelto se ejecutan una sola vez; 0 vueltas = sin fin.
        val total=if(only!=null) 1 else p.repeatCount
        fun cycle(token: Int, number: Int) {
            roundNumber=number
            val deadline=SystemClock.elapsedRealtime()+p.timeoutMs
            steps=0
            round=when { total==1 -> ""; total==0 -> "Vuelta $number · "; else -> "Vuelta $number/$total · " }+(if(failures>0) "⚠$failures · " else "")
            roundEnd=null; roundRestart=null; loops.clear()
            fun execute() {
                if(onlyRules) {
                    if(simulation) { stop("Completado: en la simulación las reglas ⚡ no miran la pantalla"); return }
                    setStatus("⚡ Vigilando la pantalla…"); Watch.step="⚡ Vigilando la pantalla (${rules.size} regla(s))"; Watch.next="Lo que aparezca"
                    fun idle() { steps=0; later(token,400) { checkpoint(token,p,false) { idle() } } }
                    idle(); return
                }
                val end={
                    loops.clear(); roundEnd=null; roundRestart=null
                    Studio.log("Secuencia $number finalizada")
                    if(total==0 || number<total) { Watch.step="${round}Vuelta terminada"; Watch.next="Vuelta ${number+1}"; Watch.waiting("Pausa entre vueltas",p.intervalMs); later(token,p.intervalMs) { cycle(token,number+1) } } else stop("Completado: $number ejecución(es)")
                }
                loops.clear(); roundEnd=end; roundRestart={ Studio.log("⚡ Empieza la vuelta otra vez"); steps=0; execute() }
                Watch.waiting("Empieza en",p.delayMs); later(token,p.delayMs) { sequence(actions,token,p,simulation,end) }
            }
            fun poll() {
                detect(p) { found -> later(token) {
                    if(found) execute() else if(SystemClock.elapsedRealtime()>=deadline) fail("Tiempo agotado esperando la imagen de inicio: ${Studio.lastMatch}") else later(token,700) { checkpoint(token,p,simulation) { poll() } }
                } }
            }
            if(triggered) { setStatus("${round}Esperando la imagen de inicio…"); poll() } else execute()
        }
        relaunch=if(total==0 && p.keepGoing && only==null && !simulation) { number -> cycle(generation,number) } else null
        later(first,1500) { cycle(first,1) }
    }
    /** Algo ha fallado. En una macro sin fin con «no parar nunca» se apunta, se espera un poco y la
     * vuelta empieza otra vez; si no, la macro se para como siempre. */
    private fun fail(reason: String) {
        val again=relaunch
        if(again==null || !Studio.running) { stop(reason); return }
        failures++
        lastFailure="${Studio.lastStep.ifBlank { "al empezar" }} · $reason"
        failStepId=lastStepId
        // Corta lo que estaba en marcha (sin parar la macro) y empieza la vuelta otra vez.
        generation++; resume=null; main.removeCallbacksAndMessages(null)
        inRule=false; loops.clear(); roundEnd=null; roundRestart=null
        val token=generation
        Studio.log("⚠ Fallo $failures: $lastFailure → la vuelta empieza otra vez en 5 s")
        setStatus("⚠ $reason · empieza otra vez en 5 s")
        Watch.step="⚠ Falló: ${Studio.lastStep.ifBlank { "al empezar" }}\n$reason"; Watch.next="Vuelta ${roundNumber+1} (desde el principio)"; Watch.waiting("Empieza otra vez en",5000)
        later(token,5000) { again(roundNumber+1) }
    }
    private fun sequence(actions: List<Action>, token: Int, p: Project, simulation: Boolean, done: ()->Unit) {
        var step: (Int, Action)->Unit = { _, _ -> }
        fun next(index: Int) { later(token) {
            if(index>=actions.size) { done(); return@later }
            val a=actions[index]; if(!a.enabled || a.kind==Kind.WHEN_IMAGE) { next(index+1); return@later }
            checkpoint(token,p,simulation) { step(index,a) }
        } }
        step = step@{ index: Int, a: Action ->
            if(++steps>10_000) { fail("Límite de seguridad: 10.000 pasos en una sola vuelta"); return@step }
            // Número como en el editor («5», «3.2»): es lo que se enseña en el panel.
            val label="Paso ${stepNumber(p.actions,a.id) ?: "${index+1}"} «${a.name}»"
            Studio.lastStep=label; lastStepId=a.id
            Studio.currentAction=a.id; setStatus(round+label); Studio.log(label)
            Watch.step=round+label
            Watch.next=actions.drop(index+1).firstOrNull { it.enabled && it.kind!=Kind.WHEN_IMAGE }?.name ?: "(se acaba este bloque)"
            Watch.waitUntil=0
            val finish={
                if(a.delayAfterMs>0) { setStatus("$round$label · espera ${readableMs(a.delayAfterMs)}"); Watch.waiting("Espera después de «${a.name}»",a.delayAfterMs); pauseFor(token,p,simulation,a.delayAfterMs,null) { next(index+1) } }
                else next(index+1)
            }
            if(a.delayBeforeMs>0) { setStatus("$round$label · espera ${readableMs(a.delayBeforeMs)} antes"); Watch.waiting("Espera antes de «${a.name}»",a.delayBeforeMs) }
            pauseFor(token,p,simulation,a.delayBeforeMs,finish) { if(a.delayBeforeMs>0) setStatus(round+label)
            when(a.kind) {
                Kind.WAIT -> { Watch.waiting("Esperando",a.durationMs); pauseFor(token,p,simulation,a.durationMs,finish,finish) }
                Kind.WAIT_IMAGE -> if(simulation) later(token,300,finish) else waitForImage(a,token,p,finish)
                Kind.GESTURE -> if(simulation) later(token,a.durationMs,finish) else {
                    val service=MacroAccessibilityService.instance
                    if(service==null) fail("Accesibilidad desconectada") else performWithRetry(service,a,token) { success ->
                        // Aunque Android lo rechace 3 veces, la macro no se para: sigue con el siguiente paso.
                        if(!success) Studio.log("Android rechazó el toque de «${a.name}» 3 veces; se sigue")
                        finish()
                    }
                }
                Kind.REPEAT -> {
                    // Con imagen elegida: repite hasta que se vea (como máximo [count] veces).
                    // Una regla ⚡ con «Salir del bucle» sale de aquí aunque falten vueltas.
                    val until=p.templates.firstOrNull { it.id==a.templateId }?.template
                    val frame=LoopFrame(finish); loops.add(frame)
                    val end={ loops.remove(frame); finish() }
                    fun repeat(i: Int) {
                        if(i>=a.count) { end(); return }
                        if(until==null || simulation) sequence(a.children,token,p,simulation) { repeat(i+1) }
                        else detect(p,until) { found -> later(token) { if(found) end() else sequence(a.children,token,p,simulation) { repeat(i+1) } } }
                    }
                    repeat(0)
                }
                Kind.STOP -> stop("Completado: parada en «${a.name}»")
                Kind.MARK_TIME -> {
                    val mark=ClockMarks.nameOf(a)
                    clock.mark(mark,SystemClock.elapsedRealtime())
                    Studio.log("⏱ Hora marcada en «$mark»")
                    finish()
                }
                Kind.WAIT_SINCE_MARK -> {
                    val mark=ClockMarks.nameOf(a)
                    val left=clock.leftMs(mark,a.durationMs,SystemClock.elapsedRealtime())
                    when {
                        clock.markedAt(mark)==null -> { Studio.log("⏱ No hay marca «$mark»: no espera"); finish() }
                        left<=0 -> { Studio.log("⏱ Ya pasaron ${durationText(a.durationMs)} desde «$mark»: sigue"); finish() }
                        else -> {
                            // Como «Esperar»: las reglas ⚡ siguen vigilando y el panel enseña la cuenta atrás.
                            Studio.log("⏱ Espera ${durationText(left)} hasta ${durationText(a.durationMs)} desde «$mark»")
                            setStatus("$round$label · faltan ${durationText(left)}")
                            Watch.waiting("Hasta ${durationText(a.durationMs)} desde «$mark»",left)
                            pauseFor(token,p,simulation,left,finish,finish)
                        }
                    }
                }
                Kind.WHEN_IMAGE -> finish()
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
                    if(service==null) fail("Accesibilidad desconectada") else service.closeActiveFromRecents(a.durationMs) { success ->
                        later(token) { if(!success) Studio.log("Android no pudo quitar la app de Recientes; se sigue"); finish() }
                    }
                }
                else -> {
                    if(simulation) later(token,300,finish)
                    else {
                        val service=MacroAccessibilityService.instance
                        if(service==null) { fail("Accesibilidad desconectada"); return@pauseFor }
                        // Si Android no lo hace a la primera, se reintenta una vez y, si no, se sigue (sin parar el bucle).
                        if(service.performSystemAction(a)) later(token,500,finish)
                        else later(token,400) { if(!service.performSystemAction(a)) Studio.log("Android no pudo hacer «${a.name}»; se sigue"); later(token,500,finish) }
                    }
                }
            }
            }
        }; next(0)
    }
    /** Envía el toque y, si Android lo rechaza (pantalla cambiando, otro toque…), lo reintenta hasta 3 veces. */
    private fun performWithRetry(service: MacroAccessibilityService, gesture: Action, token: Int, tries: Int=3, done: (Boolean)->Unit) {
        service.perform(gesture) { ok -> later(token) {
            if(ok || tries<=1) done(ok)
            else { Studio.log("Android rechazó el toque; se reintenta"); later(token,350) { performWithRetry(service,gesture,token,tries-1,done) } }
        } }
    }
    /** Espera [ms] mirando las reglas ⚡ cada poco. [skip] = lo que hace «Saltar este paso». */
    private fun pauseFor(token: Int, p: Project, simulation: Boolean, ms: Long, skip: (()->Unit)?, done: ()->Unit) {
        if(ms<=0 || rules.isEmpty() || simulation) { later(token,maxOf(ms,0),done); return }
        val end=SystemClock.elapsedRealtime()+ms
        fun tick() {
            val left=end-SystemClock.elapsedRealtime()
            if(left<=0) { later(token,0,done); return }
            later(token,minOf(left,1000L)) { if(SystemClock.elapsedRealtime()>=end) done() else checkpoint(token,p,simulation,skip) { tick() } }
        }
        tick()
    }
    /** Punto seguro: mira si se ve la imagen de alguna regla ⚡. Si no, sigue con [resume]. */
    private fun checkpoint(token: Int, p: Project, simulation: Boolean, skip: (()->Unit)?=null, resume: ()->Unit) {
        val now=SystemClock.elapsedRealtime()
        if(simulation || inRule || rules.isEmpty() || now-lastRuleCheck<800) { resume(); return }
        lastRuleCheck=now
        val ready=rules.filter { now>=(cooldown[it.id] ?: 0L) }
        fun look(i: Int) {
            if(i>=ready.size) { resume(); return }
            val r=ready[i]
            val t=p.templates.firstOrNull { it.id==r.templateId }?.template ?: run { look(i+1); return }
            val before=SystemClock.elapsedRealtime()
            detectMatch(p,t,quiet=true) { match ->
                android.util.Log.d("PrisonMacro","⚡ regla «${r.name}»: ${if(match==null) "no" else "sí"} · ${SystemClock.elapsedRealtime()-before} ms")
                later(token) { if(match==null) look(i+1) else fire(r,token,p,simulation,skip,resume) }
            }
        }
        look(0)
    }
    private fun fire(r: Action, token: Int, p: Project, simulation: Boolean, skip: (()->Unit)?, resume: ()->Unit) {
        val image=p.templates.firstOrNull { it.id==r.templateId }?.name ?: "la imagen"
        Studio.log("⚡ Apareció «$image» → ${r.name}")
        val savedAction=Studio.currentAction; val savedStep=Watch.step; val savedNext=Watch.next
        Watch.step="⚡ Apareció «$image» → ${r.name}"; Watch.next=r.children.firstOrNull { it.enabled }?.name ?: ""; Watch.waitUntil=0
        Studio.lastStep="⚡ Regla ${stepNumber(p.actions,r.id) ?: ""} «${r.name}»"; lastStepId=r.id
        setStatus("$round⚡ ${r.name}")
        inRule=true; Studio.currentAction=r.id
        sequence(r.children,token,p,simulation) {
            inRule=false
            cooldown[r.id]=SystemClock.elapsedRealtime()+(if(r.waitMs>0) r.waitMs else 3000L)
            Studio.currentAction=savedAction; Watch.step=savedStep; Watch.next=savedNext
            when(r.afterRule) {
                AfterRule.CONTINUE -> resume()
                AfterRule.SKIP_STEP -> { if(skip!=null) Studio.log("⚡ Se salta el paso que estaba esperando"); (skip ?: resume)() }
                AfterRule.EXIT_LOOP -> {
                    val frame=loops.removeLastOrNull()
                    val endRound=roundEnd
                    when {
                        frame!=null -> { Studio.log("⚡ Sale del bucle «Repetir»"); frame.exit() }
                        endRound!=null -> { Studio.log("⚡ Termina esta vuelta"); endRound() }
                        else -> resume()
                    }
                }
                AfterRule.RESTART -> { val again=roundRestart; if(again!=null) { loops.clear(); again() } else resume() }
                AfterRule.STOP -> stop("Completado: «${r.name}»")
            }
        }
    }
    private fun readableMs(ms: Long): String = if(ms%1000==0L) "${ms/1000} s" else String.format(java.util.Locale.ROOT,"%.1f s",ms/1000.0).replace('.',',')
    private fun setStatus(text: String) { Studio.status=text; MacroAccessibilityService.instance?.showStatus(text) }
    fun detect(p: Project=Studio.project, done: (Boolean)->Unit={}) = detect(p,p.template,done)
    /** Comprueba dentro de [delayMs] si la imagen elegida está en pantalla y
     * avisa con un mensaje. No ejecuta ningún gesto. */
    fun testImage(templateId: String, delayMs: Long=2500) {
        if(Studio.running || Studio.recording || Studio.detecting) { Studio.fail("Espera a que termine lo que se está ejecutando"); return }
        val p=Studio.project
        val saved=p.templates.firstOrNull { it.id==templateId } ?: run { Studio.fail("Esa imagen ya no existe"); return }
        if(!Studio.captureReady) { Studio.fail("Autoriza la captura de pantalla para probar la imagen"); return }
        main.postDelayed({
            detect(p,saved.template) { found ->
                android.widget.Toast.makeText(Studio.context,(if(found) "✓ Se ve «${saved.name}»" else "✕ No se ve «${saved.name}»")+"\n"+Studio.lastMatch,android.widget.Toast.LENGTH_LONG).show()
            }
        },delayMs)
    }
    private fun detect(p: Project, selected: Template?, done: (Boolean)->Unit) = detectMatch(p,selected) { done(it!=null) }
    /** Último fotograma, solo si la captura tiene ya la orientación actual de la pantalla. */
    private fun freshFrame(): Bitmap? {
        val s=CaptureService.instance ?: return null
        if(!s.matchesDisplay()) { s.resizeToDisplay(); return null }
        return s.frame()
    }
    private fun detectMatch(p: Project, selected: Template?, attempt: Int=0, quiet: Boolean=false, busy: Int=0, done: (Match?)->Unit) {
        // Si la búsqueda anterior aún no ha terminado, se espera a que acabe (antes paraba la macro).
        if(Studio.detecting) {
            if(busy<60) main.postDelayed({ detectMatch(p,selected,attempt,quiet,busy+1,done) },100)
            else if(Studio.running) fail("Una búsqueda de imagen se quedó colgada") else done(null)
            return
        }
        val t=selected
        if(t==null) { Studio.lastMatch="No hay plantilla"; if(Studio.running) stop(Studio.lastMatch); done(null); return }
        // Tras un giro, la captura tarda unos instantes en tener un fotograma nuevo.
        val frame=freshFrame() ?: run {
            if(attempt<8 && CaptureService.instance!=null) { main.postDelayed({ detectMatch(p,selected,attempt+1,quiet,busy,done) },250); return }
            Studio.lastMatch="No hay captura disponible"; if(Studio.running) fail(Studio.lastMatch) else done(null); return
        }
        val token=generation
        // El panel flotante y las marcas del «👁 modo ver» no son del juego: esa zona no se mira.
        val mask=MacroAccessibilityService.instance?.overlayMask().orEmpty()
        // «👁 Modo ver»: el panel enseña la imagen que se busca (o la que vigila una regla ⚡).
        Watch.searching(t.file,p.templates.firstOrNull { it.template.file==t.file }?.name ?: if(t.file==p.template?.file) "Imagen de inicio" else "imagen",rule=quiet)
        Studio.detecting=true
        worker.execute {
            val before=SystemClock.elapsedRealtime()
            var message=""; var match: Match?=null; var failed=false; var best: Match?=null
            try {
                require(t.file.matches(Regex("[a-zA-Z0-9-]+\\.png"))) { "Ruta de plantilla inválida" }
                val template=BitmapFactory.decodeFile(File(Studio.context.filesDir,t.file).path) ?: error("No se puede leer la plantilla")
                try {
                    val result=BitmapDetector.find(frame,template,t,p.mode,mask) { token!=generation }
                    best=result
                    val found=result!=null && result.confidence>=p.threshold
                    match=if(found) result else null
                    message=if(result==null) "Sin coincidencia" else "${if(found) "Encontrada" else "No encontrada"}: ${(result.confidence*100).roundToInt()}% / umbral ${(p.threshold*100).roundToInt()}% · (${result.x}, ${result.y}) · ${SystemClock.elapsedRealtime()-before} ms"
                } finally { template.recycle() }
            } catch(e: Exception) { failed=true; message=e.message ?: "Error de detección" } finally { frame.recycle() }
            results.post { Studio.detecting=false; if(token==generation) {
                if(quiet && match==null && !failed) { done(null); return@post }
                Watch.lastSearch=message.substringBefore(" · (")
                best?.let { b -> if(Watch.enabled) MacroAccessibilityService.instance?.markBox(b.x,b.y,if(b.width>0) b.width else t.width,if(b.height>0) b.height else t.height,b.confidence>=p.threshold,(b.confidence*100).roundToInt()) }
                Studio.lastMatch=message; Studio.log(message); if(failed && Studio.running) fail("Error al buscar la imagen: $message") else done(match) } }
        }
    }
    private fun clickImage(action: Action, token: Int, p: Project, done: ()->Unit, startedAt: Long=0) {
        val selected=p.templates.firstOrNull { it.id==action.templateId }?.template
        // El tiempo máximo cuenta de verdad (antes solo sumaba las pausas y duraba bastante más).
        val since=if(startedAt>0) startedAt else SystemClock.elapsedRealtime()
        if(startedAt==0L) Watch.waiting("Buscando «${p.templates.firstOrNull { it.id==action.templateId }?.name ?: "la imagen"}» (máximo)",if(action.waitMs>0) action.waitMs else p.timeoutMs)
        if(selected==null) { stop("Elige una imagen guardada para ${action.name}"); return }
        detectMatch(p,selected) { match ->
            if(match!=null) {
                val service=MacroAccessibilityService.instance
                if(service==null) { fail("Accesibilidad desconectada"); return@detectMatch }
                val (screenWidth,screenHeight)=service.screenSize()
                // Tamaño de la imagen en ESTA pantalla (puede venir adaptada de otra pantalla).
                val foundW=if(match.width>0) match.width else selected.width
                val foundH=if(match.height>0) match.height else selected.height
                val centerX=match.x+foundW/2f
                val centerY=match.y+foundH/2f
                val targetX=when(action.clickTarget) {
                    ClickTarget.CENTER -> centerX
                    ClickTarget.OFFSET -> centerX+foundW*(action.clickOffsetX/100f)
                    ClickTarget.FIXED -> (screenWidth-1)*action.clickOffsetX/100f
                }.coerceIn(0f,(screenWidth-1).toFloat())
                val targetY=when(action.clickTarget) {
                    ClickTarget.CENTER -> centerY
                    ClickTarget.OFFSET -> centerY+foundH*(action.clickOffsetY/100f)
                    ClickTarget.FIXED -> (screenHeight-1)*action.clickOffsetY/100f
                }.coerceIn(0f,(screenHeight-1).toFloat())
                val gesture=Action(kind=Kind.GESTURE,name=action.name,durationMs=if(action.clickStyle==ClickStyle.LONG) maxOf(500,action.durationMs) else action.durationMs,points=listOf(Point(targetX/(screenWidth-1),targetY/(screenHeight-1))))
                fun send(remaining: Int) { performWithRetry(service,gesture,token) { success ->
                    val limit=if(action.waitMs>0) action.waitMs else p.timeoutMs
                    val timeLeft=SystemClock.elapsedRealtime()-since < limit
                    when {
                        // Android rechazó el toque 3 veces: se vuelve a buscar la imagen (sin parar la macro).
                        !success && timeLeft -> {
                            Studio.log("Android no pudo tocar «${action.name}»; se busca otra vez")
                            later(token,700) { clickImage(action,token,p,done,since) }
                        }
                        !success -> { Studio.log("Android no pudo tocar «${action.name}»; se sigue con el siguiente paso"); done() }
                        remaining>0 -> later(token,120) { send(remaining-1) }
                        else -> done()
                    }
                } }
                send(if(action.clickStyle==ClickStyle.DOUBLE) 1 else 0)
            } else if(SystemClock.elapsedRealtime()-since>=(if(action.waitMs>0) action.waitMs else p.timeoutMs)) {
                if(action.skipIfMissing) { Studio.log("No apareció «${p.templates.firstOrNull { it.id==action.templateId }?.name}»: se salta el paso"); later(token) { done() } }
                else fail("No apareció la imagen de «${action.name}»")
            }
            else later(token,700) { checkpoint(token,p,false,done) { clickImage(action,token,p,done,since) } }
        }
    }
    /** Espera a que aparezca la captura elegida para este paso. A diferencia de
     * la imagen de inicio rápido, esta captura pertenece solo a la acción y
     * permite continuar con el siguiente paso cuando aparece, aunque cambie
     * de posición si el proyecto usa "cualquier punto". */
    /** Rato seguido sin ver la imagen para dar por hecho que ya no está (si el paso no dice otro). */
    const val GONE_DEFAULT_MS=10_000L
    /** «Esperar a que ya no salga»: vuelve cuando la imagen lleva [Action.waitMs] seguidos sin verse.
     * Si sigue saliendo al llegar al máximo, sigue igualmente (no es un fallo: solo no dio tiempo). */
    private fun waitUntilGone(action: Action, token: Int, p: Project, done: ()->Unit, deadline: Long=SystemClock.elapsedRealtime()+action.durationMs, goneSince: Long=0L) {
        val saved=p.templates.firstOrNull { it.id==action.templateId }
        val selected=saved?.template ?: run { stop("Elige una imagen guardada para ${action.name}"); return }
        val needed=if(action.waitMs>0) action.waitMs else GONE_DEFAULT_MS
        if(Watch.waitUntil==0L) { Watch.waitLabel="Esperando a que ya no salga «${saved.name}» (máximo)"; Watch.waitUntil=deadline }
        detectMatch(p,selected) { match ->
            val now=SystemClock.elapsedRealtime()
            val since=if(match!=null) 0L else if(goneSince==0L) now else goneSince
            when {
                since!=0L && now-since>=needed -> { Studio.log("«${saved.name}» ya no sale: se sigue"); later(token) { done() } }
                now>=deadline -> { Studio.log("«${saved.name}» seguía saliendo al llegar al máximo: se sigue"); later(token) { done() } }
                else -> later(token,700) { checkpoint(token,p,false,done) { waitUntilGone(action,token,p,done,deadline,since) } }
            }
        }
    }
    private fun waitForImage(action: Action, token: Int, p: Project, done: ()->Unit, deadline: Long=SystemClock.elapsedRealtime()+action.durationMs) {
        if(action.untilGone) { waitUntilGone(action,token,p,done,deadline); return }
        val selected=p.templates.firstOrNull { it.id==action.templateId }?.template
        if(Watch.waitUntil==0L) { Watch.waitLabel="Esperando a ver «${p.templates.firstOrNull { it.id==action.templateId }?.name ?: "la imagen"}» (máximo)"; Watch.waitUntil=deadline }
        if(selected==null) { stop("Elige una imagen guardada para ${action.name}"); return }
        detectMatch(p,selected) { match ->
            if(match!=null) later(token) { done() }
            else if(SystemClock.elapsedRealtime()>=deadline && action.skipIfMissing) { Studio.log("No apareció la imagen de «${action.name}»: se sigue"); later(token) { done() } }
            else if(SystemClock.elapsedRealtime()>=deadline) fail("Tiempo agotado esperando «${p.templates.firstOrNull { it.id==action.templateId }?.name ?: action.name}»: ${Studio.lastMatch}")
            else later(token,700) { checkpoint(token,p,false,done) { waitForImage(action,token,p,done,deadline) } }
        }
    }
    private fun detectColor(action: Action, attempt: Int=0, busy: Int=0, done: (Boolean)->Unit) {
        if(Studio.detecting) {
            if(busy<60) main.postDelayed({ detectColor(action,attempt,busy+1,done) },100)
            else if(Studio.running) fail("Una búsqueda de color se quedó colgada") else done(false)
            return
        }
        val frame=freshFrame() ?: run {
            if(attempt<8 && CaptureService.instance!=null) { main.postDelayed({ detectColor(action,attempt+1,busy,done) },250); return }
            Studio.lastMatch="No hay captura disponible"; if(Studio.running) fail(Studio.lastMatch) else done(false); return
        }
        val token=generation
        val mask=MacroAccessibilityService.instance?.overlayMask().orEmpty()
        Studio.detecting=true
        worker.execute {
            val before=SystemClock.elapsedRealtime(); var message=""; var found=false; var failed=false
            try {
                val colors=IntArray(frame.width*frame.height); frame.getPixels(colors,0,frame.width,0,0,frame.width,frame.height)
                val exact=if(action.colorMode==SearchMode.EXACT) Pair((action.colorX*(frame.width-1)).roundToInt(),(action.colorY*(frame.height-1)).roundToInt()) else null
                val result=ColorMatcher.find(Pixels(frame.width,frame.height,colors).masked(mask),action.targetColor,action.colorTolerance,exact) { token!=generation }
                found=result!=null
                message=if(result==null) "Color ${rgbHex(action.targetColor)} no encontrado" else "Color ${rgbHex(action.targetColor)} encontrado · (${result.x}, ${result.y}) · apoyo ${(result.support*100).roundToInt()}% · ${SystemClock.elapsedRealtime()-before} ms"
            } catch(e: Exception) { failed=true; message=e.message ?: "Error de detección de color" } finally { frame.recycle() }
            results.post { Studio.detecting=false; if(token==generation) { Studio.lastMatch=message; Studio.log(message); if(failed && Studio.running) fail("Error al buscar el color: $message") else done(found) } }
        }
    }
}
