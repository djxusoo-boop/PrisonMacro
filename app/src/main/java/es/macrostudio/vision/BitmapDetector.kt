package es.macrostudio.vision

import android.graphics.Bitmap
import es.macrostudio.core.SearchMode
import es.macrostudio.core.Template
import kotlin.math.*

object BitmapDetector {
    private fun pixels(b: Bitmap): Pixels {
        val data=IntArray(b.width*b.height); b.getPixels(data,0,b.width,0,0,b.width,b.height)
        return Pixels(b.width,b.height,data)
    }
    fun find(frame: Bitmap, template: Bitmap, region: Template, mode: SearchMode, cancelled: ()->Boolean={false}): Match? {
        require(frame.width==region.screenWidth && frame.height==region.screenHeight) { "Cambió la resolución: vuelve a capturar y grabar" }
        require(template.width==region.width && template.height==region.height) { "El archivo de plantilla no coincide con su registro" }
        val full=pixels(frame); val target=pixels(template)
        require(Matcher.isDistinctive(target)) { "Plantilla demasiado uniforme: recorta texto, bordes o detalles" }
        if(mode==SearchMode.EXACT) return Match(region.x,region.y,Matcher.score(full,target,region.x,region.y))
        val scale=min(1.0,480.0/max(frame.width,frame.height))
        val tw=(template.width*scale).roundToInt(); val th=(template.height*scale).roundToInt()
        require(tw>=3 && th>=3) { "Plantilla demasiado pequeña: amplía el recorte" }
        val small=Bitmap.createScaledBitmap(frame,(frame.width*scale).roundToInt(),(frame.height*scale).roundToInt(),true)
        val smallTemplate=Bitmap.createScaledBitmap(template,tw,th,true)
        val coarse=try { Matcher.find(pixels(small),pixels(smallTemplate),cancelled=cancelled) } finally { if(small!==frame) small.recycle(); if(smallTemplate!==template) smallTemplate.recycle() } ?: return null
        val cx=(coarse.x/scale).roundToInt(); val cy=(coarse.y/scale).roundToInt(); val radius=ceil(2/scale).toInt()
        val stride=max(1,min(template.width,template.height)/40)
        val candidates=ArrayList<Match>()
        for(y in max(0,cy-radius)..min(frame.height-template.height,cy+radius)) {
            if(cancelled()) return null
            for(x in max(0,cx-radius)..min(frame.width-template.width,cx+radius)) {
                val score=Matcher.score(full,target,x,y,stride)
                if(candidates.size<3 || score>candidates.last().confidence) { candidates.add(Match(x,y,score)); candidates.sortByDescending { it.confidence }; if(candidates.size>3) candidates.removeAt(3) }
            }
        }
        return candidates.map { it.copy(confidence=Matcher.score(full,target,it.x,it.y)) }.maxByOrNull { it.confidence }
    }
}
