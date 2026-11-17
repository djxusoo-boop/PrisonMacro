package es.macrostudio.vision

import kotlin.math.abs

/** [ignored]: píxeles que no cuentan (los tapa el panel flotante de PrisonMacro o sus marcas). */
class Pixels(val width: Int, val height: Int, val colors: IntArray, val ignored: BooleanArray? = null) {
    init { require(width > 0 && height > 0 && width.toLong()*height == colors.size.toLong() && (ignored == null || ignored.size == colors.size)) }
    fun isIgnored(index: Int): Boolean = ignored?.get(index) == true

    /** Copia que no mira lo que hay dentro de [rects] (coordenadas de esta imagen). */
    fun masked(rects: List<es.macrostudio.vision.Area>): Pixels {
        if (rects.isEmpty()) return this
        val mask = ignored?.copyOf() ?: BooleanArray(colors.size)
        for (r in rects) {
            val left = r.left.coerceIn(0, width); val right = r.right.coerceIn(0, width)
            val top = r.top.coerceIn(0, height); val bottom = r.bottom.coerceIn(0, height)
            for (y in top until bottom) java.util.Arrays.fill(mask, y*width+left, y*width+right, true)
        }
        return Pixels(width, height, colors, mask)
    }
}
/** Rectángulo de la pantalla: de [left],[top] (incluidos) a [right],[bottom] (excluidos). */
data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int)
/** [width]/[height]: tamaño de la imagen en esta pantalla (cambia si se adaptó a otro tamaño de pantalla). */
data class Match(val x: Int, val y: Int, val confidence: Double, val width: Int = 0, val height: Int = 0)

/** Local RGB template matching. No uploads, no native ABI dependency. Same scale only. */
object Matcher {
    fun isDistinctive(image: Pixels): Boolean {
        val sum=DoubleArray(3); val squares=DoubleArray(3); var count=0
        val stride=maxOf(1,image.colors.size/4096)
        for(i in image.colors.indices step stride) { count++; for(channel in 0..2) { val value=(image.colors[i] shr (channel*8) and 255).toDouble(); sum[channel]+=value; squares[channel]+=value*value } }
        return (0..2).any { squares[it]/count-(sum[it]/count)*(sum[it]/count)>=100.0 }
    }
    private fun distance(a: Int, b: Int): Int = abs((a shr 16 and 255)-(b shr 16 and 255)) + abs((a shr 8 and 255)-(b shr 8 and 255)) + abs((a and 255)-(b and 255))
    fun score(screen: Pixels, template: Pixels, x: Int, y: Int, stride: Int=1): Double {
        require(stride>=1)
        if (x < 0 || y < 0 || x+template.width > screen.width || y+template.height > screen.height) return 0.0
        var error = 0L; var count=0; var total=0
        for (ty in 0 until template.height step stride) for (tx in 0 until template.width step stride) {
            total++
            val index=(y+ty)*screen.width+x+tx
            if (screen.isIgnored(index)) continue
            error += distance(screen.colors[index],template.colors[ty*template.width+tx]); count++
        }
        // Lo que tapa el panel no cuenta; si casi todo está tapado, no se puede ver.
        if (count == 0 || count < total*0.35) return 0.0
        return 1.0 - error.toDouble() / (count*765.0)
    }
    fun find(screen: Pixels, template: Pixels, exact: Pair<Int,Int>? = null, cancelled: () -> Boolean = { false }): Match? {
        if (template.width > screen.width || template.height > screen.height) return null
        if (exact != null) return Match(exact.first,exact.second,score(screen,template,exact.first,exact.second))
        return findTop(screen, template, 1, cancelled).firstOrNull()
    }

    /** Reduce la imagen [f] veces promediando cada bloque f×f. Así no se «salta» píxeles: con
     * la reducción de Android el texto fino cambiaba según dónde cayera y el buscador elegía
     * otro botón parecido (p. ej. otra fila de una lista). */
    fun shrink(p: Pixels, f: Int): Pixels {
        if (f <= 1) return p
        val w = p.width / f; val h = p.height / f
        require(w >= 1 && h >= 1)
        val out = IntArray(w * h); val n = f * f
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0 until f) {
                val row = (y * f + dy) * p.width + x * f
                for (dx in 0 until f) { val c = p.colors[row + dx]; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 }
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        // Un bloque con algo tapado (panel flotante) se ignora entero.
        val ignored = p.ignored?.let { src ->
            val m = BooleanArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var hit = false
                var dy = 0
                while (!hit && dy < f) { val row = (y * f + dy) * p.width + x * f; var dx = 0; while (dx < f) { if (src[row + dx]) { hit = true; break }; dx++ }; dy++ }
                m[y * w + x] = hit
            }
            m
        }
        return Pixels(w, h, out, ignored)
    }

    /** Los [k] mejores sitios distintos (separados al menos medio recorte), del mejor al peor. */
    fun findTop(screen: Pixels, template: Pixels, k: Int, cancelled: () -> Boolean = { false }): List<Match> {
        if (template.width > screen.width || template.height > screen.height) return emptyList()
        // Cheap deterministic grid scores nominate candidates; full scores decide, never samples alone.
        // Puntos de muestra (7×7) precalculados; los 48 mejores sitios se guardan en un montón
        // (antes se reordenaba la lista en cada sitio bueno y en fondos lisos iba muy lento).
        val offsets = IntArray(49); val samples = IntArray(49)
        for (gy in 0..6) for (gx in 0..6) {
            val tx = gx*(template.width-1)/6; val ty = gy*(template.height-1)/6
            offsets[gy*7+gx] = ty*screen.width+tx; samples[gy*7+gx] = template.colors[ty*template.width+tx]
        }
        val heap = java.util.PriorityQueue<Match>(49, compareBy { it.confidence })
        for (y in 0..screen.height-template.height) {
            if (cancelled()) return emptyList()
            val rowStart = y*screen.width
            for (x in 0..screen.width-template.width) {
                var error = 0; var valid = 0
                val base = rowStart+x
                for (i in 0 until 49) {
                    val index = base+offsets[i]
                    if (screen.isIgnored(index)) continue
                    error += distance(screen.colors[index],samples[i]); valid++
                }
                if (valid < 17) continue
                val confidence = 1-error/(valid*765.0)
                if (heap.size < 48) heap.add(Match(x,y,confidence))
                else if (confidence > heap.peek()!!.confidence) { heap.poll(); heap.add(Match(x,y,confidence)) }
            }
        }
        val candidates = heap.toList()
        val scored = candidates.map { it.copy(confidence=score(screen,template,it.x,it.y)) }.sortedByDescending { it.confidence }
        val picked = ArrayList<Match>()
        val apartX = maxOf(2, template.width/2); val apartY = maxOf(2, template.height/2)
        for (m in scored) {
            if (picked.none { abs(it.x-m.x) < apartX && abs(it.y-m.y) < apartY }) picked += m
            if (picked.size >= k) break
        }
        return picked
    }
}
