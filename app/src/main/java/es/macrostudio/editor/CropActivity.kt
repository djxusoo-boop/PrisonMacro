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

class CropActivity: ComponentActivity() {
    private var bitmap: Bitmap?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); Studio.init(this)
        val editId=intent.getStringExtra("templateId")
        val existing=editId?.let { id -> Studio.project.templates.firstOrNull { it.id==id } }
        val sourceFile=existing?.template?.file ?: "capture.png"
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
        val actionId=colorFor ?: target
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(14,19,32)); setPadding(12,12,12,12) }
        root.setOnApplyWindowInsetsListener { view,insets ->
            @Suppress("DEPRECATION")
            view.setPadding(12+insets.systemWindowInsetLeft,12+insets.systemWindowInsetTop,12+insets.systemWindowInsetRight,12+insets.systemWindowInsetBottom)
            insets
        }
        val defaultName=existing?.name ?: when(actionId) {
            null -> "Imagen ${Studio.project.templates.size+1}"
            Studio.START_IMAGE_TARGET -> "Imagen de inicio"
            else -> findAction(Studio.project.actions,actionId)?.name?.take(60)?.let { "$it · imagen" } ?: "Imagen ${Studio.project.templates.size+1}"
        }
        val nameField=EditText(this).apply {
            setText(defaultName); setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint="Nombre de la imagen"
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
        fun save() {
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
                val updated=if(existing==null) addCapturedTemplate(project,SavedTemplate(name=name,template=template),actionId)
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
        if(colorFor!=null) { bar.addView(colorInfo,LinearLayout.LayoutParams(0,-2,1f)); crop.colorMode=true; crop.onTap={ c -> colorInfo.text="${rgbHex(c)}  ■"; colorInfo.setTextColor(c or 0xFF000000.toInt()) } }
        else bar.addView(nameField,LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(Button(this).apply { text="✓ Guardar"; isAllCaps=false; setOnClickListener { save() } })
        root.addView(bar)
        root.addView(TextView(this).apply {
            text=if(colorFor!=null) "Toca el punto exacto cuyo color quieres detectar (puedes repetirlo). Se guarda el color y su posición." else if(existing==null) "Arrastra el dedo sobre el botón o elemento que quieres reconocer. Incluye bordes o texto; evita fondos lisos." else "Marca una zona más pequeña dentro de «${existing.name}»."
            setTextColor(Color.rgb(180,200,210)); textSize=13f; setPadding(4,6,4,6)
        })
        root.addView(crop,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) { goBack() }
    }
    override fun onDestroy() { bitmap?.recycle(); bitmap=null; super.onDestroy() }
    inner class CropView(private val image: Bitmap): View(this@CropActivity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG); private val destination=RectF()
        private var start: PointF?=null; private var end: PointF?=null
        var colorMode=false
        var onTap: ((Int)->Unit)?=null
        override fun onDraw(canvas: Canvas) {
            val scale=min(width.toFloat()/image.width,height.toFloat()/image.height)
            val iw=image.width*scale; val ih=image.height*scale
            destination.set((width-iw)/2,(height-ih)/2,(width+iw)/2,(height+ih)/2)
            paint.style=Paint.Style.FILL; canvas.drawBitmap(image,null,destination,paint)
            val a=start; val b=end
            if(colorMode) {
                if(b!=null) {
                    paint.style=Paint.Style.STROKE; paint.strokeWidth=3f; paint.color=Color.WHITE
                    canvas.drawCircle(b.x,b.y,22f,paint); canvas.drawLine(b.x-34,b.y,b.x-10,b.y,paint); canvas.drawLine(b.x+10,b.y,b.x+34,b.y,paint)
                    canvas.drawLine(b.x,b.y-34,b.x,b.y-10,paint); canvas.drawLine(b.x,b.y+10,b.x,b.y+34,paint)
                    paint.color=Color.BLACK; paint.strokeWidth=1.5f; canvas.drawCircle(b.x,b.y,24f,paint); paint.style=Paint.Style.FILL
                }
            } else if(a!=null && b!=null) {
                val l=min(a.x,b.x); val t=min(a.y,b.y); val r=max(a.x,b.x); val bt=max(a.y,b.y)
                paint.style=Paint.Style.FILL; paint.color=Color.argb(140,0,0,0)
                canvas.drawRect(destination.left,destination.top,destination.right,t,paint)
                canvas.drawRect(destination.left,bt,destination.right,destination.bottom,paint)
                canvas.drawRect(destination.left,t,l,bt,paint)
                canvas.drawRect(r,t,destination.right,bt,paint)
                paint.color=Color.CYAN; paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; canvas.drawRect(l,t,r,bt,paint); paint.style=Paint.Style.FILL; paint.color=Color.WHITE
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if(e.actionMasked==MotionEvent.ACTION_CANCEL || e.pointerCount>1) {
                start=null; end=null; invalidate(); return true
            }
            if(destination.width()<=0 || destination.height()<=0) return false
            if(e.actionMasked==MotionEvent.ACTION_DOWN && !destination.contains(e.x,e.y)) {
                start=null; end=null; invalidate(); return false
            }
            if(e.actionMasked!=MotionEvent.ACTION_DOWN && start==null) return false
            val p=PointF(e.x.coerceIn(destination.left,destination.right),e.y.coerceIn(destination.top,destination.bottom))
            when(e.action) { MotionEvent.ACTION_DOWN -> { start=p; end=p }; MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> end=p }
            if(e.action==MotionEvent.ACTION_UP) performClick()
            if(colorMode) tapped()?.let { px -> onTap?.invoke(image.getPixel(px.x,px.y)) }
            invalidate(); return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        /** Píxel de la imagen bajo el último toque (modo color). */
        fun tapped(): android.graphics.Point? {
            val p=end ?: return null
            if(destination.width()<=0) return null
            return android.graphics.Point(((p.x-destination.left)*image.width/destination.width()).toInt().coerceIn(0,image.width-1),((p.y-destination.top)*image.height/destination.height()).toInt().coerceIn(0,image.height-1))
        }
        fun selected(): Rect? {
            val a=start ?: return null; val b=end ?: return null
            if(destination.width()<=0) return null
            fun x(v: Float)=((v-destination.left)*image.width/destination.width()).roundToInt().coerceIn(0,image.width)
            fun y(v: Float)=((v-destination.top)*image.height/destination.height()).roundToInt().coerceIn(0,image.height)
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
    else -> project.copy(templates=project.templates+saved,actions=assignTemplateToAction(project.actions,actionId,saved.id))
}
