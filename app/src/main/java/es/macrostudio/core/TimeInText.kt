package es.macrostudio.core

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Tiempos escritos dentro de un nombre o una nota: «Jugando (15 s)», «Esperar 4 minutos»,
 * «máximo 90 s», «Esperar 2,5 segundos».
 * Al cambiar el tiempo de un paso, [retime] cambia también esos textos para que digan lo mismo.
 */
object TimeInText {
    // Número (con coma o punto) + unidad. La unidad tiene que acabar ahí («5 sobres» no cuenta).
    private val pattern = Regex(
        "(?<![\\d.,])(\\d+(?:[.,]\\d+)?)(\\s?)(milisegundos?|minutos?|mins?|ms|segundos?|segs?|horas?|s|h|m)(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE
    )

    private fun factor(unit: String): Long = when (unit.lowercase(Locale.ROOT)) {
        "ms", "milisegundo", "milisegundos" -> 1
        "minuto", "minutos", "min", "mins", "m" -> 60_000
        "hora", "horas", "h" -> 3_600_000
        else -> 1_000
    }

    /** Milisegundos de cada tiempo escrito en el texto (para comprobar qué se cambiaría). */
    fun find(text: String): List<Long> = pattern.findAll(text).mapNotNull { toMs(it) }.toList()

    private fun toMs(m: MatchResult): Long? {
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return (v * factor(m.groupValues[3])).roundToLong()
    }

    /**
     * Cambia en [text] cada tiempo que valga lo de antes por lo de ahora.
     * [changes] = pares (antes, ahora) en milisegundos; si dos valen lo mismo, manda el primero.
     * Se hace de una sola pasada: «15 s → 20 s» y «20 s → 25 s» no se pisan.
     */
    fun retime(text: String, changes: List<Pair<Long, Long>>): String {
        val real = changes.filter { it.first != it.second && it.first > 0 && it.second > 0 }
        if (real.isEmpty() || text.isEmpty()) return text
        return pattern.replace(text) { m ->
            val ms = toMs(m) ?: return@replace m.value
            val now = real.firstOrNull { it.first == ms }?.second ?: return@replace m.value
            write(now, m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
    }

    /** Escribe [ms] al estilo del texto que había: largo («4 minutos») o corto («4 min»), con o sin espacio. */
    private fun write(ms: Long, oldNumber: String, space: String, oldUnit: String): String {
        val u = oldUnit.lowercase(Locale.ROOT)
        val long = u.length > 4 // segundo(s), minuto(s), hora(s), milisegundo(s)
        val comma = !oldNumber.contains('.')
        fun num(v: Double): String {
            val t = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
            return if (comma) t.replace('.', ',') else t
        }
        val (n, unit) = when {
            u == "ms" || u.startsWith("milisegundo") -> ms.toString() to (if (long) (if (ms == 1L) "milisegundo" else "milisegundos") else "ms")
            ms >= 3_600_000 && ms % 3_600_000 == 0L -> {
                val h = ms / 3_600_000
                h.toString() to (if (long) (if (h == 1L) "hora" else "horas") else "h")
            }
            ms >= 60_000 && ms % 60_000 == 0L -> {
                val m = ms / 60_000
                m.toString() to (if (long) (if (m == 1L) "minuto" else "minutos") else "min")
            }
            else -> {
                val s = num(ms / 1000.0)
                val short = if (u == "seg" || u == "segs") u else "s"
                s to (if (long) (if (ms == 1000L) "segundo" else "segundos") else short)
            }
        }
        val shown = if (oldUnit == oldUnit.uppercase(Locale.ROOT) && oldUnit != oldUnit.lowercase(Locale.ROOT)) unit.uppercase(Locale.ROOT) else unit
        return n + space + shown
    }
}

/**
 * Qué tiempos han cambiado al editar un paso: (antes, ahora) en milisegundos.
 * El primero manda si dos valían lo mismo. [timeoutMs] es la búsqueda por defecto de la macro.
 */
fun timeChanges(old: Action, new: Action, timeoutMs: Long): List<Pair<Long, Long>> {
    fun search(a: Action) = when (a.kind) {
        Kind.CLICK_IMAGE -> if (a.waitMs > 0) a.waitMs else timeoutMs
        Kind.WHEN_IMAGE -> if (a.waitMs > 0) a.waitMs else 3_000
        else -> a.waitMs
    }
    return listOf(
        old.durationMs to new.durationMs,
        old.delayAfterMs to new.delayAfterMs,
        old.delayBeforeMs to new.delayBeforeMs,
        search(old) to search(new)
    ).filter { it.first != it.second }
}
