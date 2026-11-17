package es.macrostudio.capture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capturas de pantalla guardadas con «📷 Capturas rápidas»: se hacen varias seguidas en el
 * juego y después se recortan los botones desde la app, sin volver al juego cada vez.
 * Se guardan en filesDir/shots (como mucho [MAX]; se borran las más antiguas).
 */
object Shots {
    const val MAX = 200
    const val FOLDER = "shots"

    fun dir(context: Context): File = File(context.filesDir, FOLDER).apply { mkdirs() }

    /** Las más nuevas primero. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.startsWith("shot-") && f.name.endsWith(".png") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** Copia la última captura (capture.png) a la carpeta de capturas guardadas. */
    fun saveLatest(context: Context): File? = runCatching {
        val source = File(context.filesDir, "capture.png")
        require(source.exists())
        val name = "shot-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date()) + ".png"
        val target = File(dir(context), name)
        source.copyTo(target, overwrite = true)
        list(context).drop(MAX).forEach { it.delete() }
        // Si hay una carpeta abierta en «Imágenes», la captura nueva va ahí.
        es.macrostudio.core.Studio.shotFolder.takeIf { it.isNotBlank() }?.let { ShotFolders.assign(context, listOf(target), it) }
        target
    }.getOrNull()

    /** Ruta relativa segura para pasar a la pantalla de recorte («shots/shot-….png»). */
    fun relative(file: File): String = "$FOLDER/${file.name}"

    fun isSafeRelative(path: String): Boolean =
        path.startsWith("$FOLDER/") && !path.contains("..") && path.count { it == '/' } == 1 && path.endsWith(".png")

    fun delete(file: File) { file.delete() }
}
