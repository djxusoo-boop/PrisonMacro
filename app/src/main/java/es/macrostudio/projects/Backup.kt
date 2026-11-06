package es.macrostudio.projects

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import es.macrostudio.core.Project
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Copias de seguridad y transferencia de macros.
 *
 * - Una macro se exporta como el JSON del proyecto más `imagesBase64`
 *   (formato compatible con versiones anteriores y con el compañero iPhone).
 * - Una copia completa es `{ "prisonMacroBackup": 1, "projects": [ ... ] }`
 *   con cada macro en el mismo formato.
 */
object Backup {
    const val MAX_BYTES = 60_000_000

    fun projectPayload(context: Context, p: Project): JSONObject {
        val root = JSONObject(ProjectStore.encode(p))
        val images = JSONObject()
        (p.templates.map { it.template } + listOfNotNull(p.template)).distinctBy { it.file }.forEach {
            val file = File(context.filesDir, it.file)
            if (file.exists()) images.put(it.file, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }
        root.put("imagesBase64", images)
        return root
    }

    fun fullBackup(context: Context, projects: List<Project>): String = JSONObject().apply {
        put("prisonMacroBackup", 1)
        put("createdAtMs", System.currentTimeMillis())
        put("projects", JSONArray().apply { projects.forEach { put(projectPayload(context, it)) } })
    }.toString()

    fun isFullBackup(bytes: ByteArray): Boolean = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).has("prisonMacroBackup") }.getOrDefault(false)

    /** Devuelve las macros contenidas en el archivo, con sus imágenes ya escritas en disco. */
    fun read(context: Context, bytes: ByteArray, keepIds: Boolean): List<Project> {
        require(bytes.size <= MAX_BYTES) { "Archivo demasiado grande" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val payloads = if (root.has("prisonMacroBackup")) {
            val array = root.getJSONArray("projects")
            require(array.length() <= 500) { "Demasiadas macros en la copia" }
            List(array.length()) { array.getJSONObject(it) }
        } else listOf(root)
        val written = mutableListOf<File>()
        try {
            return payloads.map { decodePayload(context, it, keepIds, written) }
        } catch (e: Exception) {
            written.forEach { it.delete() }
            throw e
        }
    }

    private fun decodePayload(context: Context, root: JSONObject, keepId: Boolean, written: MutableList<File>): Project {
        val legacyImage = root.optString("imageBase64")
        val images = root.optJSONObject("imagesBase64") ?: JSONObject()
        val clean = JSONObject(root.toString()).apply { remove("imageBase64"); remove("imagesBase64") }
        var incoming = ProjectStore.decode(clean.toString())
        if (!keepId) incoming = incoming.copy(id = UUID.randomUUID().toString())
        val remap = mutableMapOf<String, String>()
        (incoming.templates.map { it.template } + listOfNotNull(incoming.template)).distinctBy { it.file }.forEach { old ->
            val encoded = if (images.has(old.file)) images.getString(old.file) else legacyImage
            require(encoded.isNotEmpty()) { "Falta la imagen ${old.file}" }
            val data = Base64.decode(encoded, Base64.DEFAULT)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            require(options.outWidth == old.width && options.outHeight == old.height && old.width.toLong() * old.height <= 20_000_000) { "Imagen inválida en «${incoming.name}»" }
            val name = "template-${UUID.randomUUID()}.png"
            File(context.filesDir, name).also { it.writeBytes(data); written += it }
            remap[old.file] = name
        }
        return incoming.copy(
            template = incoming.template?.let { it.copy(file = remap[it.file] ?: it.file) },
            templates = incoming.templates.map { it.copy(template = it.template.copy(file = remap[it.template.file] ?: it.template.file)) }
        )
    }

    /** Borra los PNG de plantillas que ya no usa ninguna macro guardada. */
    fun deleteOrphanImages(context: Context, projects: List<Project>) {
        val used = projects.flatMap { p -> p.templates.map { it.template.file } + listOfNotNull(p.template?.file) }.toSet()
        context.filesDir.listFiles { f -> f.name.startsWith("template-") && f.name.endsWith(".png") }
            ?.filter { it.name !in used }
            ?.forEach { it.delete() }
    }
}
