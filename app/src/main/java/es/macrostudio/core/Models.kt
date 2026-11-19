package es.macrostudio.core

import java.util.UUID

/** MARK_TIME / WAIT_SINCE_MARK: el «⏱ reloj» (ver [ClockMarks]). Van al final para no cambiar el orden de los demás. */
enum class Kind { GESTURE, WAIT, WAIT_IMAGE, REPEAT, CLICK_IMAGE, IF_IMAGE, IF_COLOR, HOME, BACK, RECENTS, CLOSE_ACTIVE, NOTIFICATIONS, QUICK_SETTINGS, OPEN_APP, STOP, WHEN_IMAGE, MARK_TIME, WAIT_SINCE_MARK }
/** Qué hace la macro después de una regla «⚡ Siempre que se vea». */
enum class AfterRule { CONTINUE, SKIP_STEP, EXIT_LOOP, RESTART, STOP }
enum class SearchMode { EXACT, ANYWHERE }
enum class ClickTarget { CENTER, OFFSET, FIXED }
enum class ClickStyle { SINGLE, DOUBLE, LONG }
data class Point(val x: Float, val y: Float)

/** Converts a real screen coordinate into the normalized 0..1 format used by
 * portable macros. Values outside the display are clamped instead of producing
 * an invalid action when an overlay reports a cutout or edge coordinate. */
