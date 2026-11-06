package es.macrostudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.macrostudio.editor.CropActivity
import es.macrostudio.editor.recroppedTemplate
import es.macrostudio.core.Template
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CropDeviceTest {
    @Test fun recroppingKeepsOriginalScreenCoordinates() {
        val original=Template("old.png",120,80,200,100,1920,1080)
        val updated=recroppedTemplate(original,"new.png",Rect(25,10,175,90))
        assertEquals("new.png",updated.file)
        assertEquals(145,updated.x)
        assertEquals(90,updated.y)
        assertEquals(150,updated.width)
        assertEquals(80,updated.height)
        assertEquals(1920,updated.screenWidth)
        assertEquals(1080,updated.screenHeight)
    }

    @Test fun landscapeCropMapsLetterboxAndCancelledGestureClearsSelection() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        // Test the real crop View without replacing the user's capture.png or project.
        instrumentation.runOnMainSync {
            val activity=CropActivity()
            val attach=android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java)
            attach.isAccessible=true
            attach.invoke(activity,instrumentation.targetContext)
            val source=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
            val surface=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
            try {
                val view=activity.CropView(source)
                view.layout(0,0,400,400); view.draw(Canvas(surface))
                fun touch(action: Int,x: Float,y: Float): Boolean {
                    val event=MotionEvent.obtain(0,0,action,x,y,0)
                    return try { view.onTouchEvent(event) } finally { event.recycle() }
                }
                assertFalse(touch(MotionEvent.ACTION_DOWN,20f,20f))
                assertNull(view.selected())
                touch(MotionEvent.ACTION_DOWN,300f,250f)
                touch(MotionEvent.ACTION_UP,100f,150f)
                assertEquals(Rect(50,25,150,75),view.selected())
                touch(MotionEvent.ACTION_DOWN,100f,150f)
                touch(MotionEvent.ACTION_MOVE,300f,250f)
                touch(MotionEvent.ACTION_CANCEL,300f,250f)
                assertNull("Un gesto cancelado no debe guardarse",view.selected())
            } finally { source.recycle(); surface.recycle() }
        }
    }
}
