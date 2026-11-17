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
    /** Fila de botones dentro del panel, y el recuadro de información del «👁 modo ver». */
    private var barRow: LinearLayout?=null; private var watchInfo: TextView?=null
    /** Recuadro del «👁 modo ver»: la imagen que se busca (medio transparente) y el texto. */
    private var watchBox: LinearLayout?=null; private var watchImage: android.widget.ImageView?=null; private var watchImageFile=""
    private var watchSearchLabel: TextView?=null
    private var marksView: MarksView?=null
    private val watchTicker=object: Runnable { override fun run() {
        watchInfo?.let { it.text=es.macrostudio.engine.Watch.text(Studio.running,Studio.paused) }
        updateWatchImage()
        marksView?.invalidate()
        if(bar!=null && es.macrostudio.engine.Watch.enabled) main.postDelayed(this,250)
    } }
    private var recordWidth=0; private var recordHeight=0; private var lastEnd=0L
    private var recordGeneration=0
    /** Grabación: true cuando el siguiente toque se va a grabar. Mientras se
     * reproduce el toque anterior, los toques pasan al juego sin grabarse. */
    private var recordArmed=false; private var recordedCount=0
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    override fun onServiceConnected() { Studio.init(this); es.macrostudio.engine.Watch.enabled=getSharedPreferences("studio",0).getBoolean("watchMode",false); barCollapsed=getSharedPreferences("studio",0).getBoolean("barCollapsed",false); instance=this; wm=getSystemService(WindowManager::class.java); Studio.accessibilityReady=true; if(!Studio.running && !Studio.recording) Studio.status="Accesibilidad conectada"; Studio.log("Accesibilidad conectada")
        // Si la macro se paró sola (o Android cerró la app) y tenía que seguir, vuelve a empezar.
        es.macrostudio.engine.Engine.resumePending(15_000)
        // Si Android cerró PrisonMacro con la barra puesta (p. ej. «Cerrar todo»), la barra vuelve sola.
        val interrupted=es.macrostudio.engine.KeepAlive.takeInterruptedMacro(this)
        if(es.macrostudio.engine.KeepAlive.wasBarShown(this)) main.postDelayed({
            if(instance===this && bar==null) {
                showBar()
                if(interrupted) {
                    Studio.log("Android cerró PrisonMacro y cortó la macro")
                    val again=es.macrostudio.engine.Engine.resumeIsPending()
                    Toast.makeText(this,if(again) "Android cerró PrisonMacro y la macro se cortó. 🔁 Vuelve a empezar sola." else "Android cerró PrisonMacro y la macro se cortó. Pulsa ▶ para seguir.",Toast.LENGTH_LONG).show()
                }
            }
        },1200)
    }
    fun isBarShown(): Boolean = bar!=null
    /** PrisonMacro está delante: el panel se aparta (tapaba la flecha «←» y el título) y vuelve
     * en cuanto se sale a otra app. */
    fun setAppInFront(front: Boolean) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { setAppInFront(front) }; return }
        appInFront=front
        bar?.visibility=if(front) View.GONE else View.VISIBLE
        marksView?.visibility=if(front) View.GONE else View.VISIBLE
    }
    private var appInFront=false
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
    enum class BarMode { CONTROL, CAPTURE, BURST }
    private var barMode=BarMode.CONTROL
    private var barX=0; private var barY=-1
    /** Panel plegado: solo se ve una burbuja (el asa ⠿). Tocar el asa pliega o despliega;
     * arrastrarla lo mueve. Se recuerda entre sesiones. */
    private var barCollapsed=false
    /** Plegado solo en el panel normal. Si la app pide algo («Abre tu juego y pulsa ▶»), se enseña desplegado; al pulsar ▶ se vuelve a plegar. */
    private fun collapsedNow()=barCollapsed && barMode==BarMode.CONTROL && !Studio.recording && barHint.isBlank()
    private fun toggleCollapsed() {
        barCollapsed=!barCollapsed
        getSharedPreferences("studio",0).edit().putBoolean("barCollapsed",barCollapsed).apply()
        refreshBar()
    }

    /** Panel de control: ▶/Ⅱ, ■, grabar, capturar y volver a la app. */
    fun showBar(hint: String="") { barHint=hint; barMode=BarMode.CONTROL; ensureBar(); refreshBar(); if(bar!=null) es.macrostudio.engine.KeepAlive.barShown(this,true) }
    /** Qué hacer ahora, escrito en el propio panel (antes era un aviso que desaparecía): «Ve a tu juego y pulsa ▶». */
    private var barHint=""

    /** Panel reducido para capturar la pantalla de otra app. La imagen se
     * asignará a [actionId] (o a la imagen de inicio, o solo a la biblioteca). */
    fun startCaptureMode(actionId: String?) {
        if(Studio.running) { Studio.fail("Detén la macro antes de capturar"); return }
        stopRecording()
        Studio.pendingTemplateActionId=actionId
        barMode=BarMode.CAPTURE
        // Las instrucciones van en el propio panel (se esconde al capturar). Un aviso flotante
        // seguía en pantalla unos segundos y salía dentro de la foto.
        ensureBar()
    }

    /** Capturas rápidas: varias fotos seguidas del juego, para recortar después en la app. */
    private var burstCount=0
    fun startBurstMode() {
        if(Studio.running) { Studio.fail("Detén la macro antes de capturar"); return }
        stopRecording()
        barMode=BarMode.BURST; burstCount=0
        ensureBar()
    }

    /** Zonas de la pantalla ocupadas por PrisonMacro (panel flotante y marcas del «👁 modo ver»):
     * la búsqueda de imágenes y colores no las mira. Coordenadas de la pantalla. */
    fun overlayMask(): List<es.macrostudio.vision.Area> {
        val out=mutableListOf<es.macrostudio.vision.Area>()
        bar?.takeIf { it.isAttachedToWindow && it.visibility==View.VISIBLE && it.width>0 }?.let { v ->
            val at=IntArray(2).also { v.getLocationOnScreen(it) }; val pad=dp(3)
            out+=es.macrostudio.vision.Area(at[0]-pad,at[1]-pad,at[0]+v.width+pad,at[1]+v.height+pad)
        }
        marksView?.takeIf { it.visibility==View.VISIBLE }?.let { out+=it.activeRects() }
        return out
    }

    /** Para las fotos del panel: esconde todo lo de PrisonMacro y lo vuelve a enseñar. */
    private fun setOverlaysVisible(visible: Boolean) {
        // El panel se vuelve transparente pero sigue ahí: así los toques durante la foto no
        // llegan al juego (con INVISIBLE pasaban y pulsaban lo que hubiera debajo).
        bar?.alpha=if(visible) 1f else 0f
        marksView?.visibility=if(visible) View.VISIBLE else View.INVISIBLE
    }
    /** Hay una foto en marcha: los botones del panel no hacen nada hasta que termine. */
    private var capturing=false

    /** Esconde todo lo de PrisonMacro y avisa (con la hora, System.nanoTime) cuando Android ya
     * ha pintado la pantalla sin ello. Antes se esperaba un tiempo fijo y, en equipos lentos,
     * la foto salía con el panel flotante dentro. */
    private fun hideOverlaysThen(then: (Long)->Unit) {
        val views=listOfNotNull(bar,marksView)
        val startedAt=System.nanoTime()
        setOverlaysVisible(false)
        var pending=views.size; var finished=false; var latest=startedAt
        fun finish(at: Long) { if(!finished) { finished=true; then(at) } }
        if(views.isEmpty()) { finish(startedAt); return }
        // [at]: momento en que esa ventana ya entregó a Android su imagen sin el panel.
        fun one(at: Long) { latest=maxOf(latest,at); pending--; if(pending<=0) finish(latest) }
        views.forEach { v ->
            if(android.os.Build.VERSION.SDK_INT>=29) v.viewTreeObserver.registerFrameCommitCallback { val at=System.nanoTime(); main.post { one(at) } }
            else {
                // Android 9 (BlueStacks): sin aviso de «entregado»; se da un margen tras pintar.
                val listener=object: android.view.ViewTreeObserver.OnDrawListener {
                    override fun onDraw() { val at=System.nanoTime()+100_000_000L; main.post { runCatching { v.viewTreeObserver.removeOnDrawListener(this) }; one(at) } }
                }
                v.viewTreeObserver.addOnDrawListener(listener)
            }
            v.invalidate()
        }
        // Por si Android no avisa: como mucho, un segundo.
        main.postDelayed({ finish(System.nanoTime()) },1000)
    }

    private fun burstShot() {
        val service=CaptureService.instance ?: run { Studio.panelNeedsCapture="burst"; openApp(); return }
        if(capturing) return
        capturing=true
        hideOverlaysThen { hiddenAt -> service.saveScreenshot(hiddenAt) { success ->
            setOverlaysVisible(true); capturing=false
            if(success && es.macrostudio.capture.Shots.saveLatest(this)!=null) {
                burstCount++; Studio.screenshotVersion++
                // Sin aviso flotante: salía en la foto siguiente. El panel cuenta las fotos.
                refreshBar(); showStatus("✓ Foto $burstCount guardada")
            }
        } }
    }

    private fun ensureBar() {
        if(bar==null) {
            val root=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(235,23,31,48)); cornerRadius=dp(14).toFloat(); setStroke(dp(1),Color.rgb(109,226,209)) }
                setPadding(dp(4),dp(4),dp(4),dp(4))
            }
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            val info=TextView(this).apply {
                textSize=13f; setTextColor(Color.rgb(220,236,240)); setPadding(dp(8),dp(6),dp(8),dp(4)); maxWidth=dp(420)
            }
            // La imagen que se está buscando, medio transparente, con su nombre debajo.
            val image=android.widget.ImageView(this).apply { alpha=0.6f; scaleType=android.widget.ImageView.ScaleType.FIT_CENTER }
            val label=TextView(this).apply { textSize=11f; setTextColor(Color.rgb(160,200,205)); maxWidth=dp(120); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }
            val imageColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(dp(6),dp(6),dp(2),dp(4)); visibility=View.GONE; addView(image,LinearLayout.LayoutParams(dp(110),dp(62))); addView(label) }
            val box=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; visibility=View.GONE; addView(imageColumn); addView(info) }
            root.addView(row); root.addView(box)
            bar=root; barRow=row; watchInfo=info; watchBox=box; watchImage=image; watchSearchLabel=label; watchImageFile=""
            if(barY<0) barY=dp(30)
            if(es.macrostudio.engine.KeepAlive.appScreens>0) { appInFront=true; root.visibility=View.GONE }
            try { wm.addView(root,params(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT).apply { x=barX; y=barY }) }
            catch(e: Exception) {
                bar=null; barRow=null; watchInfo=null; watchBox=null; watchImage=null; watchSearchLabel=null
                if(e is WindowManager.BadTokenException) {
                    // Android dejó la accesibilidad «colgada» (pasa a veces tras actualizar la app).
                    // Se marca como apagada para que la app ofrezca «Activar» y lo explique.
                    if(instance===this) { instance=null; Studio.accessibilityReady=false }
                    Studio.fail("La accesibilidad de PrisonMacro se ha quedado colgada (pasa a veces al actualizar). Apágala y vuelve a encenderla en Ajustes › Accesibilidad.")
                } else Studio.fail("No se pudo mostrar el panel: ${e.message}")
                return
            }
        }
        refreshBar()
        applyWatch()
    }

    /** Enciende o apaga el «👁 modo ver» (recuadro de información y dibujo de los toques). */
    private fun toggleWatch() {
        es.macrostudio.engine.Watch.enabled=!es.macrostudio.engine.Watch.enabled
        getSharedPreferences("studio",0).edit().putBoolean("watchMode",es.macrostudio.engine.Watch.enabled).apply()
        Toast.makeText(this,if(es.macrostudio.engine.Watch.enabled) "👁 Modo ver: verás los toques, las esperas y el siguiente paso" else "Modo ver apagado",Toast.LENGTH_SHORT).show()
        applyWatch()
    }

    /** Enseña (medio transparente) la imagen que la macro está buscando ahora mismo. */
    private fun updateWatchImage() {
        val image=watchImage ?: return
        val column=image.parent as? View ?: return
        val file=if(Studio.running) es.macrostudio.engine.Watch.currentSearch() else null
        if(file==null) { column.visibility=View.GONE; return }
        if(file!=watchImageFile) {
            watchImageFile=file
            val bitmap=runCatching { android.graphics.BitmapFactory.decodeFile(java.io.File(filesDir,file).path) }.getOrNull()
            image.setImageBitmap(bitmap)
        }
        watchSearchLabel?.text=(if(es.macrostudio.engine.Watch.searchingRule) "⚡ Vigila:" else "🔍 Busca:")+"\n«${es.macrostudio.engine.Watch.searchingName}»"
        column.visibility=View.VISIBLE
    }

    private fun applyWatch() {
        val on=es.macrostudio.engine.Watch.enabled && bar!=null
        watchBox?.visibility=if(on && !collapsedNow()) View.VISIBLE else View.GONE
        if(on && marksView==null) {
            val view=MarksView(this)
            val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT)
                .apply { gravity=Gravity.TOP or Gravity.LEFT; if(android.os.Build.VERSION.SDK_INT>=28) layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            if(runCatching { wm.addView(view,lp) }.isSuccess) marksView=view
        }
        if(!on) { marksView?.let { runCatching { wm.removeView(it) } }; marksView=null }
        main.removeCallbacks(watchTicker)
        if(on) main.post(watchTicker)
    }

    /** Dibuja un recuadro donde se buscó una imagen (verde = encontrada, rojo = no). */
    fun markBox(x: Int, y: Int, w: Int, h: Int, found: Boolean, percent: Int) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { markBox(x,y,w,h,found,percent) }; return }
        // El recuadro va por FUERA de la imagen: si se pintara encima, la siguiente búsqueda
        // (p. ej. «Tocar esta imagen» justo después) la vería tapada y fallaría.
        val pad=7*resources.displayMetrics.density
        marksView?.add(MarksView.Mark(rect=android.graphics.RectF(x-pad,y-pad,x+w+pad,y+h+pad),label=if(found) "✓ $percent % encontrada" else "✗ $percent % no está",color=if(found) Color.rgb(60,220,120) else Color.rgb(240,80,80),lifeMs=1800))
    }

    private fun markGesture(points: List<android.graphics.PointF>, durationMs: Long) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { markGesture(points,durationMs) }; return }
        marksView?.add(MarksView.Mark(points=points,color=Color.rgb(255,210,60),lifeMs=durationMs.coerceAtMost(3_000)+900))
    }

    private fun markLabel(text: String) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { markLabel(text) }; return }
        marksView?.add(MarksView.Mark(label=text,color=Color.rgb(109,226,209),lifeMs=1500))
    }

    /** Texto pequeño del panel: paso en curso, vuelta o estado de la grabación. */
    fun showStatus(text: String) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { showStatus(text) }; return }
        statusView?.let { it.text=text; it.visibility=if(text.isBlank()) View.GONE else View.VISIBLE }
    }

    fun refreshBar() {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { refreshBar() }; return }
        val root=bar ?: return
        val row=barRow ?: return
        row.removeAllViews()
        statusView=null
        if(collapsedNow()) {
            row.addView(dragHandle(root,collapsed=true))
            watchBox?.visibility=View.GONE
            return
        }
        row.addView(dragHandle(root))
        watchBox?.visibility=if(es.macrostudio.engine.Watch.enabled) View.VISIBLE else View.GONE
        fun button(label: String, description: String, color: Int=Color.rgb(44,56,80), enabled: Boolean=true, widthDp: Int=52, block: ()->Unit) {
            row.addView(Button(this).apply {
                text=label; isAllCaps=false; textSize=15f; minimumWidth=0; minWidth=0; minimumHeight=0; minHeight=0
                setPadding(dp(4),0,dp(4),0); contentDescription=description; isEnabled=enabled; alpha=if(enabled) 1f else .4f
                setTextColor(Color.WHITE)
                background=android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius=dp(10).toFloat() }
                setOnClickListener { if(!capturing) { barHint=""; block(); refreshBar() } }
            },LinearLayout.LayoutParams(dp(widthDp),dp(44)).apply { setMargins(dp(2),0,dp(2),0) })
        }
        when {
            barMode==BarMode.BURST -> {
                button("📷 Foto ($burstCount)","Guardar una captura",Color.rgb(20,120,110),widthDp=118) { burstShot() }
                button("✓ Listo","Terminar y recortar en la app",widthDp=84) { Studio.requestedScreen="images"; openApp() }
                addStatus(row,if(burstCount==0) "Ve a cada pantalla del juego y pulsa 📷 Foto" else "Pulsa ✓ Listo para recortar")
            }
            barMode==BarMode.CAPTURE -> {
                button("📷 Capturar","Capturar pantalla",Color.rgb(20,120,110),widthDp=118) { capture() }
                button("✕","Cancelar captura") { Studio.stepOfTarget(Studio.pendingTemplateActionId)?.let { Studio.reopenActionId=it }; Studio.pendingTemplateActionId=null; openApp() }
                addStatus(row,"Ve a la pantalla que quieras y pulsa 📷")
            }
            Studio.recording -> {
                button("■ Terminar grabación","Terminar grabación",Color.rgb(170,40,60),widthDp=170) { stopRecording(); Studio.requestedScreen="editor"; openApp() }
                addStatus(row,if(recordArmed) recordingLabel() else "⏳ Espera…")
            }
            else -> {
                val running=Studio.running
                button(if(!running || Studio.paused) "▶" else "Ⅱ",if(!running) "Reproducir ${Studio.project.name}" else if(Studio.paused) "Seguir" else "Pausa",Color.rgb(20,120,110)) {
                    if(running) Engine.pause()
                    else if(Studio.project.needsScreenCapture() && !Studio.captureReady) { Studio.panelNeedsCapture="play"; openApp() }
                    else Engine.start(Studio.project.startOnImage)
                }
                button("■","Parar",Color.rgb(150,40,55),enabled=running) { Engine.stop("Parada manual") }
                button("●","Grabar gestos",enabled=!running) { startRecording() }
                button("📷","Capturas rápidas",enabled=!running) { startBurstMode() }
                button("👁",if(es.macrostudio.engine.Watch.enabled) "Apagar modo ver" else "Modo ver: toques, esperas y siguiente paso",if(es.macrostudio.engine.Watch.enabled) Color.rgb(25,110,170) else Color.rgb(44,56,80)) { toggleWatch() }
                button("⌂","Abrir PrisonMacro") { stopPointPicker(); Engine.stop(); Studio.recordInto=null; openApp() }
                // Con la macro en marcha, ✕ la para primero (antes se escondía el panel y no había dónde parar).
                button("✕",if(running) "Parar y ocultar panel" else "Ocultar panel") { if(Studio.running) Engine.stop("Parada manual") else Engine.cancelAutoResume(); Studio.recordInto=null; hideBar() }
                // Ancho para el texto: lo que quede en la pantalla junto a los botones (máx. 460 dp).
                val room=((screenSize().first/resources.displayMetrics.density).toInt()-400).coerceIn(200,460)
                if(running) addStatus(row,Studio.status,lines=2,widthDp=room)
                else if(barHint.isNotBlank()) addStatus(row,barHint,lines=2,widthDp=room,color=Color.rgb(255,220,140))
                // Al terminar, el panel dice en qué paso se quedó y por qué (sin abrir la app).
                else if(Studio.recordInto==null && Studio.stopInfo.isNotBlank() && !es.macrostudio.engine.Watch.enabled)
                    addStatus(row,Studio.stopInfo,lines=4,widthDp=room,color=if(Studio.stopInfo.startsWith("✓")) Color.rgb(140,230,170) else Color.rgb(255,170,170))
                else Studio.recordInto?.let { (id,otherwise) -> findAction(Studio.project.actions,id)?.let { addStatus(row,"Pulsa ● para grabar dentro de «${it.name}»"+if(otherwise) " (si NO se ve)" else "") } }
            }
        }
    }

    private fun addStatus(row: LinearLayout, text: String, lines: Int=2, widthDp: Int=190, color: Int=Color.rgb(200,225,230)) {
        val view=TextView(this).apply {
            this.text=text; textSize=12f; setTextColor(color); maxLines=lines; maxWidth=dp(widthDp)
            ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(6),0,dp(4),0)
        }
        statusView=view
        row.addView(view,LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Asa del panel: arrastrar = mover; tocar (sin moverse) = plegar o desplegar. */
    private fun dragHandle(row: LinearLayout, collapsed: Boolean=false): View = TextView(this).apply {
        text="⠿"; textSize=if(collapsed) 24f else 20f; gravity=Gravity.CENTER
        setTextColor(when {
            !collapsed -> Color.rgb(160,180,200)
            Studio.running && Studio.paused -> Color.rgb(255,210,90)
            Studio.running -> Color.rgb(90,225,140)
            else -> Color.rgb(160,180,200)
        })
        contentDescription=if(collapsed) "Desplegar panel (arrastra para moverlo)" else "Mover panel (toca para plegarlo)"
        val slop=android.view.ViewConfiguration.get(this@MacroAccessibilityService).scaledTouchSlop
        var startX=0f; var startY=0f; var originX=0; var originY=0; var moved=false
        setOnTouchListener { _, e ->
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX=e.rawX; startY=e.rawY; originX=barX; originY=barY; moved=false }
                MotionEvent.ACTION_MOVE -> {
                    if(!moved && (abs(e.rawX-startX)>slop || abs(e.rawY-startY)>slop)) moved=true
                    if(moved) {
                        barX=(originX+(e.rawX-startX)).toInt().coerceAtLeast(0); barY=(originY+(e.rawY-startY)).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(row,(row.layoutParams as WindowManager.LayoutParams).apply { x=barX; y=barY }) }
                    }
                }
                MotionEvent.ACTION_UP -> if(!moved && !capturing && barMode==BarMode.CONTROL && !Studio.recording) toggleCollapsed()
            }
            true
        }
        layoutParams=if(collapsed) LinearLayout.LayoutParams(dp(44),dp(44)) else LinearLayout.LayoutParams(dp(26),dp(44))
    }

    private fun openApp() {
        hideBar()
        // CLEAR_TOP: la pantalla principal queda delante aunque hubiera otra de la app encima
        // (si no, una petición de permiso quedaba esperando detrás).
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    fun hideBar() {
        if(bar!=null) es.macrostudio.engine.KeepAlive.barShown(this,false)
        bar?.let { runCatching { wm.removeView(it) } }; bar=null; barRow=null; watchInfo=null; watchBox=null; watchImage=null; watchSearchLabel=null; watchImageFile=""; barMode=BarMode.CONTROL
        main.removeCallbacks(watchTicker)
        marksView?.let { runCatching { wm.removeView(it) } }; marksView=null
    }

    private fun capture() {
        val service=CaptureService.instance ?: run {
            // Sin permiso de captura: la app lo pide y vuelve aquí con el panel de captura.
            Studio.panelNeedsCapture="capture"; openApp(); return
        }
        if(capturing) return
        capturing=true
        hideOverlaysThen { hiddenAt -> service.saveScreenshot(hiddenAt) { success ->
            setOverlaysVisible(true); capturing=false
            if(success) {
                val target=Studio.pendingTemplateActionId
                // También queda en «📸 Capturas enteras»: así se pueden recortar más botones de
                // esta misma pantalla sin volver al juego.
                if(target==null || !target.startsWith(Studio.COLOR_PREFIX)) es.macrostudio.capture.Shots.saveLatest(this)?.let { Studio.screenshotVersion++ }
                hideBar()
                startActivity(Intent(this,CropActivity::class.java)
                    .putExtra("actionId",target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        } }
    }
    fun perform(action: Action, done: (Boolean)->Unit) = dispatchAction(action,true,done)
    private fun dispatchAction(action: Action, checkTemplateResolution: Boolean, done: (Boolean)->Unit) {
        val (w,h)=screenSize()
        val template=Studio.project.template.takeIf { Studio.project.startOnImage }
        // Los toques van en % de la pantalla: valen en cualquier tamaño. Solo se bloquean si la
        // pantalla está girada respecto a la imagen de inicio (tocaría en otro sitio).
        if(checkTemplateResolution && template!=null && (w>h)!=(template.screenWidth>template.screenHeight)) { Studio.log("La pantalla está girada respecto a la imagen de inicio; gesto bloqueado"); done(false); return }
        try {
            val path=Path(); action.points.forEachIndexed { index,p -> val x=(p.x*(w-1)).coerceIn(0f,(w-1).toFloat()); val y=(p.y*(h-1)).coerceIn(0f,(h-1).toFloat()); if(index==0) path.moveTo(x,y) else path.lineTo(x,y) }
            val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,action.durationMs.coerceIn(1,60_000))).build()
            if(es.macrostudio.engine.Watch.enabled) markGesture(action.points.map { p -> android.graphics.PointF((p.x*(w-1)).coerceIn(0f,(w-1).toFloat()),(p.y*(h-1)).coerceIn(0f,(h-1).toFloat())) },action.durationMs)
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
                // Si Android no avisa (pasa en BlueStacks), el toque ya se envió: se da por hecho.
                timeout=Runnable { Studio.log("Android no confirmó el toque; se da por hecho"); finish(true) }
                gestureHandler.postDelayed(timeout!!,action.durationMs.coerceIn(40,60_000)+1_500L)
            }
        } catch(e: Exception) { Studio.log("Gesto fallido: ${e.message}"); done(false) }
    }
    fun performSystemAction(action: Action): Boolean = try {
        if(es.macrostudio.engine.Watch.enabled) markLabel(when(action.kind) { Kind.HOME -> "🏠 Inicio"; Kind.BACK -> "◀ Atrás"; Kind.RECENTS -> "🗂 Apps recientes"; Kind.NOTIFICATIONS -> "🔔 Notificaciones"; Kind.QUICK_SETTINGS -> "🎛 Ajustes rápidos"; Kind.OPEN_APP -> "🚀 Abrir ${action.packageName.substringAfterLast('.')}"; else -> action.name })
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
    /** Otras versiones de Android (p. ej. BlueStacks en Windows) tienen un botón «Cerrar todo»
     * en Recientes y las tarjetas colocadas de otra forma. Devuelve dónde está ese botón en
     * pantalla (o null). No mira las ventanas de PrisonMacro (su panel también habla de «Cerrar todo»). */
    private fun findClearAllBounds(): android.graphics.Rect? {
        val labels=listOf("cerrar todo","cerrar todas","borrar todo","borrar todas","eliminar todo","quitar todo","limpiar todo","clear all","close all","dismiss all","remove all")
        fun matches(node: AccessibilityNodeInfo): Boolean {
            val text=(node.text?.toString().orEmpty()+" "+node.contentDescription?.toString().orEmpty()).lowercase().trim()
            return text.isNotBlank() && text.length<40 && labels.any { text.contains(it) }
        }
        fun visit(node: AccessibilityNodeInfo): android.graphics.Rect? {
            if(node.isVisibleToUser && matches(node)) {
                val r=android.graphics.Rect(); node.getBoundsInScreen(r)
                if(r.width()>4 && r.height()>4) return r
            }
            for(index in 0 until node.childCount) {
                val child=node.getChild(index) ?: continue
                val found=visit(child); child.recycle()
                if(found!=null) return found
            }
            return null
        }
        val roots=runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(runCatching { rootInActiveWindow }.getOrNull()) }
        return try { roots.filter { it.packageName?.toString()!=packageName }.firstNotNullOfOrNull { visit(it) } } finally { roots.forEach { runCatching { it.recycle() } } }
    }
    /** Guarda dónde está «Cerrar todo» (para tocar ahí las próximas veces). */
    private fun rememberClearAll(r: android.graphics.Rect) {
        val (w,h)=screenSize()
        val point=Point((r.exactCenterX()/w).coerceIn(0f,1f),(r.exactCenterY()/h).coerceIn(0f,1f))
        es.macrostudio.engine.ThisDevice.setClearAllPoint(this,point)
        Studio.log("«Cerrar todo» encontrado solo en ${(point.x*100).toInt()} %, ${(point.y*100).toInt()} %")
    }
    /** Pulsa «Cerrar todo» si se ve, y recuerda dónde estaba. */
    private fun clickClearAllInRecents(): Boolean {
        val r=findClearAllBounds() ?: return false
        rememberClearAll(r)
        val (w,h)=screenSize()
        dispatchAction(Action(kind=Kind.GESTURE,name="Cerrar todo",durationMs=80,points=listOf(Point(r.exactCenterX()/w,r.exactCenterY()/h))),false) { }
        return true
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
                    // PC Windows: se pulsa «Cerrar todo» donde lo marcó el usuario (ahí no vale deslizar).
                    if(es.macrostudio.engine.ThisDevice.usesClearAll(this)) {
                        val point=es.macrostudio.engine.ThisDevice.clearAllPoint(this)
                        if(point!=null) {
                            dispatchAction(Action(kind=Kind.GESTURE,name="Cerrar todo",durationMs=80,points=listOf(point)),false) { tapped ->
                                Studio.log(if(tapped) "Recientes: tocado «Cerrar todo»" else "Recientes: no se pudo tocar «Cerrar todo»")
                                gestureHandler.postDelayed({ finish(performGlobalAction(GLOBAL_ACTION_HOME) && tapped) },800)
                            }
                            return@postDelayed
                        }
                        if(clickClearAllInRecents()) {
                            Studio.log("Recientes: pulsado «Cerrar todo»")
                            gestureHandler.postDelayed({ finish(performGlobalAction(GLOBAL_ACTION_HOME)) },800)
                            return@postDelayed
                        }
                        Studio.log("⚠ No veo «Cerrar todo» (⚙ Ajustes › Este aparato › Buscar); se prueba a deslizar")
                    }
                    if(dismissVisibleRecentsTask()) {
                        gestureHandler.postDelayed({ finish(performGlobalAction(GLOBAL_ACTION_HOME)) },250)
                        return@postDelayed
                    }
                    if(clickClearAllInRecents()) {
                        Studio.log("Recientes: pulsado «Cerrar todo»")
                        gestureHandler.postDelayed({ finish(performGlobalAction(GLOBAL_ACTION_HOME)) },600)
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

    /**
     * PC Windows: abre Recientes y busca «Cerrar todo» él solo para guardar dónde está.
     * Si no lo encuentra y [ask], deja tocarlo (una sola vez; el toque no llega a Recientes).
     * [done] dice si quedó guardado. Recientes se queda abierto: lo cierra quien llama.
     */
    private fun locateClearAll(ask: Boolean, done: (Boolean)->Unit) {
        if(Studio.recording || recorder!=null || pointPicker!=null) { done(false); return }
        if(!performGlobalAction(GLOBAL_ACTION_RECENTS)) { Studio.fail("Android no abrió Recientes"); done(false); return }
        fun look(attempt: Int) { gestureHandler.postDelayed({ main.post {
            val found=findClearAllBounds()
            if(found!=null) {
                rememberClearAll(found)
                Toast.makeText(this,"✓ He encontrado «Cerrar todo» yo solo",Toast.LENGTH_SHORT).show()
                done(true); return@post
            }
            if(attempt<2) { look(attempt+1); return@post }
            if(!ask) { done(false); return@post }
            askClearAll(done)
        } },if(attempt==0) 1500L else 900L) }
        look(0)
    }
    private fun askClearAll(done: (Boolean)->Unit) {
        val (w,h)=screenSize()
        var finished=false
        val overlay=FrameLayout(this).apply {
            setBackgroundColor(Color.argb(40,109,226,209))
            addView(TextView(this@MacroAccessibilityService).apply {
                text="👆 Toca encima del botón «Cerrar todo»\n(solo esta vez: no se cerrará nada, solo se guarda dónde está)"; textSize=15f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); setPadding(dp(14),dp(8),dp(14),dp(8))
                background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(210,23,31,48)); cornerRadius=dp(12).toFloat() }
            },FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin=dp(40) })
        }
        overlay.setOnTouchListener { _, event ->
            if(event.actionMasked==MotionEvent.ACTION_UP && !finished) {
                finished=true
                val point=normalizedScreenPoint(event.rawX,event.rawY,w,h)
                es.macrostudio.engine.ThisDevice.setClearAllPoint(this,point)
                stopPointPicker()
                val percent="${(point.x*100).toInt()} %, ${(point.y*100).toInt()} %"
                Studio.log("«Cerrar todo» marcado en $percent")
                Toast.makeText(this,"✓ Guardado dónde está «Cerrar todo» ($percent)",Toast.LENGTH_SHORT).show()
                done(true)
            }
            true
        }
        pointPicker=overlay
        try { wm.addView(overlay,params(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)) }
        catch(e: Exception) { pointPicker=null; Studio.fail("No se pudo marcar: ${e.message}"); done(false); return }
        // Si nadie lo toca en 1 minuto, se deja como estaba.
        main.postDelayed({ if(!finished && pointPicker===overlay) { finished=true; stopPointPicker(); Studio.log("Nadie tocó «Cerrar todo»: queda sin marcar"); done(false) } },60_000)
    }

    /** Botón «📍 Buscar «Cerrar todo»» de la app: lo busca (o deja tocarlo) y vuelve a Ajustes. */
    fun startClearAllPicker() {
        if(Studio.running) { Studio.fail("Detén la macro antes"); return }
        locateClearAll(ask=true) { _ ->
            // Sin pasar por Inicio: Android no deja abrir una app justo después.
            Studio.requestedScreen="settings"
            startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** Al pulsar ▶ en el PC sin saber aún dónde está «Cerrar todo»: lo busca, cierra Recientes
     * (vuelve al juego) y avisa. Así la persona no tiene que hacer nada (o solo un toque). */
    fun findClearAllBeforeStart(done: (Boolean)->Unit) {
        locateClearAll(ask=true) { found ->
            performGlobalAction(GLOBAL_ACTION_BACK)
            main.postDelayed({ done(found) },1200)
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
        // ¿Dentro de un bloque? (p. ej. «⚡ Siempre que se vea»). Si el bloque ya no existe, al final.
        val into=Studio.recordInto?.takeIf { findAction(Studio.project.actions,it.first)!=null }
        recordIntoName=into?.let { findAction(Studio.project.actions,it.first)?.name }
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
                                    // Dentro de un bloque no se guarda la espera antes del primer toque (es lo
                                    // que tardaste en ir al juego); entre toques, sí.
                                    val wait=if(gap>=200 && !(into!=null && recordedCount==0)) listOf(Action(kind=Kind.WAIT,name="Espera grabada",durationMs=gap.coerceAtMost(600_000))) else emptyList()
                                    val current=Studio.project.actions
                                    val updated=if(into!=null && findAction(current,into.first)!=null) (wait+action).fold(current) { list, step -> addInside(list,into.first,into.second,step) } else current+wait+action
                                    if(Studio.update(Studio.project.copy(actions=updated))) { recordedCount++; Studio.log("Gesto grabado (${duration} ms)${recordIntoName?.let { " dentro de «$it»" } ?: ""}") } else stopRecording()
                                } else Studio.fail("No se grabó el gesto: Android lo rechazó")
                                lastEnd=SystemClock.elapsedRealtime()
                                if(generation==recordGeneration && recorder===overlay && Studio.recording) {
                                    if(!setRecordingTouchable(true)) { stopRecording(); Studio.fail("No se pudo reanudar la grabación") }
                                    else { recordArmed=true; overlay.setBackgroundColor(Color.argb(28,255,70,100)); showStatus(recordingLabel()) }
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
        if(Studio.recording) {
            val where=recordIntoName?.let { " dentro de «$it»" } ?: ""
            Studio.log("Grabación guardada: $recordedCount gesto(s)$where"); Studio.status="Grabación guardada: $recordedCount gesto(s)$where"
            Studio.recordInto=null; recordIntoName=null
        }
        Studio.recording=false; refreshBar()
    }
    /** Nombre del bloque donde se está grabando (null = al final de la macro). */
    private var recordIntoName: String?=null
    private fun recordingLabel()="● Grabando · $recordedCount"+(recordIntoName?.let { "\ndentro de «$it»" } ?: "")
}