fun normalizedScreenPoint(rawX: Float, rawY: Float, width: Int, height: Int): Point {
    require(width > 1 && height > 1)
    return Point((rawX/(width-1)).coerceIn(0f,1f),(rawY/(height-1)).coerceIn(0f,1f))
}
data class Action(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind = Kind.GESTURE,
    val name: String = "Pulsación",
    val description: String = "",
    val enabled: Boolean = true,
    val durationMs: Long = 80,
    val points: List<Point> = listOf(Point(.5f, .5f)),
    val count: Int = 2,
    val children: List<Action> = emptyList(),
    val otherwise: List<Action> = emptyList(),
    val packageName: String = "es.macrostudio",
    val templateId: String = "",
    val clickTarget: ClickTarget = ClickTarget.CENTER,
    val clickStyle: ClickStyle = ClickStyle.SINGLE,
    val clickOffsetX: Float = 0f,
    val clickOffsetY: Float = 0f,
    val targetColor: Int = 0xFFFF0000.toInt(),
    val colorTolerance: Int = 24,
    val colorMode: SearchMode = SearchMode.ANYWHERE,
    val colorX: Float = .5f,
    val colorY: Float = .5f,
    /** Buscar imagen / esperar imagen: si no aparece a tiempo, sigue con el siguiente paso en vez de parar. */
    val skipIfMissing: Boolean = false,
    /** Buscar imagen y tocarla: tiempo máximo buscando (0 = el de la macro). */
    val waitMs: Long = 0,
    /** Espera antes de hacer este paso y después de hacerlo (así no hacen falta pasos «Esperar»). */
    val delayBeforeMs: Long = 0,
    val delayAfterMs: Long = 0,
    /** «⚡ Siempre que se vea»: qué hacer al terminar sus pasos (waitMs = descanso antes de poder volver a actuar). */
    val afterRule: AfterRule = AfterRule.CONTINUE,
    /** «Esperar a que salga una imagen» al revés: espera a que YA NO se vea (waitMs seguidos sin verla;
     * p. ej. «hasta que no quede ninguna caja por abrir»). Mientras, las reglas ⚡ siguen actuando. */
    val untilGone: Boolean = false
) {
    fun duplicate(): Action = copy(id = UUID.randomUUID().toString(), children = children.map { it.duplicate() }, otherwise = otherwise.map { it.duplicate() })
}
data class Template(val file: String, val x: Int, val y: Int, val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int)
/** [folder]: carpeta donde la guarda el usuario para ordenarlas ("" = sin carpeta). */
data class SavedTemplate(val id: String = UUID.randomUUID().toString(), val name: String = "Nueva captura", val template: Template, val folder: String = "")
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Mi secuencia",
    val template: Template? = null,
    val mode: SearchMode = SearchMode.ANYWHERE,
    val threshold: Double = .93,
    val delayMs: Long = 500,
    val intervalMs: Long = 0,
    val timeoutMs: Long = 60_000,
    val maxRuns: Int = 10,
    /** Veces que se ejecuta la macro completa cada vez que se pulsa ▶ (0 = sin fin). */
    val repeatCount: Int = 1,
    /** Parada automática de seguridad, en minutos (0 = nunca). */
    val maxMinutes: Int = 30,
    /** true: la macro espera a ver [template] antes de cada repetición. */
    val startOnImage: Boolean = false,
    /** Sin fin: si un paso falla (no aparece una imagen, la captura se corta…), se apunta y la
     * vuelta empieza otra vez en vez de parar la macro. */
    val keepGoing: Boolean = true,
    val templates: List<SavedTemplate> = emptyList(),
    val actions: List<Action> = emptyList()
) {
    fun validate(): String? {
        fun validTemplate(it: Template): Boolean =
            it.file.matches(Regex("[a-zA-Z0-9-]+\\.png")) &&
                it.screenWidth in 1..8192 && it.screenHeight in 1..8192 &&
                it.width >= 8 && it.height >= 8 && it.x >= 0 && it.y >= 0 &&
                it.x.toLong()+it.width <= it.screenWidth && it.y.toLong()+it.height <= it.screenHeight
        if (name.isBlank() || name.length > 100) return "El nombre debe tener entre 1 y 100 caracteres"
        if (!threshold.isFinite() || threshold !in .7..1.0) return "Similitud fuera de rango"
        if (delayMs !in 0..600_000 || intervalMs !in 0..3_600_000 || timeoutMs !in 1000..3_600_000 || maxRuns !in 1..1000 || repeatCount !in 0..1000 || maxMinutes !in 0..1440) return "Tiempos o límite de ejecuciones no válidos"
        template?.let { if(!validTemplate(it)) return "Recorte fuera de la pantalla o nombre de imagen inválido" }
        if(templates.size>100 || templates.map { it.id }.toSet().size!=templates.size || templates.any { it.id.isBlank() || it.id.length>60 || it.name.isBlank() || it.name.length>100 || !validTemplate(it.template) }) return "Biblioteca de imágenes no válida"
        var total = 0
        val ids=HashSet<String>()
        fun check(items: List<Action>, depth: Int): Boolean = depth <= 8 && items.all { a ->
            ++total <= 500 && ids.add(a.id) && a.id.isNotBlank() && a.name.length <= 100 && a.description.length <= 500 && a.durationMs in 1..(if(a.kind==Kind.WAIT_SINCE_MARK) 3_600_000L else 600_000L) && (a.kind!=Kind.WAIT_IMAGE || a.durationMs in 1_000..600_000) && a.count in 1..100 && a.waitMs in 0..3_600_000 && a.delayBeforeMs in 0..3_600_000 && a.delayAfterMs in 0..3_600_000 &&
                (a.kind!=Kind.CLOSE_ACTIVE || a.durationMs in 500..3_000) &&
                (a.kind!=Kind.OPEN_APP || (a.packageName.length<=255 && a.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))) &&
                (a.kind!=Kind.CLICK_IMAGE || (a.clickOffsetX.isFinite() && a.clickOffsetY.isFinite() &&
                    when(a.clickTarget) {
                        ClickTarget.CENTER -> true
                        ClickTarget.OFFSET -> a.clickOffsetX in -100f..100f && a.clickOffsetY in -100f..100f
                        ClickTarget.FIXED -> a.clickOffsetX in 0f..100f && a.clickOffsetY in 0f..100f
                    })) &&
                (a.kind!=Kind.IF_COLOR || (a.colorTolerance in 0..255 && a.colorX.isFinite() && a.colorY.isFinite() && a.colorX in 0f..1f && a.colorY in 0f..1f)) &&
                (a.kind != Kind.GESTURE || (a.durationMs <= 60_000 && a.points.isNotEmpty() && a.points.size <= 1000 && a.points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f })) && check(a.children, depth+1) && check(a.otherwise, depth+1)
        }
        if(!check(actions,0)) return "Secuencia no válida: revisa duración, coordenadas o profundidad"
        val templateIds=templates.map { it.id }.toSet()
        // Una condición puede quedar vacía mientras se está editando, pero una
        // referencia elegida siempre debe apuntar a una captura que aún exista.
        fun refs(items: List<Action>): Boolean=items.all { it.templateId.isEmpty() || it.templateId in templateIds } && items.all { refs(it.children) && refs(it.otherwise) }
        return if(refs(actions)) null else "Una condición usa una imagen que ya no existe"
    }

    /** Problemas que impiden ejecutar una macro terminada, pero que no deben
     * impedir guardar un borrador mientras se está editando. */
    fun executionIssues(): List<String> {
        val issues=mutableListOf<String>()
        validate()?.let { issues += it }
        val templateIds=templates.map { it.id }.toSet()
        fun check(items: List<Action>) {
            items.filter { it.enabled }.forEach { action ->
                if(action.kind in setOf(Kind.IF_IMAGE,Kind.CLICK_IMAGE,Kind.WAIT_IMAGE,Kind.WHEN_IMAGE) && action.templateId !in templateIds) {
                    issues += "«${action.name.ifBlank { kindLabelForIssue(action.kind) }}» necesita una captura"
                }
                if(action.kind==Kind.WHEN_IMAGE && action.children.none { it.enabled }) {
                    issues += "«${action.name.ifBlank { "Siempre que se vea" }}» todavía no toca nada"
                }
                if(action.kind==Kind.OPEN_APP && action.packageName==QuickMacro.NO_APP) {
                    issues += "«${action.name.ifBlank { "Abrir un juego" }}»: elige el juego"
                }
                if(action.kind==Kind.REPEAT && action.children.none { it.enabled }) {
                    issues += "«${action.name.ifBlank { "Repetir pasos" }}» no contiene pasos activos"
                }
                check(action.children)
                check(action.otherwise)
            }
        }
        if(startOnImage && template==null) issues += "Elige la imagen con la que empieza la macro"
        if(actions.none { it.enabled }) issues += "Añade al menos una acción activa" else check(actions)
        return issues.distinct()
    }
}

