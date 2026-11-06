package es.macrostudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.macrostudio.capture.AtomicScreenshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ScreenshotDeviceTest {
    @Test fun interruptedScreenshotPreservesPreviousPng() {
        val file=File.createTempFile("screenshot-",".png",InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val bitmap=Bitmap.createBitmap(20,10,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.CYAN)
            AtomicScreenshot.save(file,bitmap)
            val original=file.readBytes()
            try {
                AtomicScreenshot.write(file) { it.write(byteArrayOf(1,2,3)); throw IOException("Fallo de escritura simulado") }
                fail("Debe propagar el fallo")
            } catch(expected: IOException) { }
            assertArrayEquals(original,file.readBytes())
            val decoded=BitmapFactory.decodeFile(file.path)
            assertNotNull(decoded)
            try { assertEquals(android.graphics.Color.CYAN,decoded.getPixel(0,0)) } finally { decoded.recycle() }
            bitmap.eraseColor(android.graphics.Color.RED)
            AtomicScreenshot.save(file,bitmap)
            val replacement=BitmapFactory.decodeFile(file.path)
            try { assertEquals(android.graphics.Color.RED,replacement.getPixel(0,0)) } finally { replacement.recycle() }
        } finally { bitmap.recycle(); file.delete() }
    }
}
