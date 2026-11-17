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
    /** [mask]: zonas de la pantalla que no se miran (el panel flotante de PrisonMacro y sus marcas). */
    /** ¿Se puede usar una imagen recortada en una pantalla de [w0]×[h0] en otra de [w]×[h]? (misma forma) */
    fun sameShape(w0: Int, h0: Int, w: Int, h: Int): Boolean {
        if (w0 <= 0 || h0 <= 0) return false
        val sx = w.toDouble()/w0; val sy = h.toDouble()/h0
        return abs(sx-sy)/max(sx,sy) < 0.04
    }

    fun find(frame: Bitmap, template: Bitmap, region: Template, mode: SearchMode, mask: List<es.macrostudio.vision.Area> = emptyList(), cancelled: ()->Boolean={false}): Match? {
        require(template.width==region.width && template.height==region.height) { "El archivo de plantilla no coincide con su registro" }
        if (frame.width!=region.screenWidth || frame.height!=region.screenHeight) {
            // Recortada en una pantalla de otro tamaño (otro BlueStacks, una captura del iPhone…):
            // si la forma es la misma, se adapta la imagen al tamaño de esta pantalla.
            require(sameShape(region.screenWidth,region.screenHeight,frame.width,frame.height)) {
                if ((region.screenWidth>region.screenHeight)!=(frame.width>frame.height))
                    "La imagen se recortó con la pantalla ${if(region.screenWidth>region.screenHeight) "horizontal" else "vertical"} y ahora está ${if(frame.width>frame.height) "horizontal" else "vertical"}: gira el juego o recórtala otra vez"
                else "La imagen se recortó en una pantalla de otra forma (${region.screenWidth}×${region.screenHeight}; esta es ${frame.width}×${frame.height}): recórtala otra vez aquí"
            }
            val s = frame.width.toDouble()/region.screenWidth
            val sw = max(3,(template.width*s).roundToInt()); val sh = max(3,(template.height*s).roundToInt())
            val scaled = Bitmap.createScaledBitmap(template,sw,sh,true)
            val fitted = region.copy(x=(region.x*s).roundToInt().coerceIn(0,frame.width-sw), y=(region.y*s).roundToInt().coerceIn(0,frame.height-sh), width=sw, height=sh, screenWidth=frame.width, screenHeight=frame.height)
            return try { find(frame,scaled,fitted,mode,mask,cancelled) } finally { if (scaled!==template) scaled.recycle() }
        }
        return findIn(pixels(frame).masked(mask),pixels(template),region,mode,cancelled)
    }

    /** La búsqueda en sí (sin Bitmap, para poder probarla en el ordenador). */
    fun findIn(full: Pixels, target: Pixels, region: Template, mode: SearchMode, cancelled: ()->Boolean={false}): Match? {
        require(Matcher.isDistinctive(target)) { "Plantilla demasiado uniforme: recorta texto, bordes o detalles" }
        if(mode==SearchMode.EXACT) return Match(region.x,region.y,Matcher.score(full,target,region.x,region.y),target.width,target.height)
        // Primero se busca en pequeño (unos 480 px de lado) y luego se afina a tamaño real.
        val f=max(1,ceil(max(full.width,full.height)/480.0).toInt())
        require(target.width/f>=3 && target.height/f>=3) { "Plantilla demasiado pequeña: amplía el recorte" }
        val coarse=Matcher.findTop(Matcher.shrink(full,f),Matcher.shrink(target,f),3,cancelled)
        if(coarse.isEmpty() || cancelled()) return null
        // Se afinan los 3 mejores sitios (no solo uno): si dos botones se parecen mucho en
        // pequeño, a tamaño real se ve cuál es el bueno.
        val radius=2*f
        val stride=max(1,min(target.width,target.height)/24)
        var best: Match?=null
        for(c in coarse) {
            val cx=c.x*f; val cy=c.y*f
            val local=ArrayList<Match>()
            for(y in max(0,cy-radius)..min(full.height-target.height,cy+radius)) {
                if(cancelled()) return null
                for(x in max(0,cx-radius)..min(full.width-target.width,cx+radius)) {
                    val score=Matcher.score(full,target,x,y,stride)
                    if(local.size<3 || score>local.last().confidence) { local.add(Match(x,y,score)); local.sortByDescending { it.confidence }; if(local.size>3) local.removeAt(3) }
                }
            }
            local.map { it.copy(confidence=Matcher.score(full,target,it.x,it.y),width=target.width,height=target.height) }.maxByOrNull { it.confidence }
                ?.let { if(best==null || it.confidence>best!!.confidence) best=it }
        }
        return best
    }
}
