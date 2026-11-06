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
    private var statusView: TextView?=null
    private var bar: LinearLayout?=null; private var recorder: View?=null; private var pointPicker: View?=null
    private var recordWidth=0; private var recordHeight=0; private var lastEnd=0L
    private var recordGeneration=0
    /** Grabación: true cuando el siguiente toque se va a grabar. Mientras se
     * reproduce el toque anterior, los toques pasan al juego sin grabarse. */
    private var recordArmed=false; private var recordedCount=0
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
    /** Modo del panel flotante. */
    enum class BarMode { CONTROL, CAPTURE }
    private var barMode=BarMode.CONTROL
    private var barX=0; private var barY=-1

    /** Panel de control: ▶/Ⅱ, ■, grabar, capturar y volver a la app. */
    fun showBar() { barMode=BarMode.CONTROL; ensureBar() }

    /** Panel reducido para capturar la pantalla de otra app. La imagen se
     * asignará a [actionId] (o a la imagen de inicio, o solo a la biblioteca). */
    fun startCaptureMode(actionId: String?) {
        if(Studio.running) { Studio.fail("Detén la macro antes de capturar"); return }
        stopRecording()
        Studio.pendingTemplateActionId=actionId
        barMode=BarMode.CAPTURE
        ensureBar()
        Toast.makeText(this,"Ve a la pantalla que quieras y pulsa 📷 Capturar",Toast.LENGTH_LONG).show()
    }

    private fun ensureBar() {
        if(bar==null) {
            val row=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(235,23,31,48)); cornerRadius=dp(14).toFloat(); setStroke(dp(1),Color.rgb(109,226,209)) }
                setPadding(dp(4),dp(4),dp(4),dp(4))
            }
            bar=row
            if(barY<0) barY=dp(30)
            try { wm.addView(row,params(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT).apply { x=barX; y=barY }) }
            catch(e: Exception) { bar=null; Studio.fail("No se pudo mostrar el panel: ${e.message}"); return }
        }
        refreshBar()
    }

    /** Texto pequeño del panel: paso en curso, vuelta o estado de la grabación. */
    fun showStatus(text: String) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { showStatus(text) }; return }
        statusView?.let { it.text=text; it.visibility=if(text.isBlank()) View.GONE else View.VISIBLE }
    }

    fun refreshBar() {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { refreshBar() }; return }
        val row=bar ?: return
        row.removeAllViews()
        row.addView(dragHandle(row))
        statusView=null
        fun button(label: String, description: String, color: Int=Color.rgb(44,56,80), enabled: Boolean=true, widthDp: Int=52, block: ()->Unit) {
            row.addView(Button(this).apply {
                text=label; isAllCaps=false; textSize=15f; minimumWidth=0; minWidth=0; minimumHeight=0; minHeight=0
                setPadding(dp(4),0,dp(4),0); contentDescription=description; isEnabled=enabled; alpha=if(enabled) 1f else .4f
                setTextColor(Color.WHITE)
                background=android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius=dp(10).toFloat() }
                setOnClickListener { block(); refreshBar() }
            },LinearLayout.LayoutParams(dp(widthDp),dp(44)).apply { setMargins(dp(2),0,dp(2),0) })
        }
        when {
            barMode==BarMode.CAPTURE -> {
                button("📷 Capturar","Capturar pantalla",Color.rgb(20,120,110),widthDp=118) { capture() }
                button("✕","Cancelar captura") { Studio.stepOfTarget(Studio.pendingTemplateActionId)?.let { Studio.reopenActionId=it }; Studio.pendingTemplateActionId=null; openApp() }
            }
            Studio.recording -> {
                button("■ Terminar grabación","Terminar grabación",Color.rgb(170,40,60),widthDp=170) { stopRecording(); Studio.requestedScreen="editor"; openApp() }
                addStatus(row,if(recordArmed) "● Toca para grabar · $recordedCount" else "⏳ Espera…")
            }
            else -> {
                val running=Studio.running
                button(if(!running || Studio.paused) "▶" else "Ⅱ",if(!running) "Reproducir ${Studio.project.name}" else if(Studio.paused) "Seguir" else "Pausa",Color.rgb(20,120,110)) {
                    if(!running) Engine.start(Studio.project.startOnImage) else Engine.pause()
                }
                button("■","Parar",Color.rgb(150,40,55),enabled=running) { Engine.stop("Parada manual") }
                button("●","Grabar gestos",enabled=!running) { startRecording() }
                button("📷","Capturar imagen",enabled=!running) { barMode=BarMode.CAPTURE; Studio.pendingTemplateActionId=null; capture() }
                button("⌂","Abrir PrisonMacro") { stopPointPicker(); Engine.stop(); openApp() }
                button("✕","Ocultar panel") { hideBar() }
                if(running) addStatus(row,Studio.status)
            }
        }
    }

    private fun addStatus(row: LinearLayout, text: String) {
        val view=TextView(this).apply {
            this.text=text; textSize=12f; setTextColor(Color.rgb(200,225,230)); maxLines=2; maxWidth=dp(150)
            ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(6),0,dp(4),0)
        }
        statusView=view
        row.addView(view,LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun dragHandle(row: LinearLayout): View = TextView(this).apply {
        text="⠿"; textSize=20f; setTextColor(Color.rgb(160,180,200)); gravity=Gravity.CENTER; contentDescription="Mover panel"
        var startX=0f; var startY=0f; var originX=0; var originY=0
        setOnTouchListener { _, e ->
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX=e.rawX; startY=e.rawY; originX=barX; originY=barY }
                MotionEvent.ACTION_MOVE -> {
                    barX=(originX+(e.rawX-startX)).toInt().coerceAtLeast(0); barY=(originY+(e.rawY-startY)).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(row,(row.layoutParams as WindowManager.LayoutParams).apply { x=barX; y=barY }) }
                }
            }
            true
        }
        layoutParams=LinearLayout.LayoutParams(dp(26),dp(44))
    }

    private fun openApp() {
        hideBar()
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    fun hideBar() { bar?.let { runCatching { wm.removeView(it) } }; bar=null; barMode=BarMode.CONTROL }

    private fun capture() {
        val service=CaptureService.instance ?: run {
            Studio.fail("Primero autoriza la captura de pantalla desde PrisonMacro"); openApp(); return
        }
        bar?.visibility=View.INVISIBLE
        main.postDelayed({ service.saveScreenshot { success ->
            bar?.visibility=View.VISIBLE
            if(success) {
                val target=Studio.pendingTemplateActionId
                hideBar()
                startActivity(Intent(this,CropActivity::class.java)
                    .putExtra("actionId",target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } },450)
    }
    fun perform(action: Action, done: (Boolean)->Unit) = dispatchAction(action,true,done)
    private fun dispatchAction(action: Action, checkTemplateResolution: Boolean, done: (Boolean)->Unit) {
        val (w,h)=screenSize()
        val template=Studio.project.template.takeIf { Studio.project.startOnImage }
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
    fun startPointPicker(actionId: String, swipe: Boolean=false) {
        if(Studio.running) { Studio.fail("Detén la macro antes de elegir un punto"); return }
        if(Studio.recording || recorder!=null || pointPicker!=null) return
        val action=findAction(Studio.project.actions,actionId)
        if(action==null) { Studio.fail("La acción ya no existe"); return }
        val (w,h)=screenSize()
        if(w<2 || h<2) { Studio.fail("No se pudo leer el tamaño de la pantalla"); return }
        val help=if(swipe) "Arrastra el dedo desde donde empieza hasta donde acaba el deslizamiento" else "Toca una vez el punto que quieres guardar"
        // Capa tenue con instrucciones: así se ve que el toque no llega al juego.
        val overlay=FrameLayout(this).apply {
            setBackgroundColor(Color.argb(40,109,226,209))
            addView(TextView(this@MacroAccessibilityService).apply {
                text="👆 $help"; textSize=15f; setTextColor(Color.WHITE); setPadding(dp(14),dp(8),dp(14),dp(8))
                background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(210,23,31,48)); cornerRadius=dp(12).toFloat() }
            },FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin=dp(40) })
        }
        var downPoint: Point?=null
        overlay.setOnTouchListener { _, event ->
            if(event.actionMasked==MotionEvent.ACTION_DOWN) downPoint=normalizedScreenPoint(event.rawX,event.rawY,w,h)
            if(event.actionMasked==MotionEvent.ACTION_UP) {
                val currentSize=screenSize()
                if(currentSize!=Pair(w,h)) {
                    stopPointPicker(); Studio.fail("Cambió la orientación; vuelve a elegir el punto"); return@setOnTouchListener true
                }
                val point=normalizedScreenPoint(event.rawX,event.rawY,w,h)
                val start=downPoint ?: point
                if(swipe && abs(start.x-point.x)<.02f && abs(start.y-point.y)<.02f) {
                    Toast.makeText(this,"Ha sido un toque: arrastra el dedo para marcar el deslizamiento",Toast.LENGTH_SHORT).show(); return@setOnTouchListener true
                }
                val updated=updateAction(Studio.project.actions,actionId) { current ->
                    when(current.kind) {
                        Kind.CLICK_IMAGE -> current.copy(
                            clickTarget=ClickTarget.FIXED,
                            clickOffsetX=point.x*100f,
                            clickOffsetY=point.y*100f
                        )
                        Kind.GESTURE -> current.copy(points=if(swipe) listOf(start,point) else listOf(point), durationMs=if(swipe && current.durationMs<150) 300 else current.durationMs)
                        else -> current
                    }
                }
                val saved=Studio.update(Studio.project.copy(actions=updated))
                stopPointPicker()
                if(saved) {
                    val percent=(if(swipe) "${(start.x*100).toInt()} %, ${(start.y*100).toInt()} % → " else "")+"${(point.x*100).toInt()} %, ${(point.y*100).toInt()} %"
                    Studio.status="Punto guardado: $percent"
                    Studio.log("Punto capturado para ${action.name}: $percent")
                    Toast.makeText(this,"Punto guardado: $percent",Toast.LENGTH_SHORT).show()
                    Studio.reopenActionId=actionId; Studio.requestedScreen="editor"
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

        val generation=++recordGeneration; lastEnd=SystemClock.elapsedRealtime()
        val points=ArrayList<Point>(); var downAt=0L; var gap=0L
        val overlay=View(this).apply { setBackgroundColor(Color.argb(28,255,70,100)) }
        recordArmed=true; recordedCount=0
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
                        recordArmed=false; showStatus("⏳ Espera…"); overlay.setBackgroundColor(Color.TRANSPARENT)
                        main.postDelayed({ if(generation==recordGeneration) perform(action) { success ->
                            // Android puede avisar de que la inyección terminó antes de
                            // que la aplicación destino procese el UP.
                            main.postDelayed({
                            if(generation==recordGeneration) {
                                if(success) {
                                    val wait=if(gap>=200) listOf(Action(kind=Kind.WAIT,name="Espera grabada",durationMs=gap.coerceAtMost(600_000))) else emptyList()
                                    if(Studio.update(Studio.project.copy(actions=Studio.project.actions+wait+action))) { recordedCount++; Studio.log("Gesto grabado (${duration} ms)") } else stopRecording()
                                } else Studio.fail("No se grabó el gesto: Android lo rechazó")
                                lastEnd=SystemClock.elapsedRealtime()
                                if(generation==recordGeneration && recorder===overlay && Studio.recording) {
                                    if(!setRecordingTouchable(true)) { stopRecording(); Studio.fail("No se pudo reanudar la grabación") }
                                    else { recordArmed=true; overlay.setBackgroundColor(Color.argb(28,255,70,100)); showStatus("● Toca para grabar · $recordedCount") }
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
        try { hideBar(); wm.addView(overlay,params(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)); Studio.recording=true; barMode=BarMode.CONTROL; ensureBar(); Studio.status="Grabando: cada gesto se reproduce al soltar"; Studio.log("Grabación asistida iniciada") }
        catch(e: Exception) { stopRecording(); Studio.fail("No se pudo grabar: ${e.message}") }
    }
    fun stopRecording() {
        recordGeneration++
        recorder?.let { runCatching { wm.removeView(it) } }; recorder=null
        if(Studio.recording) Studio.log("Grabación guardada")
        Studio.recording=false; refreshBar()
    }
}
