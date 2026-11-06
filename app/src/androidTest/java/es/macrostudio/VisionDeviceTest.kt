package es.macrostudio

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.macrostudio.core.*
import es.macrostudio.vision.BitmapDetector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisionDeviceTest {
    private fun target(): Bitmap = Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888).apply {
        val c=Canvas(this); c.drawColor(Color.rgb(15,135,140)); val p=Paint().apply { color=Color.WHITE; textSize=26f; isAntiAlias=true }
        c.drawText("PRUEBA 729",8f,42f,p); p.color=Color.YELLOW; c.drawRect(15f,65f,110f,82f,p); p.color=Color.MAGENTA; c.drawCircle(145f,74f,15f,p)
    }
    private fun screen(t: Bitmap,x: Int,y: Int)=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888).apply { val c=Canvas(this); c.drawColor(Color.rgb(18,24,37)); c.drawBitmap(t,x.toFloat(),y.toFloat(),null) }
    private val region=Template("test.png",137,619,180,100,1080,2400)
    @Test fun exactPixelComparisonHasNoScalingDrift() {
        val t=target(); val s=screen(t,137,619)
        try { val match=BitmapDetector.find(s,t,region,SearchMode.EXACT)!!; assertEquals(1.0,match.confidence,0.0) } finally {t.recycle();s.recycle()}
    }
    @Test fun detectsMovedTemplateAndRefinesFullResolution() {
        val t=target(); val s=screen(t,619,1337)
        try {
            val start=android.os.SystemClock.elapsedRealtime()
            val result=BitmapDetector.find(s,t,region,SearchMode.ANYWHERE)!!
            assertEquals(619,result.x); assertEquals(1337,result.y); assertEquals(1.0,result.confidence,0.00001)
            assertTrue("Detección excesivamente lenta",android.os.SystemClock.elapsedRealtime()-start<5000)
            assertTrue(BitmapDetector.find(s,t,region,SearchMode.EXACT)!!.confidence<.93)
        } finally {t.recycle();s.recycle()}
    }
    @Test fun absentTemplateFailsThreshold() {
        val t=target(); val s=screen(t,137,619); s.eraseColor(Color.rgb(18,24,37))
        try { assertTrue(BitmapDetector.find(s,t,region,SearchMode.ANYWHERE)!!.confidence<.93) } finally {t.recycle();s.recycle()}
    }
    @Test fun refusesChangedResolution() {
        val t=target(); val s=Bitmap.createBitmap(2400,1080,Bitmap.Config.ARGB_8888)
        try { assertTrue(runCatching {BitmapDetector.find(s,t,region,SearchMode.EXACT)}.isFailure) } finally {t.recycle();s.recycle()}
    }
}
