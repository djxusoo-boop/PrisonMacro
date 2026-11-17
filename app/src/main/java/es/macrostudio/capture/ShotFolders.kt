package es.macrostudio.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Carpetas para ordenar las capturas enteras («Lobby», «Batalla»…). Se guardan en este
 * aparato (shots/carpetas.json): qué carpetas hay y en cuál está cada captura.
 * Los recortes llevan su carpeta dentro de la macro (SavedTemplate.folder).
 */
object ShotFolders {
    private fun file(context: Context) = File(Shots.dir(context), "carpetas.json")

    data class State(val folders: List<String> = emptyList(), val of: Map<String, String> = emptyMap()) {
        fun folderOf(shot: File): String = of[shot.name].orEmpty()
    }

    fun load(context: Context): State = runCatching {
        val root = JSONObject(file(context).readText())
        val folders = root.optJSONArray("folders")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        val of = root.optJSONObject("of")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
        State(folders.distinct(), of)
    }.getOrDefault(State())

    private fun save(context: Context, s: State) {
        val existing = Shots.list(context).map { it.name }.toSet()
        val root = JSONObject()
            .put("folders", JSONArray(s.folders.distinct()))
            .put("of", JSONObject(s.of.filter { (k, v) -> k in existing && v in s.folders }))
        runCatching { file(context).writeText(root.toString()) }
    }

    /** Nombre limpio para una carpeta (sin espacios sobrantes, máximo 40 letras). */
    fun clean(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(40)

    fun create(context: Context, name: String): String {
        val n = clean(name); if (n.isBlank()) return ""
        val s = load(context); if (n !in s.folders) save(context, s.copy(folders = s.folders + n))
        return n
    }

    /** Mete las capturas en [folder] ("" = sacarlas de su carpeta). */
    fun assign(context: Context, shots: Collection<File>, folder: String) {
        val s = load(context)
        val folders = if (folder.isNotBlank() && folder !in s.folders) s.folders + folder else s.folders
        val of = s.of.toMutableMap()
        shots.forEach { if (folder.isBlank()) of.remove(it.name) else of[it.name] = folder }
        save(context, State(folders, of))
    }

    fun rename(context: Context, old: String, new: String) {
        val n = clean(new); if (n.isBlank()) return
        val s = load(context)
        save(context, State(s.folders.map { if (it == old) n else it }.distinct(), s.of.mapValues { (_, v) -> if (v == old) n else v }))
    }

    /** Quita la carpeta: sus capturas no se borran, pasan a «Sin carpeta». */
    fun delete(context: Context, name: String) {
        val s = load(context)
        save(context, State(s.folders - name, s.of.filterValues { it != name }))
    }
}
