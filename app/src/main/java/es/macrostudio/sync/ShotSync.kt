package es.macrostudio.sync

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import es.macrostudio.capture.Shots
import java.io.File

/**
 * «📸 Mis capturas» en la cuenta: las capturas enteras del juego se comparten entre
 * todos los aparatos con la misma cuenta (Android e iPhone).
 *
 *  users/<uid>/shotsIndex/<id> = { createdAtMs, sizeBytes, device }   (lista ligera)
 *  users/<uid>/shots/<id>      = "PNG en base64"                       (la imagen)
 *
 * El id es el nombre del archivo («shot-20261109-200812-829»), igual en todos los
 * aparatos: así nunca se duplican. Sincronizar sube las que faltan en la cuenta y baja
 * las que faltan aquí.
 */
object ShotSync {
    private const val MAX_BYTES = 8_000_000
    private fun db() = FirebaseDatabase.getInstance("https://prison-ed653.firebaseio.com")
    private fun uid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    fun isSignedIn(): Boolean = uid() != null

    /** Texto para la pantalla («Sincronizado: 2 subidas, 3 bajadas»…). */
    var status by androidx.compose.runtime.mutableStateOf("")
    var running by androidx.compose.runtime.mutableStateOf(false)

    /** Sincroniza si hay cuenta y no se está sincronizando ya. */
    fun syncNow(context: Context) {
        if (running || !isSignedIn()) { if (!isSignedIn()) status = "Entra en tu cuenta (⚙ Ajustes › ☁ Nube) para tener tus capturas en todos los aparatos."; return }
        running = true
        sync(context, progress = { status = it }) { error, up, down ->
            running = false
            status = error ?: if (up + down == 0) "✔ Capturas al día con tu cuenta" else "✔ Sincronizado: $up subida(s), $down bajada(s)"
            es.macrostudio.core.Studio.screenshotVersion++
        }
    }

    private fun safeId(id: String) = id.matches(Regex("shot-[0-9A-Za-z_-]{6,60}"))

    /** Responde (error, subidas, bajadas) en el hilo principal. */
    fun sync(context: Context, progress: (String) -> Unit, done: (String?, Int, Int) -> Unit) {
        val app = context.applicationContext
        val user = uid() ?: run { done("Entra en tu cuenta (⚙ Ajustes › ☁ Nube) para compartir las capturas entre aparatos.", 0, 0); return }
        progress("Mirando las capturas de tu cuenta…")
        db().getReference("users/$user/shotsDeleted").get().addOnCompleteListener { del ->
        // Las borradas «en todos los aparatos» desde otro aparato se quitan también aquí.
        val gone = if (del.isSuccessful) del.result.children.mapNotNull { it.key }.toSet() else emptySet()
        Shots.list(app).forEach { f -> if (f.nameWithoutExtension in gone) f.delete() }
        db().getReference("users/$user/shotsIndex").get().addOnCompleteListener { t ->
            if (!t.isSuccessful) { done("No se pudo leer la cuenta: ${t.exception?.localizedMessage ?: "sin conexión"}", 0, 0); return@addOnCompleteListener }
            val remote = t.result.children.mapNotNull { it.key }.filter(::safeId).toSet() - gone
            val localFiles = Shots.list(app)
            val local = localFiles.map { it.nameWithoutExtension }.toSet()
            val toUpload = localFiles.filter { it.nameWithoutExtension !in remote && it.length() in 1..MAX_BYTES }
            val toDownload = (remote - local).toList()
            var up = 0; var down = 0
            fun downloadNext(i: Int) {
                if (i >= toDownload.size) { done(null, up, down); return }
                val id = toDownload[i]
                progress("Bajando captura ${i + 1} de ${toDownload.size}…")
                db().getReference("users/$user/shots/$id").get().addOnCompleteListener { r ->
                    val text = if (r.isSuccessful) r.result.getValue(String::class.java) else null
                    if (text != null) runCatching {
                        val bytes = Base64.decode(text, Base64.DEFAULT)
                        require(bytes.size in 1..MAX_BYTES)
                        File(Shots.dir(app), "$id.png").writeBytes(bytes); down++
                    }
                    downloadNext(i + 1)
                }
            }
            fun uploadNext(i: Int) {
                if (i >= toUpload.size) { downloadNext(0); return }
                val file = toUpload[i]; val id = file.nameWithoutExtension
                progress("Subiendo captura ${i + 1} de ${toUpload.size}…")
                val data = runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrNull()
                if (data == null) { uploadNext(i + 1); return }
                db().getReference("users/$user/shots/$id").setValue(data).addOnCompleteListener { w ->
                    if (!w.isSuccessful) { uploadNext(i + 1); return@addOnCompleteListener }
                    db().getReference("users/$user/shotsIndex/$id")
                        .setValue(mapOf("createdAtMs" to file.lastModified(), "sizeBytes" to file.length(), "device" to android.os.Build.MODEL.take(40)))
                        .addOnCompleteListener { up++; uploadNext(i + 1) }
                }
            }
            uploadNext(0)
        }
        }
    }

    /** Borra una captura aquí y en la cuenta; los demás aparatos la quitan al sincronizar. */
    fun deleteEverywhere(context: Context, file: File, done: (String?) -> Unit) {
        val id = file.nameWithoutExtension
        Shots.delete(file)
        val user = uid() ?: run { done(null); return }
        db().getReference("users/$user/shotsDeleted/$id").setValue(System.currentTimeMillis()).addOnCompleteListener {
            db().getReference("users/$user/shotsIndex/$id").removeValue().addOnCompleteListener {
                db().getReference("users/$user/shots/$id").removeValue().addOnCompleteListener { r -> done(if (r.isSuccessful) null else r.exception?.localizedMessage) }
            }
        }
    }
}
