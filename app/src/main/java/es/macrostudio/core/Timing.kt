package es.macrostudio.core

import java.util.Locale

/**
 * Cuánto tarda una macro, para enseñarlo en el editor mientras se crea.
 * Algunos pasos no tienen un tiempo fijo (buscar una imagen tarda lo que tarde en salir):
 * por eso se guarda lo normal ([min]) y lo máximo ([max]).
 */
data class Span(val min: Long, val max: Long) {
    operator fun plus(o: Span) = Span(min + o.min, max + o.max)
    operator fun times(n: Int) = Span(min * n, max * n)
    val exact: Boolean get() = min == max
    companion object { val ZERO = Span(0, 0); fun of(ms: Long) = Span(ms, ms) }
}

/** Lo que suele tardar una búsqueda de imagen cuando la imagen ya está en pantalla. */
const val SEARCH_MS = 400L
/** Atrás, Inicio, Recientes…: el motor espera medio segundo después. */
const val SYSTEM_MS = 500L

/** Tiempo de un paso (con sus esperas antes y después y lo que tenga dentro). */
fun stepTime(a: Action, p: Project): Span {
    if (!a.enabled) return Span.ZERO
    val own = when (a.kind) {
        Kind.WAIT, Kind.GESTURE -> Span.of(a.durationMs)
        Kind.CLICK_IMAGE -> {
            val tap = when (a.clickStyle) { ClickStyle.DOUBLE -> a.durationMs * 2 + 120; ClickStyle.LONG -> maxOf(500, a.durationMs); else -> a.durationMs }
            val limit = if (a.waitMs > 0) a.waitMs else p.timeoutMs
            Span(SEARCH_MS + tap, limit + SEARCH_MS + tap)
        }
        Kind.WAIT_IMAGE -> if (a.untilGone) Span(minOf(if (a.waitMs > 0) a.waitMs else 10_000, a.durationMs) + SEARCH_MS, a.durationMs + SEARCH_MS)
            else Span(SEARCH_MS, a.durationMs + SEARCH_MS)
        Kind.IF_IMAGE, Kind.IF_COLOR -> {
            val yes = blockTime(a.children, p); val no = blockTime(a.otherwise, p)
            Span(SEARCH_MS + minOf(yes.min, no.min), SEARCH_MS + maxOf(yes.max, no.max))
        }
        Kind.REPEAT -> {
            val body = blockTime(a.children, p)
            // «Hasta ver una imagen»: puede salir a la primera o dar todas las vueltas.
            if (a.templateId.isNotEmpty()) Span(SEARCH_MS, (body.max + SEARCH_MS) * a.count) else body * a.count
        }
        // Las reglas ⚡ no van en orden: solo suman cuando aparece su imagen.
        Kind.WHEN_IMAGE, Kind.STOP -> Span.ZERO
        Kind.MARK_TIME -> Span.ZERO
        // Espera solo lo que falte desde la marca: de nada a todo el tiempo.
        Kind.WAIT_SINCE_MARK -> Span(0, a.durationMs)
        Kind.CLOSE_ACTIVE -> Span.of(a.durationMs + 700)
        else -> Span.of(SYSTEM_MS)
    }
    return own + Span.of(a.delayBeforeMs + a.delayAfterMs)
}

fun blockTime(items: List<Action>, p: Project): Span = items.fold(Span.ZERO) { acc, a -> acc + stepTime(a, p) }

/** Una vuelta entera de la macro (con la espera del principio). */
fun roundTime(p: Project): Span = Span.of(p.delayMs) + blockTime(p.actions, p)

/** «0,5 s», «12 s», «5 min 32 s», «1 h 05 min». */
fun durationText(ms: Long): String = when {
    ms < 60_000 -> if (ms % 1000 == 0L || ms >= 10_000) "${(ms + 500) / 1000} s" else String.format(Locale("es"), "%.1f s", ms / 1000.0)
    ms < 3_600_000 -> { val s = (ms + 500) / 1000; if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s" }
    else -> { val m = (ms + 30_000) / 60_000; "${m / 60} h ${"%02d".format(m % 60)} min" }
}

/** «12 s» o «≈0,5 s (hasta 1 min)». */
/** Versión corta para la lista de pasos: solo lo normal («≈20 s»), sin el máximo. */
fun shortSpan(s: Span): String = if (s.exact) durationText(s.min) else "≈${durationText(s.min)}"

fun spanText(s: Span): String = if (s.exact) durationText(s.min) else "≈${durationText(s.min)} (hasta ${durationText(s.max)})"

/** Resumen para la cabecera del editor: cada vuelta y el total. */
fun totalText(p: Project): String {
    val round = roundTime(p)
    val parts = mutableListOf("⏱ Cada vuelta: ${shortSpan(round)}")
    when (p.repeatCount) {
        1 -> Unit
        0 -> parts += if (p.maxMinutes > 0) "sin fin (se para sola a los ${p.maxMinutes} min)" else "sin fin"
        else -> {
            val n = p.repeatCount
            parts += "Total ${n} vueltas: ${shortSpan(round * n + Span.of(p.intervalMs * (n - 1)))}"
        }
    }
    return parts.joinToString(" · ")
}