private fun kindLabelForIssue(kind: Kind): String = when(kind) {
    Kind.IF_IMAGE -> "SI SE VE UNA IMAGEN"
    Kind.CLICK_IMAGE -> "Detectar imagen y hacer clic"
    Kind.WAIT_IMAGE -> "Esperar hasta ver una imagen"
    Kind.WHEN_IMAGE -> "Siempre que se vea una imagen"
    else -> kind.name
}

fun parseRgbHex(value: String): Int? {
    val clean=value.trim().removePrefix("#")
    if(clean.length!=6 || clean.any { it.digitToIntOrNull(16)==null }) return null
    return (0xFF000000L or clean.toLong(16)).toInt()
}

fun rgbHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

/** Gesto relativo usado después de abrir Recientes. Quita la tarjeta central
 * sin depender de píxeles concretos ni del tamaño de la pantalla. */
fun closeRecentSwipeAction(): Action = Action(
    kind=Kind.GESTURE,
    name="Deslizar tarjeta fuera de Recientes",
    durationMs=350,
    points=listOf(Point(.5f,.70f),Point(.5f,.14f))
)

fun findAction(items: List<Action>, id: String): Action? {
    items.forEach { action ->
        if(action.id==id) return action
        findAction(action.children,id)?.let { return it }
        findAction(action.otherwise,id)?.let { return it }
    }
    return null
}

fun actionUsesTemplate(items: List<Action>, templateId: String): Boolean =
    items.any { it.templateId==templateId || actionUsesTemplate(it.children,templateId) || actionUsesTemplate(it.otherwise,templateId) }

