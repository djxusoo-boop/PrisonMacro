package es.macrostudio.editor

import android.app.Activity
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

class CropActivity: Activity() {
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
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16,20,31)); setPadding(16,16,16,16) }
        root.setOnApplyWindowInsetsListener { view,insets ->
            @Suppress("DEPRECATION")
            view.setPadding(16+insets.systemWindowInsetLeft,16+insets.systemWindowInsetTop,16+insets.systemWindowInsetRight,16+insets.systemWindowInsetBottom)
            insets
        }
        root.addView(TextView(this).apply { text=(if(existing==null) "NUEVA CAPTURA" else "RECORTAR MÁS · ${existing.name}")+"\nArrastra un rectángulo sobre el elemento. Incluye bordes y detalles; evita fondos uniformes."; setTextColor(Color.WHITE); textSize=18f })
        val crop=CropView(b); root.addView(crop,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(Button(this).apply { text="Guardar recorte"; setOnClickListener {
            val r=crop.selected()
            if(r==null || r.width()<8 || r.height()<8) Toast.makeText(this@CropActivity,"Selecciona un recorte de al menos 8×8 píxeles",Toast.LENGTH_LONG).show()
            else runCatching {
                val filename="template-${UUID.randomUUID()}.png"
                val piece=Bitmap.createBitmap(b,r.left,r.top,r.width(),r.height())
                try {
                    val pixels=IntArray(piece.width*piece.height); piece.getPixels(pixels,0,piece.width,0,0,piece.width,piece.height)
                    require(es.macrostudio.vision.Matcher.isDistinctive(es.macrostudio.vision.Pixels(piece.width,piece.height,pixels))) { "Recorte uniforme: incluye texto o bordes" }
                    File(filesDir,filename).outputStream().use { check(piece.compress(Bitmap.CompressFormat.PNG,100,it)) }
                } finally { if(piece!==b) piece.recycle() }
                val template=if(existing==null) Template(filename,r.left,r.top,r.width(),r.height(),b.width,b.height)
                    else recroppedTemplate(existing.template,filename,r)
                val project=Studio.project
                val updated=if(existing==null) {
                    val saved=SavedTemplate(name="Captura ${project.templates.size+1}",template=template)
                    addCapturedTemplate(project,saved,intent.getStringExtra("actionId"))
                } else project.copy(
                    template=if(project.template?.file==existing.template.file) template else project.template,
                    templates=project.templates.map { if(it.id==existing.id) it.copy(template=template) else it }
                )
                check(Studio.update(updated)) { "No se pudo guardar el proyecto" }
                if(intent.getStringExtra("actionId")==Studio.pendingTemplateActionId) Studio.pendingTemplateActionId=null
                if(existing!=null && updated.template?.file!=existing.template.file && updated.templates.none { it.template.file==existing.template.file }) File(filesDir,existing.template.file).delete()
                Studio.log("Plantilla guardada: ${r.width()}×${r.height()} en (${r.left}, ${r.top})")
                startActivity(Intent(this@CropActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
            }.onFailure { Studio.fail("No se pudo guardar el recorte: ${it.message}"); Toast.makeText(this@CropActivity,it.message,Toast.LENGTH_LONG).show() }
        } })
        root.addView(Button(this).apply { text="Cancelar"; setOnClickListener {
            if(intent.getStringExtra("actionId")==Studio.pendingTemplateActionId) Studio.pendingTemplateActionId=null
            finish()
        } }); setContentView(root)
    }
    override fun onDestroy() { bitmap?.recycle(); bitmap=null; super.onDestroy() }
    inner class CropView(private val image: Bitmap): View(this@CropActivity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG); private val destination=RectF()
        private var start: PointF?=null; private var end: PointF?=null
        override fun onDraw(canvas: Canvas) {
            val scale=min(width.toFloat()/image.width,height.toFloat()/image.height)
            val iw=image.width*scale; val ih=image.height*scale
            destination.set((width-iw)/2,(height-ih)/2,(width+iw)/2,(height+ih)/2)
            paint.style=Paint.Style.FILL; canvas.drawBitmap(image,null,destination,paint)
            val a=start; val b=end
            if(a!=null && b!=null) { paint.color=Color.CYAN; paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; canvas.drawRect(min(a.x,b.x),min(a.y,b.y),max(a.x,b.x),max(a.y,b.y),paint); paint.style=Paint.Style.FILL }
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
            invalidate(); return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
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
 * Añade una captura a la biblioteca y, si se pidió desde una condición, la
 * asigna solo a esa condición. Una segunda captura de condición no debe
 * sustituir silenciosamente la imagen independiente de inicio rápido.
 */
internal fun addCapturedTemplate(project: Project, saved: SavedTemplate, actionId: String?): Project = project.copy(
    template=if(actionId==null || project.template==null) saved.template else project.template,
    templates=project.templates+saved,
    actions=if(actionId!=null) assignTemplateToAction(project.actions,actionId,saved.id) else project.actions
)
