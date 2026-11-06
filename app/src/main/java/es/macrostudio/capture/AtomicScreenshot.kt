package es.macrostudio.capture

import android.graphics.Bitmap
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

/** Publish a complete PNG or keep the previously saved capture intact. */
internal object AtomicScreenshot {
    fun save(file: File, bitmap: Bitmap) = write(file) { stream ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)) { "No se pudo codificar la captura" }
    }

    internal fun write(file: File, encode: (FileOutputStream)->Unit) {
        val atomic=AtomicFile(file)
        val stream=atomic.startWrite()
        try {
            encode(stream)
            atomic.finishWrite(stream)
        } catch(error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