fun countActions(items: List<Action>): Int = items.sumOf { 1+countActions(it.children)+countActions(it.otherwise) }

/** Pasos que necesitan la captura de pantalla autorizada para funcionar. */
fun Project.needsScreenCapture(): Boolean {
    fun scan(items: List<Action>): Boolean = items.any { (it.enabled && (it.kind in setOf(Kind.IF_IMAGE,Kind.CLICK_IMAGE,Kind.WAIT_IMAGE,Kind.IF_COLOR,Kind.WHEN_IMAGE) || (it.kind==Kind.REPEAT && it.templateId.isNotEmpty()))) || scan(it.children) || scan(it.otherwise) }
    return startOnImage || scan(actions)
}

fun updateAction(items: List<Action>, id: String, change: (Action) -> Action?): List<Action> = items.mapNotNull { a ->
    if (a.id == id) change(a) else a.copy(children = updateAction(a.children,id,change), otherwise = updateAction(a.otherwise,id,change))
}

fun assignTemplateToAction(items: List<Action>, actionId: String, templateId: String): List<Action> =
    updateAction(items, actionId) { it.copy(templateId = templateId) }

fun moveAction(items: List<Action>, id: String, delta: Int): List<Action> {
    val index = items.indexOfFirst { it.id == id }
    if (index >= 0) return items.toMutableList().apply { val to = (index+delta).coerceIn(0,lastIndex); add(to,removeAt(index)) }
    return items.map { it.copy(children = moveAction(it.children,id,delta), otherwise = moveAction(it.otherwise,id,delta)) }
}

fun insertAfter(items: List<Action>, id: String, action: Action): List<Action> {
    val index = items.indexOfFirst { it.id == id }
    if (index >= 0) return items.toMutableList().apply { add(index + 1, action) }
    return items.map { it.copy(children = insertAfter(it.children,id,action), otherwise = insertAfter(it.otherwise,id,action)) }
}

fun insertBefore(items: List<Action>, id: String, action: Action): List<Action> {
    val index = items.indexOfFirst { it.id == id }
    if (index >= 0) return items.toMutableList().apply { add(index, action) }
    return items.map { it.copy(children = insertBefore(it.children,id,action), otherwise = insertBefore(it.otherwise,id,action)) }
}

/** Duplica la acción en la misma rama, justo después de ella. */
fun duplicateAfter(items: List<Action>, id: String): List<Action> {
    val index = items.indexOfFirst { it.id == id }
    if (index >= 0) return items.toMutableList().apply { add(index + 1, this[index].duplicate()) }
    return items.map { it.copy(children = duplicateAfter(it.children,id), otherwise = duplicateAfter(it.otherwise,id)) }
}

/** Paso al que hay que volver tras una captura pedida para [target]
 * (un paso, "color:" + paso, o la imagen de inicio, que no es un paso). */
fun stepOfCaptureTarget(target: String?): String? = target?.removePrefix("color:")?.takeIf { it != "__inicio__" }

/** El bloque («Si se ve…» o «Repetir») que contiene el paso, o null si está en la lista principal. */
fun parentOf(items: List<Action>, id: String): Action? {
    items.forEach { a ->
        if (a.children.any { it.id == id } || a.otherwise.any { it.id == id }) return a
        parentOf(a.children, id)?.let { return it }
        parentOf(a.otherwise, id)?.let { return it }
    }
    return null
}

/** Saca el paso de su bloque y lo deja justo después del bloque. */
fun moveOutOfBlock(items: List<Action>, id: String): List<Action> {
    val parent = parentOf(items, id) ?: return items
    val action = findAction(items, id) ?: return items
    return insertAfter(updateAction(items, id) { null }, parent.id, action)
}

/** Añade el paso al final de un bloque. */
fun addInside(items: List<Action>, parentId: String, otherwise: Boolean, action: Action): List<Action> =
    updateAction(items, parentId) { if (otherwise) it.copy(otherwise = it.otherwise + action) else it.copy(children = it.children + action) }

