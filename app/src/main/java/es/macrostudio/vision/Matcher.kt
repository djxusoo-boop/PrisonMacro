package es.macrostudio.vision

import kotlin.math.abs

data class Pixels(val width: Int, val height: Int, val colors: IntArray) {
    init { require(width > 0 && height > 0 && width.toLong()*height == colors.size.toLong()) }
}
data class Match(val x: Int, val y: Int, val confidence: Double)

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
        var error = 0L; var count=0
        for (ty in 0 until template.height step stride) for (tx in 0 until template.width step stride) { error += distance(screen.colors[(y+ty)*screen.width+x+tx],template.colors[ty*template.width+tx]); count++ }
        return 1.0 - error.toDouble() / (count*765.0)
    }
    fun find(screen: Pixels, template: Pixels, exact: Pair<Int,Int>? = null, cancelled: () -> Boolean = { false }): Match? {
        if (template.width > screen.width || template.height > screen.height) return null
        if (exact != null) return Match(exact.first,exact.second,score(screen,template,exact.first,exact.second))
        // Cheap deterministic grid scores nominate candidates; full scores decide, never samples alone.
        val candidates = ArrayList<Match>()
        for (y in 0..screen.height-template.height) {
            if (cancelled()) return null
            for (x in 0..screen.width-template.width) {
                var error = 0
                for (gy in 0..4) for (gx in 0..4) {
                    val tx = gx*(template.width-1)/4; val ty = gy*(template.height-1)/4
                    error += distance(screen.colors[(y+ty)*screen.width+x+tx],template.colors[ty*template.width+tx])
                }
                val confidence = 1-error/19125.0
                if (candidates.size < 24 || confidence > candidates.last().confidence) {
                    candidates.add(Match(x,y,confidence)); candidates.sortByDescending { it.confidence }
                    if (candidates.size > 24) candidates.removeAt(24)
                }
            }
        }
        return candidates.map { it.copy(confidence=score(screen,template,it.x,it.y)) }.maxByOrNull { it.confidence }
    }
}
