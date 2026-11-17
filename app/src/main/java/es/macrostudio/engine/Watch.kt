package es.macrostudio.engine

import android.os.SystemClock

/**
 * «👁 Modo ver»: lo que la macro está haciendo, para enseñarlo en el panel flotante
 * (paso actual, cuenta atrás de las esperas, siguiente paso y última búsqueda de imagen).
 * Solo lo lee el panel; el motor lo rellena aunque el modo esté apagado.
 */
object Watch {
    @Volatile var enabled = false
    @Volatile var step = ""
    @Volatile var next = ""
    @Volatile var waitUntil = 0L
    @Volatile var waitLabel = ""
    @Volatile var lastSearch = ""
    /** Imagen que se está buscando ahora (archivo y nombre) y cuándo: el panel la enseña medio transparente. */
    @Volatile var searchingFile = ""
    @Volatile var searchingName = ""
    @Volatile var searchingAt = 0L
    /** true = la busca una regla «⚡ Siempre que se vea» (vigilando), false = un paso. */
    @Volatile var searchingRule = false

    fun searching(file: String, name: String, rule: Boolean = false) {
        // Lo que busca un paso manda: las reglas no lo tapan mientras dura.
        if (rule && !searchingRule && SystemClock.elapsedRealtime() - searchingAt < 1_500) return
        searchingFile = file; searchingName = name; searchingRule = rule; searchingAt = SystemClock.elapsedRealtime()
    }
    /** La imagen de la última búsqueda, si fue hace poco (si no, no se enseña). */
    fun currentSearch(): String? = searchingFile.takeIf { it.isNotBlank() && SystemClock.elapsedRealtime() - searchingAt < 3_000 }

    fun waiting(label: String, ms: Long) {
        if (ms <= 0) { waitUntil = 0; return }
        waitLabel = label; waitUntil = SystemClock.elapsedRealtime() + ms
    }

    fun clear() { step = ""; next = ""; waitUntil = 0; waitLabel = ""; lastSearch = ""; searchingFile = ""; searchingName = ""; searchingAt = 0 }

    /** «4:32» o «12 s». */
    fun remaining(): String? {
        val left = waitUntil - SystemClock.elapsedRealtime()
        if (waitUntil == 0L || left <= 0) return null
        val s = (left + 999) / 1000
        return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "$s s"
    }

    /** Texto del recuadro de información. */
    fun text(running: Boolean, paused: Boolean): String {
        if (!running) return es.macrostudio.core.Studio.stopInfo.takeIf { it.isNotBlank() }?.let { "$it\nPulsa ▶ para empezar otra vez." }
            ?: "👁 Modo ver activado\nPulsa ▶ y verás aquí cada paso, las esperas y los toques."
        val lines = mutableListOf<String>()
        lines += (if (paused) "Ⅱ En pausa · " else "▶ ") + step.ifBlank { "Empezando…" }
        remaining()?.let { lines += "⏱ $waitLabel: faltan $it" }
        if (next.isNotBlank()) lines += "➡ Siguiente: $next"
        if (lastSearch.isNotBlank()) lines += "🔍 $lastSearch"
        return lines.joinToString("\n")
    }
}