/** ¿Es el primero / el último de su lista? */
fun siblingPosition(items: List<Action>, id: String): Pair<Boolean, Boolean>? {
    val index = items.indexOfFirst { it.id == id }
    if (index >= 0) return (index == 0) to (index == items.lastIndex)
    items.forEach { a -> (siblingPosition(a.children, id) ?: siblingPosition(a.otherwise, id))?.let { return it } }
    return null
}

/** La lista (el bloque) donde está el paso [id], o null si no está. */
fun siblingsOf(items: List<Action>, id: String): List<Action>? {
    if (items.any { it.id == id }) return items
    items.forEach { a -> (siblingsOf(a.children, id) ?: siblingsOf(a.otherwise, id))?.let { return it } }
    return null
}

/** El paso de al lado (abajo si [down], si no arriba) en el mismo bloque, o null si no hay. */
fun siblingNeighbor(items: List<Action>, id: String, down: Boolean): String? {
    val list = siblingsOf(items, id) ?: return null
    return list.getOrNull(list.indexOfFirst { it.id == id } + if (down) 1 else -1)?.id
}

/** Ids de todos los bloques (para plegar todo). */
fun containerIds(items: List<Action>): Set<String> = items.flatMap { a ->
    (if (a.kind == Kind.REPEAT || a.kind == Kind.IF_IMAGE || a.kind == Kind.IF_COLOR || a.kind == Kind.WHEN_IMAGE) listOf(a.id) else emptyList()) + containerIds(a.children) + containerIds(a.otherwise)
}.toSet()

/** Número del paso como en el editor: «3», «3.2» o «3.n1» (dentro de «✖ Si NO se ve»). */
fun stepNumber(items: List<Action>, id: String, prefix: String = ""): String? {
    items.forEachIndexed { i, a ->
        val n = "$prefix${i + 1}"
        if (a.id == id) return n
        stepNumber(a.children, id, "$n.")?.let { return it }
        stepNumber(a.otherwise, id, "$n.n")?.let { return it }
    }
    return null
}

/** Todas las reglas «⚡ Siempre que se vea» activas, estén donde estén. */
fun ruleActions(items: List<Action>): List<Action> = items.filter { it.enabled }.flatMap { a ->
    (if (a.kind == Kind.WHEN_IMAGE) listOf(a) else emptyList()) + ruleActions(a.children) + ruleActions(a.otherwise)
}

/** Quita los pasos [ids] (con lo que tengan dentro). */
fun removeActions(items: List<Action>, ids: Set<String>): List<Action> = items.filter { it.id !in ids }.map { a ->
    a.copy(children = removeActions(a.children, ids), otherwise = removeActions(a.otherwise, ids))
}

/** Todos los ids de pasos, también los de dentro de los bloques. */
fun allActionIds(items: List<Action>): Set<String> = items.flatMap { listOf(it.id) + allActionIds(it.children) + allActionIds(it.otherwise) }.toSet()

/** Los pasos que usaban alguna de las imágenes [templateIds] se quedan sin imagen. */
fun clearTemplateRefs(items: List<Action>, templateIds: Set<String>): List<Action> = items.map { a ->
    a.copy(templateId = if (a.templateId in templateIds) "" else a.templateId, children = clearTemplateRefs(a.children, templateIds), otherwise = clearTemplateRefs(a.otherwise, templateIds))
}

/** Quita imágenes de la macro aunque estén en uso (los pasos que las usaban se quedan sin imagen). */
fun Project.withoutTemplates(templateIds: Set<String>): Project {
    val removedFiles = templates.filter { it.id in templateIds }.map { it.template.file }.toSet()
    val keepStart = template?.takeIf { it.file !in removedFiles }
    return copy(templates = templates.filterNot { it.id in templateIds }, actions = clearTemplateRefs(actions, templateIds), template = keepStart, startOnImage = startOnImage && keepStart != null)
}
