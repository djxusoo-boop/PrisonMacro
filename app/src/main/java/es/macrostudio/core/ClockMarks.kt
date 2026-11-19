package es.macrostudio.core

import java.util.Locale

/**
 * El «⏱ reloj» de una macro sin fin: «⏱ Marcar la hora» apunta la hora en una marca con nombre y
 * «⏱ Esperar hasta X desde la marca» espera solo lo que falte hasta X, tarden lo que tarden los pasos
 * de en medio. Las marcas duran toda la ejecución (entre vueltas y aunque una vuelta falle y vuelva a
 * empezar): solo se borran al parar la macro.
 */
class ClockMarks {
    private val marks = HashMap<String, Long>()

    fun mark(name: String, now: Long) { marks[key(name)] = now }
    fun markedAt(name: String): Long? = marks[key(name)]
    /** Lo que falta para que pasen [durationMs] desde la marca [name] (0 si ya pasó o si no hay marca). */
    fun leftMs(name: String, durationMs: Long, now: Long): Long = leftMs(markedAt(name), durationMs, now)
    fun clear() = marks.clear()

    companion object {
        const val DEFAULT_NAME = "reloj"
        const val MAX_NAME = 40
        /** El nombre de la marca va en la nota del paso (vacía = «reloj»). */
        fun nameOf(a: Action): String = a.description.trim().take(MAX_NAME).ifBlank { DEFAULT_NAME }
        private fun key(name: String) = name.trim().lowercase(Locale.ROOT).ifBlank { DEFAULT_NAME }
        /** max(0, marca + durationMs − ahora); sin marca no se espera. */
        fun leftMs(markedAt: Long?, durationMs: Long, now: Long): Long =
            if (markedAt == null) 0L else maxOf(0L, markedAt + durationMs - now)
    }
}

/** ¿Usa la macro el «⏱ reloj»? (entonces se guarda como versión 5, que las apps antiguas no saben leer). */
fun Project.usesClock(): Boolean {
    fun scan(items: List<Action>): Boolean = items.any { it.kind == Kind.MARK_TIME || it.kind == Kind.WAIT_SINCE_MARK || scan(it.children) || scan(it.otherwise) }
    return scan(actions)
}
