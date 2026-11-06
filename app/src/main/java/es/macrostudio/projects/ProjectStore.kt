package es.macrostudio.projects

import android.content.Context
import android.util.AtomicFile
import es.macrostudio.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun recoverPrimaryTemplate(primary: Template?, library: List<SavedTemplate>): List<SavedTemplate> =
    if(primary!=null && library.none { it.template.file==primary.file }) library+SavedTemplate(name="Imagen principal",template=primary) else library

class ProjectStore(context: Context) {
    private val dir = File(context.filesDir,"projects").apply { mkdirs() }
    fun list(): List<Project> = dir.listFiles { f -> f.name.endsWith(".json") || f.name.endsWith(".json.backup") }?.map { File(dir,it.name.removeSuffix(".backup")) }?.distinct()?.mapNotNull { file ->
        runCatching { decode(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrElse {
            runCatching { decode(AtomicFile(File(file.path+".backup")).readFully().toString(Charsets.UTF_8)) }.getOrNull()
        }
    }?.sortedBy { it.name } ?: emptyList()
    fun save(p: Project) {
        require(p.id.matches(Regex("[a-zA-Z0-9-]{1,60}")))
        require(p.validate() == null) { p.validate().orEmpty() }
        val base=File(dir,"${p.id}.json")
        if(base.exists()) {
            val previous=runCatching { AtomicFile(base).readFully().also { decode(it.toString(Charsets.UTF_8)) } }.getOrNull()
            if(previous!=null) {
                val backup=AtomicFile(File(base.path+".backup")); val output=backup.startWrite()
                try { output.write(previous); backup.finishWrite(output) } catch(e: Exception) { backup.failWrite(output); throw e }
            }
        }
        val file=AtomicFile(base); val stream=file.startWrite()
        try { stream.write(encode(p).toByteArray()); file.finishWrite(stream) } catch(e: Exception) { file.failWrite(stream); throw e }
    }
    companion object {
        fun encode(p: Project): String = JSONObject().apply {
            put("schemaVersion",4); put("id",p.id); put("name",p.name); put("mode",p.mode.name); put("threshold",p.threshold)
            put("delayMs",p.delayMs); put("intervalMs",p.intervalMs); put("timeoutMs",p.timeoutMs); put("maxRuns",p.maxRuns)
            p.template?.let { put("template",JSONObject().put("file",it.file).put("x",it.x).put("y",it.y).put("width",it.width).put("height",it.height).put("screenWidth",it.screenWidth).put("screenHeight",it.screenHeight)) }
            put("templates",JSONArray().apply { p.templates.forEach { saved -> put(JSONObject().put("id",saved.id).put("name",saved.name).put("template",templateJson(saved.template))) } })
            put("actions",actionsJson(p.actions))
        }.toString(2)
        private fun templateJson(t: Template)=JSONObject().put("file",t.file).put("x",t.x).put("y",t.y).put("width",t.width).put("height",t.height).put("screenWidth",t.screenWidth).put("screenHeight",t.screenHeight)
        private fun templateFromJson(t: JSONObject)=Template(t.getString("file"),t.getInt("x"),t.getInt("y"),t.getInt("width"),t.getInt("height"),t.getInt("screenWidth"),t.getInt("screenHeight"))
        private fun actionsJson(actions: List<Action>): JSONArray = JSONArray().apply { actions.forEach { a -> put(JSONObject().apply {
            put("id",a.id); put("kind",a.kind.name); put("name",a.name); put("description",a.description); put("enabled",a.enabled); put("durationMs",a.durationMs); put("count",a.count)
            put("packageName",a.packageName); put("templateId",a.templateId); put("clickTarget",a.clickTarget.name); put("clickStyle",a.clickStyle.name); put("clickOffsetX",a.clickOffsetX.toDouble()); put("clickOffsetY",a.clickOffsetY.toDouble()); put("targetColor",a.targetColor); put("colorTolerance",a.colorTolerance); put("colorMode",a.colorMode.name); put("colorX",a.colorX.toDouble()); put("colorY",a.colorY.toDouble())
            put("points",JSONArray().apply { a.points.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } })
            put("children",actionsJson(a.children)); put("otherwise",actionsJson(a.otherwise))
        }) } }
        fun decode(json: String): Project {
            require(json.length <= 2_000_000) { "Proyecto demasiado grande" }
            val root=JSONObject(json); val schema=root.getInt("schemaVersion"); require(schema in 2..4) { "Versión de proyecto incompatible" }
            var count=0
            fun actions(array: JSONArray, depth: Int): List<Action> {
                require(depth <= 8 && array.length() <= 500) { "Demasiadas instrucciones" }
                return List(array.length()) { i ->
                    require(++count <= 500)
                    val a=array.getJSONObject(i); val points=a.getJSONArray("points"); require(points.length() <= 1000)
                    Action(id=a.getString("id"),kind=Kind.valueOf(a.getString("kind")),name=a.getString("name"),description=a.optString("description",""),enabled=a.getBoolean("enabled"),durationMs=a.getLong("durationMs"),count=a.getInt("count"),packageName=a.optString("packageName","es.macrostudio"),templateId=a.optString("templateId",""),clickTarget=runCatching { ClickTarget.valueOf(a.optString("clickTarget",ClickTarget.CENTER.name)) }.getOrDefault(ClickTarget.CENTER),clickStyle=runCatching { ClickStyle.valueOf(a.optString("clickStyle",ClickStyle.SINGLE.name)) }.getOrDefault(ClickStyle.SINGLE),clickOffsetX=a.optDouble("clickOffsetX",0.0).toFloat(),clickOffsetY=a.optDouble("clickOffsetY",0.0).toFloat(),targetColor=a.optInt("targetColor",0xFFFF0000.toInt()),colorTolerance=a.optInt("colorTolerance",24),colorMode=runCatching { SearchMode.valueOf(a.optString("colorMode",SearchMode.ANYWHERE.name)) }.getOrDefault(SearchMode.ANYWHERE),colorX=a.optDouble("colorX",.5).toFloat(),colorY=a.optDouble("colorY",.5).toFloat(),points=List(points.length()) { val pt=points.getJSONArray(it); Point(pt.getDouble(0).toFloat(),pt.getDouble(1).toFloat()) },children=actions(a.getJSONArray("children"),depth+1),otherwise=actions(a.getJSONArray("otherwise"),depth+1))
                }
            }
            val t=root.optJSONObject("template")?.let(::templateFromJson)
            val decodedLibrary=if(schema>=3) root.optJSONArray("templates")?.let { array -> List(array.length()) { i -> val item=array.getJSONObject(i); SavedTemplate(item.getString("id"),item.getString("name"),templateFromJson(item.getJSONObject("template"))) } } ?: emptyList() else emptyList()
            // Algunas versiones intermedias ya escribían schema 3/4, pero
            // guardaban la imagen principal fuera de `templates`. Recuperarla
            // aquí evita que el selector de una acción aparezca vacío.
            val library=recoverPrimaryTemplate(t,decodedLibrary)
            val p=Project(id=root.getString("id"),name=root.getString("name"),template=t,mode=SearchMode.valueOf(root.getString("mode")),threshold=root.getDouble("threshold"),delayMs=root.getLong("delayMs"),intervalMs=root.getLong("intervalMs"),timeoutMs=root.getLong("timeoutMs"),maxRuns=root.getInt("maxRuns"),templates=library,actions=actions(root.getJSONArray("actions"),0))
            require(p.validate()==null) { p.validate().orEmpty() }; return p
        }
    }
}
