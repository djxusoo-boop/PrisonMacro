package es.macrostudio.vision

import kotlin.math.abs

data class ColorMatch(val x: Int, val y: Int, val support: Double, val distance: Int)

/** Búsqueda RGB local. Exige varios píxeles vecinos para evitar un único píxel de ruido. */
object ColorMatcher {
    fun distance(a: Int,b: Int): Int=maxOf(
        abs((a shr 16 and 255)-(b shr 16 and 255)),
        abs((a shr 8 and 255)-(b shr 8 and 255)),
        abs((a and 255)-(b and 255))
    )

    private fun candidate(screen: Pixels,target: Int,tolerance: Int,x: Int,y: Int): ColorMatch? {
        if(x !in 0 until screen.width || y !in 0 until screen.height) return null
        val center=distance(screen.colors[y*screen.width+x],target)
        if(center>tolerance) return null
        var matches=0; var total=0
        for(py in maxOf(0,y-1)..minOf(screen.height-1,y+1)) for(px in maxOf(0,x-1)..minOf(screen.width-1,x+1)) {
            total++; if(distance(screen.colors[py*screen.width+px],target)<=tolerance) matches++
        }
        val required=maxOf(2,(total*0.45).toInt())
        return if(matches>=required) ColorMatch(x,y,matches.toDouble()/total,center) else null
    }

    fun find(screen: Pixels,target: Int,tolerance: Int,exact: Pair<Int,Int>?=null,cancelled: ()->Boolean={false}): ColorMatch? {
        require(tolerance in 0..255)
        exact?.let { return candidate(screen,target,tolerance,it.first,it.second) }
        var best: ColorMatch?=null
        val stride=if(screen.width.toLong()*screen.height>1_500_000) 2 else 1
        for(y in 0 until screen.height step stride) {
            if(cancelled()) return null
            for(x in 0 until screen.width step stride) candidate(screen,target,tolerance,x,y)?.let { found ->
                val current=best
                if(current==null || found.support>current.support || (found.support==current.support && found.distance<current.distance)) best=found
                if(found.support==1.0 && found.distance==0) return found
            }
        }
        return best
    }
}
