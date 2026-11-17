package es.macrostudio.gestures

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * Capa transparente del «👁 modo ver»: dibuja encima del juego dónde toca la macro
 * (círculo), por dónde desliza (línea con flecha), dónde buscó una imagen (recuadro
 * verde o rojo con el parecido) y los botones de Android que pulsa (etiqueta).
 * No recibe toques: todo pasa al juego.
 */
class MarksView(context: Context) : View(context) {
    data class Mark(
        val points: List<PointF> = emptyList(),
        val rect: RectF? = null,
        val label: String = "",
        val color: Int,
        val lifeMs: Long,
        val bornAt: Long = SystemClock.elapsedRealtime()
    )

    private val marks = mutableListOf<Mark>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    /** Zonas de la pantalla donde hay algo dibujado ahora (para que la búsqueda no las mire).
     * El recuadro de «encontrada» no cuenta: va por fuera de la imagen y su interior es el juego. */
    fun activeRects(): List<es.macrostudio.vision.Area> {
        val now = SystemClock.elapsedRealtime()
        val origin = IntArray(2).also { getLocationOnScreen(it) }
        val out = mutableListOf<es.macrostudio.vision.Area>()
        fun square(x: Float, y: Float, r: Float) { out += es.macrostudio.vision.Area((x - r).toInt(), (y - r).toInt(), (x + r).toInt() + 1, (y + r).toInt() + 1) }
        fun label(text: String, x: Float, y: Float) {
            if (text.isBlank()) return
            paint.textSize = 16 * density
            out += es.macrostudio.vision.Area(x.toInt(), (y - 24 * density).toInt(), (x + paint.measureText(text) + 16 * density).toInt() + 1, (y + 6 * density).toInt() + 1)
        }
        for (m in marks.toList()) {
            if (now - m.bornAt > m.lifeMs) continue
            when {
                m.rect != null -> label(m.label, m.rect.left, m.rect.top - 6 * density)
                m.points.size == 1 -> square(m.points[0].x, m.points[0].y, 26 * density * 1.4f + 3 * density)
                m.points.size >= 2 -> {
                    for (i in 1 until m.points.size) {
                        val a = m.points[i - 1]; val b = m.points[i]
                        val steps = (Math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) / (8 * density)).toInt().coerceIn(1, 400)
                        for (k in 0..steps) square(a.x + (b.x - a.x) * k / steps, a.y + (b.y - a.y) * k / steps, 6 * density)
                    }
                    square(m.points.first().x, m.points.first().y, 11 * density)
                    square(m.points.last().x, m.points.last().y, 24 * density)
                }
                m.label.isNotBlank() -> label(m.label, width / 2f - 80 * density + origin[0], height * 0.4f + origin[1])
            }
        }
        return out
    }

    fun add(mark: Mark) { marks += mark; if (marks.size > 30) marks.removeAt(0); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        marks.removeAll { now - it.bornAt > it.lifeMs }
        // La capa empieza en la esquina de la pantalla; si Android la desplaza, se compensa.
        val origin = IntArray(2).also { getLocationOnScreen(it) }
        canvas.save(); canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        for (m in marks) {
            val fade = (1f - (now - m.bornAt).toFloat() / m.lifeMs).coerceIn(0.15f, 1f)
            val alpha = (255 * fade).toInt()
            paint.color = m.color; paint.alpha = alpha
            when {
                m.rect != null -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 4 * density
                    canvas.drawRect(m.rect, paint)
                    drawLabel(canvas, m.label, m.rect.left, m.rect.top - 6 * density, m.color, alpha)
                }
                m.points.size == 1 -> {
                    val p = m.points[0]
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 4 * density
                    canvas.drawCircle(p.x, p.y, 26 * density * (1.4f - fade * 0.4f), paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(p.x, p.y, 7 * density, paint)
                }
                m.points.size >= 2 -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 5 * density
                    for (i in 1 until m.points.size) canvas.drawLine(m.points[i - 1].x, m.points[i - 1].y, m.points[i].x, m.points[i].y, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(m.points.first().x, m.points.first().y, 9 * density, paint)
                    val a = m.points[m.points.size - 2]; val b = m.points.last()
                    val angle = Math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                    val len = 22 * density
                    for (side in listOf(-0.5, 0.5)) canvas.drawLine(b.x, b.y, (b.x - len * Math.cos(angle + side)).toFloat(), (b.y - len * Math.sin(angle + side)).toFloat(), paint.apply { style = Paint.Style.STROKE })
                }
                m.label.isNotBlank() -> drawLabel(canvas, m.label, width / 2f - 80 * density + origin[0], height * 0.4f + origin[1], m.color, alpha)
            }
        }
        canvas.restore()
        if (marks.isNotEmpty()) postInvalidateDelayed(50)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int, alpha: Int) {
        if (text.isBlank()) return
        paint.style = Paint.Style.FILL; paint.textSize = 16 * density
        val w = paint.measureText(text) + 16 * density
        paint.color = Color.argb((alpha * 0.85f).toInt(), 15, 20, 32)
        canvas.drawRoundRect(x, y - 24 * density, x + w, y + 6 * density, 8 * density, 8 * density, paint)
        paint.color = color; paint.alpha = alpha
        canvas.drawText(text, x + 8 * density, y - 3 * density, paint)
    }
}
