package es.macrostudio.editor

import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import android.os.Bundle
import android.content.Intent
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.*
import es.macrostudio.MainActivity
import es.macrostudio.core.*
import java.io.File
import java.util.UUID
import kotlin.math.*

/** Qué se está haciendo con el dedo en la pantalla de recorte. */
private enum class Drag { NONE, NEW, RESIZE, MOVE, PAN, MARK, COLOR }

class CropActivity: ComponentActivity() {
    private var bitmap: Bitmap?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Studio.init(this)
        // Android puede reabrir esta pantalla sola (al volver a abrir la app tras cerrarla o
        // actualizarla) sin decir qué recortar: entonces se va a la pantalla principal.
        if(intent.extras==null && intent.data==null) {
            startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish(); return
        }
        val editId=intent.getStringExtra("templateId")
        val existing=editId?.let { id -> Studio.project.templates.firstOrNull { it.id==id } }
        val shot=intent.getStringExtra("shotFile")?.takeIf { es.macrostudio.capture.Shots.isSafeRelative(it) }
        val sourceFile=existing?.template?.file ?: shot ?: "capture.png"
        val b=if(intent.data!=null && existing==null) runCatching {
            contentResolver.openInputStream(intent.data!!)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() else BitmapFactory.decodeFile(File(filesDir,sourceFile).path)
        if(b!=null && (b.width>8192 || b.height>8192 || b.width.toLong()*b.height>20_000_000)) {
            b.recycle(); Studio.fail("Imagen demasiado grande (máximo 8192 px y 20 MP)"); finish(); return
        }
        if(b==null) { Studio.fail(if(existing==null) "No se pudo abrir la captura reciente" else "No se pudo abrir la imagen elegida"); finish(); return }; bitmap=b
        // Recorta con la misma orientación que la captura: una captura horizontal de un
        // juego se veía diminuta en vertical y costaba marcar el botón.
        requestedOrientation=if(b.width>b.height) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        val target=intent.getStringExtra("actionId")
        // Modo color: el usuario toca un píxel de la captura y se guarda su color y posición.
        val colorFor=target?.takeIf { it.startsWith(Studio.COLOR_PREFIX) }?.removePrefix(Studio.COLOR_PREFIX)
        // Modo punto: tocar en la captura dónde tiene que tocar (1) o deslizar (2) el paso.
        val pointFor=target?.takeIf { it.startsWith(Studio.POINT_PREFIX) }?.removePrefix(Studio.POINT_PREFIX)
        val swipeFor=target?.takeIf { it.startsWith(Studio.SWIPE_PREFIX) }?.removePrefix(Studio.SWIPE_PREFIX)
        val markFor=pointFor ?: swipeFor
        val actionId=colorFor ?: markFor ?: target
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(14,19,32)); setPadding(12,12,12,12) }
        root.setOnApplyWindowInsetsListener { view,insets ->
            @Suppress("DEPRECATION")
            view.setPadding(12+insets.systemWindowInsetLeft,12+insets.systemWindowInsetTop,12+insets.systemWindowInsetRight,12+insets.systemWindowInsetBottom)
            insets
        }
        // «Imagen 3», «Imagen 4»… (sin repetir). Antes cogía el nombre del paso y el paso
        // acababa llamándose «Tocar «Tocar «X» · imagen»».
        val used=Studio.project.templates.map { it.name }.toSet()
        val nextName=generateSequence(Studio.project.templates.size+1) { it+1 }.map { "Imagen $it" }.first { it !in used }
        // Si el paso tiene un nombre puesto a mano («⚡ Cerrar ventanas», «3. Pulsar jugar»), la imagen se llama igual
        // (sin el número ni el ⚡). Con un nombre automático se queda «Imagen N», para no repetir «Tocar «Tocar…»».
        val fromStep=actionId?.let { findAction(Studio.project.actions,it) }?.takeIf { !es.macrostudio.ui.hasAutoName(it) }?.name
            ?.replace(Regex("^\\d+\\.\\s*"),"")?.removePrefix("⚡")?.trim()?.take(60)?.takeIf { it.isNotBlank() && it !in used }
        val defaultName=existing?.name ?: if(actionId==Studio.START_IMAGE_TARGET) "Imagen de inicio" else fromStep ?: nextName
        val nameField=EditText(this).apply {
            setText(defaultName); setSelectAllOnFocus(true); setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint="Nombre de la imagen"
            isEnabled=existing==null; textSize=15f
        }
        val crop=CropView(b)
        fun goBack() {
            if(target==Studio.pendingTemplateActionId) Studio.pendingTemplateActionId=null
            if(actionId!=null && actionId!=Studio.START_IMAGE_TARGET) Studio.reopenActionId=actionId
            Studio.requestedScreen=if(actionId==null) "images" else "editor"
            startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        fun saveColor() {
            val pixel=crop.tapped() ?: run { Toast.makeText(this,"Toca el punto cuyo color quieres usar",Toast.LENGTH_LONG).show(); return }
            val color=b.getPixel(pixel.x,pixel.y) or 0xFF000000.toInt()
            val point=es.macrostudio.core.Point(pixel.x/(b.width-1f).coerceAtLeast(1f),pixel.y/(b.height-1f).coerceAtLeast(1f))
            val updated=updateAction(Studio.project.actions,colorFor!!) { it.copy(targetColor=color,colorX=point.x.coerceIn(0f,1f),colorY=point.y.coerceIn(0f,1f)) }
            if(Studio.update(Studio.project.copy(actions=updated))) { Studio.log("Color elegido: ${rgbHex(color)}"); goBack() }
        }
        fun savePoints() {
            val needed=if(swipeFor!=null) 2 else 1
            val marks=crop.markedPixels()
            if(marks.size<needed) { Toast.makeText(this,if(needed==2) "Toca dónde EMPIEZA y luego dónde TERMINA el deslizamiento" else "Toca el sitio donde tiene que tocar",Toast.LENGTH_LONG).show(); return }
            val pts=marks.take(needed).map { es.macrostudio.core.Point((it.x/(b.width-1f).coerceAtLeast(1f)).coerceIn(0f,1f),(it.y/(b.height-1f).coerceAtLeast(1f)).coerceIn(0f,1f)) }
            val updated=updateAction(Studio.project.actions,markFor!!) { a ->
                if(a.kind==Kind.CLICK_IMAGE) a.copy(clickTarget=ClickTarget.FIXED,clickOffsetX=pts[0].x*100,clickOffsetY=pts[0].y*100)
                else a.copy(points=pts,durationMs=if(needed==2) a.durationMs.coerceAtLeast(250) else a.durationMs.coerceAtMost(60_000))
            }
            if(Studio.update(Studio.project.copy(actions=updated))) { Studio.log("Punto elegido en una captura: ${pts.joinToString { "${(it.x*100).roundToInt()} %, ${(it.y*100).roundToInt()} %" }}"); goBack() }
        }
        fun save() {
            if(markFor!=null) { savePoints(); return }
            if(colorFor!=null) { saveColor(); return }
            val r=crop.selected()
            if(r==null || r.width()<8 || r.height()<8) { Toast.makeText(this,"Arrastra el dedo para marcar un rectángulo (mínimo 8×8 píxeles)",Toast.LENGTH_LONG).show(); return }
            runCatching {
                val filename="template-${UUID.randomUUID()}.png"
                val piece=Bitmap.createBitmap(b,r.left,r.top,r.width(),r.height())
                try {
                    val pixels=IntArray(piece.width*piece.height); piece.getPixels(pixels,0,piece.width,0,0,piece.width,piece.height)
                    require(es.macrostudio.vision.Matcher.isDistinctive(es.macrostudio.vision.Pixels(piece.width,piece.height,pixels))) { "El recorte es de un solo color: incluye texto, bordes o detalles" }
                    File(filesDir,filename).outputStream().use { check(piece.compress(Bitmap.CompressFormat.PNG,100,it)) }
                } finally { if(piece!==b) piece.recycle() }
                val template=if(existing==null) Template(filename,r.left,r.top,r.width(),r.height(),b.width,b.height)
                    else recroppedTemplate(existing.template,filename,r)
                val project=Studio.project
                val name=nameField.text.toString().trim().take(100).ifBlank { defaultName }
                val updated=if(existing==null) addCapturedTemplate(project,SavedTemplate(name=name,template=template,folder=Studio.shotFolder),actionId)
                else project.copy(
                    template=if(project.template?.file==existing.template.file) template else project.template,
                    templates=project.templates.map { if(it.id==existing.id) it.copy(template=template) else it }
                )
                check(Studio.update(updated)) { "No se pudo guardar el proyecto" }
                if(existing!=null) es.macrostudio.projects.Backup.deleteOrphanImages(this,Studio.projects)
                Studio.status="Imagen guardada: $name"
                Studio.log("Imagen guardada: $name · ${r.width()}×${r.height()} en (${r.left}, ${r.top})")
                goBack()
            }.onFailure { Studio.fail("No se pudo guardar el recorte: ${it.message}"); Toast.makeText(this,it.message,Toast.LENGTH_LONG).show() }
        }
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
        bar.addView(Button(this).apply { text="✕ Cancelar"; isAllCaps=false; setOnClickListener { goBack() } })
        val colorInfo=TextView(this).apply { setTextColor(Color.WHITE); textSize=15f; gravity=android.view.Gravity.CENTER; text="Toca un punto" }
        if(markFor!=null) {
            colorInfo.text=if(swipeFor!=null) "Toca dónde EMPIEZA" else "Toca el sitio"
            bar.addView(colorInfo,LinearLayout.LayoutParams(0,-2,1f)); crop.maxMarks=if(swipeFor!=null) 2 else 1
            crop.onMark={ n -> colorInfo.text=when { swipeFor==null -> "✓ Punto marcado"; n==1 -> "Ahora toca dónde TERMINA"; else -> "✓ Deslizamiento marcado" } }
        }
        else if(colorFor!=null) { bar.addView(colorInfo,LinearLayout.LayoutParams(0,-2,1f)); crop.colorMode=true; crop.onTap={ c -> colorInfo.text="${rgbHex(c)}  ■"; colorInfo.setTextColor(c or 0xFF000000.toInt()) } }
        else bar.addView(nameField,LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(Button(this).apply { text="✓ Guardar"; isAllCaps=false; setOnClickListener { save() } })
        root.addView(bar)
        // Fila de ayuda con los botones de zoom (en el PC también vale la rueda del ratón).
        val helpRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
        val zoomLabel=TextView(this).apply { setTextColor(Color.rgb(109,226,209)); textSize=13f; setPadding(8,0,8,0); text="" }
        crop.onZoom={ z -> zoomLabel.text=if(z>1.05f) "×${"%.1f".format(java.util.Locale.ROOT,z).removeSuffix(".0")}" else "" }
        fun zoomButton(label: String, desc: String, action: ()->Unit)=Button(this).apply { text=label; contentDescription=desc; isAllCaps=false; minWidth=0; minimumWidth=0; setPadding(28,0,28,0); setOnClickListener { action() } }
        helpRow.addView(TextView(this).apply {
            text=(if(markFor!=null) (if(swipeFor!=null) "Toca dónde empieza y dónde acaba el deslizamiento" else "Toca dónde tiene que tocar")
            else if(colorFor!=null) "Toca el color que hay que buscar" else if(existing==null) "Arrastra el dedo alrededor del botón" else "Marca una zona más pequeña")+" y pulsa ✓ Guardar. Con ＋ amplías."
            setTextColor(Color.rgb(180,200,210)); textSize=14f; setPadding(4,6,4,6)
        },LinearLayout.LayoutParams(0,-2,1f))
        helpRow.addView(zoomLabel)
        helpRow.addView(zoomButton("－","Reducir") { crop.zoomButton(1/1.6f) })
        helpRow.addView(zoomButton("＋","Ampliar") { crop.zoomButton(1.6f) })
        helpRow.addView(zoomButton("Ver toda","Ver la imagen entera") { crop.fit() })
        root.addView(helpRow)
        root.addView(crop,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) { goBack() }
    }
    override fun onStart() { super.onStart(); es.macrostudio.engine.KeepAlive.appScreen(true) }
    override fun onStop() { super.onStop(); es.macrostudio.engine.KeepAlive.appScreen(false) }
    override fun onDestroy() { bitmap?.recycle(); bitmap=null; super.onDestroy() }
    /**
     * La captura con zoom: pellizcar con dos dedos, la rueda del ratón (BlueStacks) o los
     * botones ＋/－ amplían; con dos dedos (o uno en modo punto) se mueve la imagen.
     * El recuadro se dibuja arrastrando y luego se puede ajustar tirando de sus bordes o
     * esquinas, o moverlo arrastrando desde dentro. Todo se guarda en píxeles de la imagen.
     */
    inner class CropView(private val image: Bitmap): View(this@CropActivity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val density=resources.displayMetrics.density
        // Dónde está la imagen dentro de la vista: esquina (offX, offY) y px de pantalla por px de imagen.
        private var scale=0f; private var fitScale=0f; private var offX=0f; private var offY=0f
        private val maxScale get()=maxOf(fitScale*2f,16f)
        // Selección y marcas, en píxeles de la imagen.
        private var selA: PointF?=null; private var selB: PointF?=null
        private var colorPoint: PointF?=null
        private val marks=mutableListOf<PointF>()
        var colorMode=false
        var onTap: ((Int)->Unit)?=null
        /** Modo marcar puntos (1 = tocar, 2 = deslizar). 0 = recortar. */
        var maxMarks=0
        var onMark: ((Int)->Unit)?=null
        /** Avisa cuando cambia el zoom (para enseñar «×3»). */
        var onZoom: ((Float)->Unit)?=null

        private var drag=Drag.NONE
        private var edges=0 // 1 izquierda, 2 arriba, 4 derecha, 8 abajo
        private var downX=0f; private var downY=0f; private var lastX=0f; private var lastY=0f
        private var moved=false
        private var savedA: PointF?=null; private var savedB: PointF?=null
        private var focus: PointF?=null
        private val slop get()=10*density
        private val scaler=android.view.ScaleGestureDetector(context,object: android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor,d.focusX,d.focusY); return true }
        })

        val zoom: Float get()=if(fitScale>0) scale/fitScale else 1f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); fit() }

        /** Imagen entera en la vista. */
        fun fit() {
            if(width<=0 || height<=0) return
            fitScale=min(width.toFloat()/image.width,height.toFloat()/image.height); scale=fitScale
            offX=(width-image.width*scale)/2; offY=(height-image.height*scale)/2
            onZoom?.invoke(1f); invalidate()
        }
        /** Amplía [f] veces alrededor del punto (fx, fy) de la vista. */
        fun zoomBy(f: Float, fx: Float=width/2f, fy: Float=height/2f) {
            if(scale<=0f) return
            val target=(scale*f).coerceIn(fitScale,maxScale)
            val k=target/scale
            offX=fx-(fx-offX)*k; offY=fy-(fy-offY)*k; scale=target
            clampPan(); onZoom?.invoke(zoom); invalidate()
        }
        /** Botones ＋/－: amplían hacia el recuadro (o el punto) si hay uno. */
        fun zoomButton(f: Float) {
            val a=selA; val b=selB
            val center=when {
                a!=null && b!=null -> PointF((a.x+b.x)/2,(a.y+b.y)/2)
                colorPoint!=null -> colorPoint
                marks.isNotEmpty() -> marks.last()
                else -> null
            }
            if(center==null) { zoomBy(f); return }
            val vx=viewX(center.x); val vy=viewY(center.y)
            zoomBy(f,vx,vy)
            // Deja lo marcado en el centro de la vista.
            if(f>1f) { offX+=width/2f-viewX(center.x); offY+=height/2f-viewY(center.y); clampPan(); invalidate() }
        }
        private fun clampPan() {
            val iw=image.width*scale; val ih=image.height*scale
            offX=if(iw<=width) (width-iw)/2 else offX.coerceIn(width-iw,0f)
            offY=if(ih<=height) (height-ih)/2 else offY.coerceIn(height-ih,0f)
        }
        private fun viewX(ix: Float)=offX+ix*scale
        private fun viewY(iy: Float)=offY+iy*scale
        private fun toImage(x: Float, y: Float)=PointF(((x-offX)/scale).coerceIn(0f,image.width.toFloat()),((y-offY)/scale).coerceIn(0f,image.height.toFloat()))
        private fun onImage(x: Float, y: Float)=x>=offX && y>=offY && x<=offX+image.width*scale && y<=offY+image.height*scale
        /** Recuadro en coordenadas de la vista. */
        private fun selectionView(): RectF? {
            val a=selA ?: return null; val b=selB ?: return null
            return RectF(viewX(min(a.x,b.x)),viewY(min(a.y,b.y)),viewX(max(a.x,b.x)),viewY(max(a.y,b.y)))
        }
        private fun hitEdges(x: Float, y: Float): Int {
            val r=selectionView() ?: return 0
            val grab=22*density
            if(x<r.left-grab || x>r.right+grab || y<r.top-grab || y>r.bottom+grab) return 0
            var e=0
            val dl=abs(x-r.left); val dr=abs(x-r.right); val dt=abs(y-r.top); val db=abs(y-r.bottom)
            if(min(dl,dr)<grab) e=e or (if(dl<=dr) 1 else 4)
            if(min(dt,db)<grab) e=e or (if(dt<=db) 2 else 8)
            return e
        }
        private fun resizeTo(x: Float, y: Float) {
            val a=selA ?: return; val b=selB ?: return; val p=toImage(x,y)
            var l=min(a.x,b.x); var t=min(a.y,b.y); var r=max(a.x,b.x); var bt=max(a.y,b.y)
            if(edges and 1!=0) l=p.x; if(edges and 4!=0) r=p.x
            if(edges and 2!=0) t=p.y; if(edges and 8!=0) bt=p.y
            // Si un borde pasa al otro lado, se intercambian.
            if(l>r) { val s=l; l=r; r=s; edges=edges xor 5 }
            if(t>bt) { val s=t; t=bt; bt=s; edges=edges xor 10 }
            selA=PointF(l,t); selB=PointF(r,bt)
        }
        private fun moveBy(dx: Float, dy: Float) {
            val a=selA ?: return; val b=selB ?: return
            val l=min(a.x,b.x); val t=min(a.y,b.y); val w=abs(a.x-b.x); val h=abs(a.y-b.y)
            val nl=(l+dx/scale).coerceIn(0f,image.width-w); val nt=(t+dy/scale).coerceIn(0f,image.height-h)
            selA=PointF(nl,nt); selB=PointF(nl+w,nt+h)
        }
        private fun pickColorAt(x: Float, y: Float) {
            colorPoint=toImage(x,y)
            tapped()?.let { px -> onTap?.invoke(image.getPixel(px.x,px.y)) }
        }

        override fun onGenericMotionEvent(e: MotionEvent): Boolean {
            // Rueda del ratón (BlueStacks en el PC): amplía o reduce donde está el puntero.
            if(e.actionMasked==MotionEvent.ACTION_SCROLL) {
                val v=e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if(v!=0f) { zoomBy(if(v>0) 1.25f else 0.8f,e.x,e.y); return true }
            }
            return super.onGenericMotionEvent(e)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if(scale<=0f) return false
            scaler.onTouchEvent(e)
            if(e.pointerCount>1 || drag==Drag.PAN) {
                // Dos dedos: ampliar y mover. Lo que se estaba dibujando con un dedo se deshace.
                if(drag==Drag.NEW || drag==Drag.RESIZE || drag==Drag.MOVE) { selA=savedA; selB=savedB }
                if(e.pointerCount>1) {
                    var fx=0f; var fy=0f
                    for(i in 0 until e.pointerCount) { fx+=e.getX(i); fy+=e.getY(i) }
                    fx/=e.pointerCount; fy/=e.pointerCount
                    val f=focus
                    if(e.actionMasked==MotionEvent.ACTION_MOVE && f!=null) { offX+=fx-f.x; offY+=fy-f.y; clampPan() }
                    focus=if(e.actionMasked==MotionEvent.ACTION_POINTER_UP) null else PointF(fx,fy)
                }
                drag=Drag.PAN
                if(e.actionMasked==MotionEvent.ACTION_UP || e.actionMasked==MotionEvent.ACTION_CANCEL) { drag=Drag.NONE; focus=null }
                invalidate(); return true
            }
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX=e.x; downY=e.y; lastX=e.x; lastY=e.y; moved=false; focus=null
                    savedA=selA?.let { PointF(it.x,it.y) }; savedB=selB?.let { PointF(it.x,it.y) }
                    drag=when {
                        maxMarks>0 -> Drag.MARK
                        colorMode -> { pickColorAt(e.x,e.y); Drag.COLOR }
                        else -> {
                            edges=hitEdges(e.x,e.y)
                            val r=selectionView()
                            when {
                                edges!=0 -> Drag.RESIZE
                                r!=null && r.contains(e.x,e.y) -> Drag.MOVE
                                else -> { val p=toImage(e.x,e.y); selA=p; selB=PointF(p.x,p.y); Drag.NEW }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if(abs(e.x-downX)>slop || abs(e.y-downY)>slop) moved=true
                    when(drag) {
                        Drag.NEW -> selB=toImage(e.x,e.y)
                        Drag.RESIZE -> resizeTo(e.x,e.y)
                        Drag.MOVE -> moveBy(e.x-lastX,e.y-lastY)
                        Drag.MARK -> if(moved) { offX+=e.x-lastX; offY+=e.y-lastY; clampPan() }
                        Drag.COLOR -> pickColorAt(e.x,e.y)
                        else -> Unit
                    }
                    lastX=e.x; lastY=e.y
                }
                MotionEvent.ACTION_UP -> {
                    // El punto donde se suelta también cuenta (con ratón a veces no llega un último «mover»).
                    if(abs(e.x-downX)>slop || abs(e.y-downY)>slop) moved=true
                    when(drag) {
                        Drag.NEW -> if(moved) selB=toImage(e.x,e.y)
                        Drag.RESIZE -> resizeTo(e.x,e.y)
                        Drag.MOVE -> moveBy(e.x-lastX,e.y-lastY)
                        Drag.COLOR -> pickColorAt(e.x,e.y)
                        else -> Unit
                    }
                    when(drag) {
                        Drag.MARK -> if(!moved && onImage(e.x,e.y)) {
                            if(marks.size>=maxMarks) marks.clear()
                            marks+=toImage(e.x,e.y); onMark?.invoke(marks.size)
                        }
                        // Un toque suelto (sin arrastrar) no borra el recuadro que ya había.
                        Drag.NEW -> if(!moved) { selA=savedA; selB=savedB }
                        else -> Unit
                    }
                    drag=Drag.NONE; performClick()
                }
                MotionEvent.ACTION_CANCEL -> { if(drag==Drag.NEW || drag==Drag.RESIZE || drag==Drag.MOVE) { selA=savedA; selB=savedB }; drag=Drag.NONE }
            }
            invalidate(); return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }

        override fun onDraw(canvas: Canvas) {
            if(scale<=0f) fit()
            if(scale<=0f) return
            // Solo se dibuja el trozo de la imagen que se ve (con mucho zoom la imagen sería enorme).
            val src=Rect(
                floor(max(0f,-offX/scale)).toInt(), floor(max(0f,-offY/scale)).toInt(),
                ceil(min(image.width.toFloat(),(width-offX)/scale)).toInt(), ceil(min(image.height.toFloat(),(height-offY)/scale)).toInt()
            )
            if(src.width()<=0 || src.height()<=0) return
            val dst=RectF(viewX(src.left.toFloat()),viewY(src.top.toFloat()),viewX(src.right.toFloat()),viewY(src.bottom.toFloat()))
            paint.style=Paint.Style.FILL; paint.isFilterBitmap=scale<4f
            canvas.drawBitmap(image,src,dst,paint)
            val shown=RectF(max(0f,dst.left),max(0f,dst.top),min(width.toFloat(),dst.right),min(height.toFloat(),dst.bottom))
            if(maxMarks>0) {
                val pts=marks.map { PointF(viewX(it.x),viewY(it.y)) }
                if(pts.size==2) { paint.style=Paint.Style.STROKE; paint.color=Color.CYAN; paint.strokeWidth=5f; canvas.drawLine(pts[0].x,pts[0].y,pts[1].x,pts[1].y,paint) }
                pts.forEachIndexed { i, m ->
                    paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; paint.color=Color.WHITE; canvas.drawCircle(m.x,m.y,26f,paint)
                    paint.style=Paint.Style.FILL; paint.color=Color.CYAN; canvas.drawCircle(m.x,m.y,10f,paint)
                    if(maxMarks==2) { paint.color=Color.WHITE; paint.textSize=34f; canvas.drawText("${i+1}",m.x+30,m.y-30,paint) }
                }
                paint.style=Paint.Style.FILL
                return
            }
            if(colorMode) {
                colorPoint?.let { c ->
                    val bx=viewX(c.x); val by=viewY(c.y)
                    paint.style=Paint.Style.STROKE; paint.strokeWidth=3f; paint.color=Color.WHITE
                    canvas.drawCircle(bx,by,22f,paint); canvas.drawLine(bx-34,by,bx-10,by,paint); canvas.drawLine(bx+10,by,bx+34,by,paint)
                    canvas.drawLine(bx,by-34,bx,by-10,paint); canvas.drawLine(bx,by+10,bx,by+34,paint)
                    paint.color=Color.BLACK; paint.strokeWidth=1.5f; canvas.drawCircle(bx,by,24f,paint); paint.style=Paint.Style.FILL
                }
                return
            }
            val r=selectionView() ?: return
            val l=r.left; val t=r.top; val rt=r.right; val bt=r.bottom
            paint.style=Paint.Style.FILL; paint.color=Color.argb(140,0,0,0)
            canvas.drawRect(shown.left,shown.top,shown.right,max(shown.top,t),paint)
            canvas.drawRect(shown.left,min(shown.bottom,bt),shown.right,shown.bottom,paint)
            canvas.drawRect(shown.left,max(shown.top,t),max(shown.left,l),min(shown.bottom,bt),paint)
            canvas.drawRect(min(shown.right,rt),max(shown.top,t),shown.right,min(shown.bottom,bt),paint)
            paint.color=Color.CYAN; paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; canvas.drawRect(l,t,rt,bt,paint)
            // Asas en las esquinas: se ve que se puede tirar de ellas.
            paint.style=Paint.Style.FILL
            val hs=7*density
            for(cx in listOf(l,rt)) for(cy in listOf(t,bt)) canvas.drawRect(cx-hs,cy-hs,cx+hs,cy+hs,paint)
            // Tamaño del recorte en píxeles de la captura.
            selected()?.let { s ->
                val label="${s.width()} × ${s.height()} px"
                paint.textSize=14*density
                val tw=paint.measureText(label)+12*density
                val ly=if(t-30*density>0) t-10*density else bt+26*density
                val lx=l.coerceIn(0f,max(0f,width-tw))
                paint.color=Color.argb(200,15,20,32); canvas.drawRoundRect(lx,ly-18*density,lx+tw,ly+6*density,6*density,6*density,paint)
                paint.color=if(s.width()>=8 && s.height()>=8) Color.CYAN else Color.rgb(255,170,60)
                canvas.drawText(label,lx+6*density,ly,paint)
            }
            paint.color=Color.WHITE
        }
        /** Píxel de la imagen elegido (modo color). */
        fun tapped(): android.graphics.Point? {
            val p=colorPoint ?: return null
            return android.graphics.Point(p.x.toInt().coerceIn(0,image.width-1),p.y.toInt().coerceIn(0,image.height-1))
        }
        fun markedPixels(): List<android.graphics.Point> = marks.map { p -> android.graphics.Point(p.x.toInt().coerceIn(0,image.width-1),p.y.toInt().coerceIn(0,image.height-1)) }
        fun selected(): Rect? {
            val a=selA ?: return null; val b=selB ?: return null
            fun x(v: Float)=v.roundToInt().coerceIn(0,image.width)
            fun y(v: Float)=v.roundToInt().coerceIn(0,image.height)
            return Rect(x(min(a.x,b.x)),y(min(a.y,b.y)),x(max(a.x,b.x)),y(max(a.y,b.y)))
        }
    }
}

internal fun recroppedTemplate(existing: Template, filename: String, crop: Rect): Template = existing.copy(
    file=filename,
    x=existing.x+crop.left,
    y=existing.y+crop.top,
    width=crop.width(),
    height=crop.height()
)

/**
 * Añade una captura a la biblioteca y la asigna solo a quien la pidió: un paso
 * concreto o, con [Studio.START_IMAGE_TARGET], la imagen de inicio de la macro.
 * Una captura hecha sin destino solo se guarda en la biblioteca.
 */
internal fun addCapturedTemplate(project: Project, saved: SavedTemplate, actionId: String?): Project = when (actionId) {
    null -> project.copy(templates=project.templates+saved)
    Studio.START_IMAGE_TARGET -> project.copy(template=saved.template,startOnImage=true,templates=project.templates+saved)
    // El paso cambia también de nombre si lo tenía automático (si no, decía «Tocar «Imagen 1»» y tocaba la 2).
    else -> project.copy(templates=project.templates+saved,actions=updateAction(project.actions,actionId) { es.macrostudio.ui.withImage(it,saved.id,saved.name) })
}
