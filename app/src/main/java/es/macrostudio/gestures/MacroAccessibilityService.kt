package es.macrostudio.gestures

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import es.macrostudio.MainActivity
import es.macrostudio.capture.CaptureService
import es.macrostudio.core.*
import es.macrostudio.editor.CropActivity
import es.macrostudio.engine.Engine
import kotlin.math.abs

/** Assisted recorder: intercept one stroke, remove the touch overlay, replay it, then re-arm.
 * Does not claim unrestricted passive touch logging or simultaneous multi-touch. */
class MacroAccessibilityService: AccessibilityService() {
    companion object { @Volatile var instance: MacroAccessibilityService?=null; private set }
    private val main=Handler(Looper.getMainLooper())
    /** Los cambios de Recientes pueden pausar el hilo de UI de la aplicación.
     * Los callbacks y límites de tiempo de los gestos no deben depender de él. */
    private val gestureThread=HandlerThread("PrisonMacroGestures").apply { start() }
    private val gestureHandler=Handler(gestureThread.looper)
    private lateinit var wm: WindowManager
    private var bar: LinearLayout?=null; private var recorder: View?=null; private var pointPicker: View?=null
    private var recordWidth=0; private var recordHeight=0; private var lastEnd=0L
    private var recordGeneration=0
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    override fun onServiceConnected() { Studio.init(this); instance=this; wm=getSystemService(WindowManager::class.java); Studio.accessibilityReady=true; if(!Studio.running && !Studio.recording) Studio.status="Accesibilidad conectada"; Studio.log("Accesibilidad conectada") }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() { stopRecording(); Engine.stop("Accesibilidad interrumpida") }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        gestureThread.quitSafely()
        if(instance===this) {
            stopRecording(); stopPointPicker(); hideBar(); instance=null; Studio.accessibilityReady=false; Engine.stop("Accesibilidad desconectada")
        } else {
            recordGeneration++
            recorder?.let { runCatching { wm.removeView(it) } }; recorder=null
            pointPicker?.let { runCatching { wm.removeView(it) } }; pointPicker=null
            hideBar()
        }
        super.onDestroy()
    }
    fun screenSize(): Pair<Int,Int> { val m=android.util.DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m); return Pair(m.widthPixels,m.heightPixels) }
    private fun params(width: Int,height: Int)=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.LEFT; if(android.os.Build.VERSION.SDK_INT>=28) layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
    fun showBar() {
        if(bar!=null) { refreshBar(); return }
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(26,31,45)); setPadding(dp(3),dp(3),dp(3),dp(3)) }
        bar=row
        fun button(label: String, block: ()->Unit) { row.addView(Button(this).apply { text=label; isAllCaps=false; textSize=11f; minimumWidth=0; setPadding(dp(5),0,dp(5),0); contentDescription=label; setOnClickListener { block(); refreshBar() } },LinearLayout.LayoutParams(dp(62),dp(48))) }
        button("Captura") { if(!Studio.recording && !Studio.running) capture() }
        button("Grabar") { if(Studio.recording) stopRecording() else if(!Studio.running) startRecording() }
        button("Pausa") { if(Studio.running) Engine.pause() }
        button("PARAR") { stopRecording(); stopPointPicker(); Engine.stop("Parada de emergencia") }
        button("Editor") { stopRecording(); stopPointPicker(); Engine.stop(); startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); hideBar() }
        try { wm.addView(row,params(WindowManager.LayoutParams.WRAP_CONTENT,dp(54)).apply { y=dp(30) }) } catch(e: Exception) { bar=null; Studio.fail("No se pudo mostrar el panel: ${e.message}") }
        refreshBar()
    }
    fun refreshBar() {
        (bar?.getChildAt(1) as? Button)?.text=if(Studio.recording) "FIN REC" else "Grabar"
        (bar?.getChildAt(1) as? Button)?.contentDescription=if(Studio.recording) "Finalizar grabación" else "Grabar gestos"
        (bar?.getChildAt(2) as? Button)?.apply {
            isEnabled=Studio.running
            text=when {
                Studio.paused -> "▶ Seguir"
                Studio.running -> "Ⅱ Pausa"
                else -> "Pausa"
            }
            contentDescription=when {
                Studio.paused -> "Seguir macro"
                Studio.running -> "Pausar macro"
                else -> "Pausa no disponible: no hay una macro ejecutándose"
            }
        }
    }
    fun hideBar() { bar?.let { runCatching { wm.removeView(it) } }; bar=null }
    private fun capture() {
        val service=CaptureService.instance ?: return Studio.fail("Autoriza la captura desde el editor")
        bar?.visibility=View.INVISIBLE
        main.postDelayed({ service.saveScreenshot { success ->
            bar?.visibility=View.VISIBLE
            if(success) {
                hideBar()
                startActivity(Intent(this,CropActivity::class.java)
                    .putExtra("actionId",Studio.pendingTemplateActionId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } },450)
    }
    fun perform(action: Action, done: (Boolean)->Unit) = dispatchAction(action,true,done)
    private fun dispatchAction(action: Action, checkTemplateResolution: Boolean, done: (Boolean)->Unit) {
        val (w,h)=screenSize()
        val template=Studio.project.template
        if(checkTemplateResolution && template!=null && (w!=template.screenWidth || h!=template.screenHeight)) { Studio.log("Resolución distinta a la plantilla; gesto bloqueado"); done(false); return }
        try {
            val path=Path(); action.points.forEachIndexed { index,p -> val x=(p.x*(w-1)).coerceIn(0f,(w-1).toFloat()); val y=(p.y*(h-1)).coerceIn(0f,(h-1).toFloat()); if(index==0) path.moveTo(x,y) else path.lineTo(x,y) }
            val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,action.durationMs.coerceIn(1,60_000))).build()
            var delivered=false
            var timeout: Runnable?=null
            fun finish(success: Boolean) {
                if(!delivered) {
                    delivered=true
                    timeout?.let { gestureHandler.removeCallbacks(it) }
                    done(success)
                }
            }
            val accepted=dispatchGesture(gesture,object: GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { finish(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { finish(false) }
            },gestureHandler)
            if(!accepted) finish(false)
            else {
                // Android normally invokes the callback after the stroke, but a
                // transition (notably Recientes) can occasionally lose it. Never
                // leave Engine or the assisted recorder waiting forever.
                timeout=Runnable { finish(false) }
                gestureHandler.postDelayed(timeout!!,action.durationMs.coerceIn(40,60_000)+1_500L)
            }
        } catch(e: Exception) { Studio.log("Gesto fallido: ${e.message}"); done(false) }
    }
    fun performSystemAction(action: Action): Boolean = try {
        when(action.kind) {
            Kind.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            Kind.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Kind.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            Kind.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            Kind.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            Kind.OPEN_APP -> {
                val intent=packageManager.getLaunchIntentForPackage(action.packageName)
                if(intent==null) {Studio.log("App no instalada o sin pantalla de inicio: ${action.packageName}"); false}
                else {startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true}
            }
            else -> false
        }
    } catch(e: Exception) {Studio.log("Acción del sistema rechazada: ${e.message}"); false}
    /** Pixel swipes are the fallback. Recent Android launchers often expose a
     * semantic dismiss action for the task card, which is more stable during
     * the heavy Recents animation. */
    private fun dismissVisibleRecentsTask(): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean {
            val id=node.viewIdResourceName.orEmpty()
            val isTaskCard=id.endsWith(":id/task_view_single") || id.endsWith(":id/task_view")
            if(isTaskCard && node.isVisibleToUser && node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) return true
            for(index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    val dismissed=visit(child)
                    child.recycle()
                    if(dismissed) return true
                }
            }
            return false
        }
        val root=runCatching { rootInActiveWindow }.getOrNull() ?: return false
        return try { visit(root) } finally { root.recycle() }
    }
    fun closeActiveFromRecents(waitMs: Long, done: (Boolean)->Unit) {
        var delivered=false
        var watchdog: Runnable?=null
        fun finish(success: Boolean) {
            if(!delivered) {
                delivered=true
                watchdog?.let { gestureHandler.removeCallbacks(it) }
                done(success)
            }
        }
        watchdog=Runnable {
            if(!delivered) {
                Studio.log("Recientes no permitió retirar la tarjeta a tiempo; se vuelve a Inicio")
                finish(performGlobalAction(GLOBAL_ACTION_HOME))
            }
        }
        // Keep the timeout on the gesture worker. The app UI can be paused by
        // the Recents transition, but the close operation must still complete.
        gestureHandler.postDelayed(watchdog!!,waitMs.coerceIn(500,3_000)+2_500L)
        if(!gestureHandler.post {
            try {
                if(!performGlobalAction(GLOBAL_ACTION_RECENTS)) { finish(false); return@post }
                gestureHandler.postDelayed({
                    // LabActivity and some games force landscape. Recents can open
                    // while Android is still rotating; calculate the swipe only
                    // after that transition has settled, otherwise the normalized
                    // coordinates may be converted from the old portrait size.
                    var previousSize=screenSize()
                    if(dismissVisibleRecentsTask()) {
                        gestureHandler.postDelayed({ finish(performGlobalAction(GLOBAL_ACTION_HOME)) },250)
                        return@postDelayed
                    }
                    fun swipe(attempt: Int) { dispatchAction(closeRecentSwipeAction(),false) { swiped ->
                        if(!swiped && attempt<2) gestureHandler.postDelayed({ swipe(attempt+1) },350)
                        else if(!swiped) finish(false)
                        else gestureHandler.postDelayed({
                                // Volver a Inicio evita abrir por accidente otra tarjeta.
                                finish(performGlobalAction(GLOBAL_ACTION_HOME))
                            },250)
                        }
                    }
                    fun waitForStableScreen(attempt: Int) {
                        gestureHandler.postDelayed({
                            val currentSize=screenSize()
                            if(currentSize==previousSize || attempt>=8) swipe(1)
                            else { previousSize=currentSize; waitForStableScreen(attempt+1) }
                        },250)
                    }
                    waitForStableScreen(0)
                },waitMs.coerceIn(500,3_000)+1_200L)
            } catch(e: Exception) { Studio.log("No se pudo cerrar desde Recientes: ${e.message}"); finish(false) }
        }) finish(false)
    }
    /**
     * Captura un único punto real de la pantalla para editar una acción que ya
     * existe. El overlay no reproduce el toque: solo lo registra y vuelve al
     * editor, para que elegir una coordenada no dispare por accidente un botón
     * del juego o de otra aplicación.
     */
    fun startPointPicker(actionId: String) {
        if(Studio.running) { Studio.fail("Detén la macro antes de elegir un punto"); return }
        if(Studio.recording || recorder!=null || pointPicker!=null) return
        val action=findAction(Studio.project.actions,actionId)
        if(action==null) { Studio.fail("La acción ya no existe"); return }
        val (w,h)=screenSize()
        if(w<2 || h<2) { Studio.fail("No se pudo leer el tamaño de la pantalla"); return }
        Toast.makeText(this,"Toca una vez el punto que quieres guardar",Toast.LENGTH_LONG).show()
        val overlay=View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        overlay.setOnTouchListener { _, event ->
            if(event.actionMasked==MotionEvent.ACTION_UP) {
                val currentSize=screenSize()
                if(currentSize!=Pair(w,h)) {
                    stopPointPicker(); Studio.fail("Cambió la orientación; vuelve a elegir el punto"); return@setOnTouchListener true
                }
                val point=normalizedScreenPoint(event.rawX,event.rawY,w,h)
                val updated=updateAction(Studio.project.actions,actionId) { current ->
                    when(current.kind) {
                        Kind.CLICK_IMAGE -> current.copy(
                            clickTarget=ClickTarget.FIXED,
                            clickOffsetX=point.x*100f,
                            clickOffsetY=point.y*100f
                        )
                        Kind.GESTURE -> current.copy(points=listOf(point))
                        else -> current
                    }
                }
                val saved=Studio.update(Studio.project.copy(actions=updated))
                stopPointPicker()
                if(saved) {
                    val percent="${(point.x*100).toInt()} %, ${(point.y*100).toInt()} %"
                    Studio.status="Punto guardado: $percent"
                    Studio.log("Punto capturado para ${action.name}: $percent")
                    Toast.makeText(this,"Punto guardado: $percent",Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
            true
        }
        pointPicker=overlay
        try {
            wm.addView(overlay,params(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT))
            Studio.status="Elige un punto tocando la pantalla"
            Studio.log("Selector de punto iniciado para ${action.name}")
        } catch(e: Exception) {
            pointPicker=null
            Studio.fail("No se pudo capturar el punto: ${e.message}")
        }
    }

    fun stopPointPicker() {
        pointPicker?.let { runCatching { wm.removeView(it) } }
        pointPicker=null
    }

    private fun findAction(items: List<Action>,id: String): Action? {
        items.forEach { action ->
            if(action.id==id) return action
            findAction(action.children,id)?.let { return it }
            findAction(action.otherwise,id)?.let { return it }
        }
        return null
    }

    fun startRecording() {
        if(Studio.running) return
        if(recorder!=null) return
        val (w,h)=screenSize(); recordWidth=w; recordHeight=h
        val template=Studio.project.template
        if(template!=null && (w!=template.screenWidth || h!=template.screenHeight)) { Studio.fail("La plantilla tiene otra orientación; vuelve a capturar"); return }
        val generation=++recordGeneration; lastEnd=SystemClock.elapsedRealtime()
        val points=ArrayList<Point>(); var downAt=0L; var gap=0L
        val overlay=View(this).apply { setBackgroundColor(Color.argb(12,255,70,100)) }
        fun setRecordingTouchable(enabled: Boolean): Boolean {
            val layout=params(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
            if(!enabled) layout.flags=layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            return runCatching { wm.updateViewLayout(overlay,layout); true }.getOrDefault(false)
        }
        overlay.setOnTouchListener { _, event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { points.clear(); downAt=SystemClock.elapsedRealtime(); gap=downAt-lastEnd; points.add(Point((event.rawX/(w-1)).coerceIn(0f,1f),(event.rawY/(h-1)).coerceIn(0f,1f))) }
                MotionEvent.ACTION_POINTER_DOWN -> { points.clear(); Studio.log("Multitáctil no admitido en el grabador asistido") }
                MotionEvent.ACTION_MOVE -> if(points.isNotEmpty() && points.size<999 && event.pointerCount==1) points.add(Point((event.rawX/(w-1)).coerceIn(0f,1f),(event.rawY/(h-1)).coerceIn(0f,1f)))
                MotionEvent.ACTION_CANCEL -> points.clear()
                MotionEvent.ACTION_UP -> if(points.isNotEmpty()) {
                    if(screenSize()!=Pair(recordWidth,recordHeight)) { stopRecording(); Studio.fail("Cambió la orientación durante la grabación") }
                    else {
                        points.add(Point((event.rawX/(w-1)).coerceIn(0f,1f),(event.rawY/(h-1)).coerceIn(0f,1f)))
                        val duration=(SystemClock.elapsedRealtime()-downAt).coerceIn(40,60_000)
                        // Un toque genera DOWN y UP en el mismo lugar. Guardarlo como
                        // una línea de longitud cero puede hacer que un ScrollView lo
                        // interprete de forma distinta; normalízalo a una pulsación.
                        val recordedPoints=if(points.size<=2 && abs(points.last().x-points.first().x)<.002f && abs(points.last().y-points.first().y)<.002f) listOf(points.first()) else points.toList()
                        val action=Action(name=if(recordedPoints.size==1) "Pulsación grabada" else "Trayectoria grabada",durationMs=duration,points=recordedPoints)
                        // Deja la ventana puesta, pero sin tocar, para que el gesto
                        // reproducido llegue a la aplicación que estaba debajo.
                        if(!setRecordingTouchable(false)) Studio.fail("No se pudo liberar la pantalla para reproducir el gesto")
                        main.postDelayed({ if(generation==recordGeneration) perform(action) { success ->
                            // Android puede avisar de que la inyección terminó antes de
                            // que la aplicación destino procese el UP.
                            main.postDelayed({
                            if(generation==recordGeneration) {
                                if(success) {
                                    val wait=if(gap>=200) listOf(Action(kind=Kind.WAIT,name="Espera grabada",durationMs=gap.coerceAtMost(600_000))) else emptyList()
                                    if(Studio.update(Studio.project.copy(actions=Studio.project.actions+wait+action))) Studio.log("Gesto grabado (${duration} ms)") else stopRecording()
                                } else Studio.fail("No se grabó el gesto: Android lo rechazó")
                                lastEnd=SystemClock.elapsedRealtime()
                                if(generation==recordGeneration && recorder===overlay && Studio.recording && !setRecordingTouchable(true)) {
                                    stopRecording(); Studio.fail("No se pudo reanudar la grabación")
                                }
                            }
                            // En emuladores y dispositivos cargados el clic puede
                            // llegar después del callback de dispatchGesture.
                            // Rearmar después de este margen evita tragarse ese UP.
                            // Algunos botones entregan su onClick con más retraso
                            // cuando el emulador viene de varias pruebas seguidas.
                            },1200)
                        } },100)
                    }
                }
            }; true
        }
        recorder=overlay
        try { hideBar(); wm.addView(overlay,params(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)); Studio.recording=true; showBar(); Studio.status="Grabando: cada gesto se reproduce al soltar"; Studio.log("Grabación asistida iniciada") }
        catch(e: Exception) { stopRecording(); Studio.fail("No se pudo grabar: ${e.message}") }
    }
    fun stopRecording() {
        recordGeneration++
        recorder?.let { runCatching { wm.removeView(it) } }; recorder=null
        if(Studio.recording) Studio.log("Grabación guardada")
        Studio.recording=false; refreshBar()
    }
}
