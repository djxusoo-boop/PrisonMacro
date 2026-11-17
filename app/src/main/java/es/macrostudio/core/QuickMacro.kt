package es.macrostudio.core

/**
 * «⚡ Macro rápida»: la macro de siempre hecha con 4 respuestas.
 * Abre el juego, espera a que cargue, pulsa el botón de jugar, juega un rato,
 * lo cierra y espera hasta la siguiente vuelta. Sin fin; si algo falla, vuelve a empezar.
 * Las imágenes (icono, pantalla de carga, botón de jugar) se eligen después en el editor.
 */
object QuickMacro {
    const val ZOOBA = "com.wildlife.games.battle.royale.free.zooba"
    /** Paquete de relleno de «Abrir un juego» mientras no se elige (es válido, pero no es un juego). */
    const val NO_APP = "es.macrostudio"

    /** Paquete conocido a partir del nombre que escribe la persona («zooba» → Zooba). */
    fun knownPackage(game: String): String? = if (game.contains("zooba", ignoreCase = true)) ZOOBA else null

    /**
     * @param byIcon true = se abre tocando su icono (imagen); false = abriendo la app instalada.
     * @param playMs cuánto juega antes de cerrarlo.
     * @param everyMs cuánto espera con el juego cerrado hasta la siguiente vuelta.
     * @param popups añade una regla «⚡» para cerrar ventanas o anuncios (su imagen, después).
     */
    fun build(base: Project, game: String, byIcon: Boolean, playMs: Long, everyMs: Long, popups: Boolean): Project {
        val name = game.trim().ifBlank { "el juego" }.take(40)
        fun step(kind: Kind, title: String) = Action(kind = kind, name = title.take(100))
        val steps = mutableListOf<Action>()
        if (popups) steps += step(Kind.WHEN_IMAGE, "⚡ Cerrar ventanas y anuncios").copy(waitMs = 3_000)
        steps += if (byIcon) step(Kind.CLICK_IMAGE, "1. Abrir $name (tocar su icono)").copy(waitMs = 30_000, skipIfMissing = true)
        else step(Kind.OPEN_APP, "1. Abrir $name").copy(packageName = knownPackage(name) ?: NO_APP, durationMs = 80)
        steps += step(Kind.WAIT_IMAGE, "2. Esperar a que cargue").copy(durationMs = 90_000, skipIfMissing = true)
        steps += step(Kind.CLICK_IMAGE, "3. Pulsar el botón para jugar").copy(waitMs = 20_000)
        steps += step(Kind.WAIT, "4. Jugando").copy(durationMs = playMs.coerceIn(1_000, 600_000))
        steps += step(Kind.CLOSE_ACTIVE, "5. Cerrar $name").copy(durationMs = 1_000)
        // Un paso «Esperar» llega a 10 min; lo que pase de ahí va como pausa entre vueltas (hasta 1 h).
        val wait = everyMs.coerceIn(0, 600_000)
        if (wait > 0) steps += step(Kind.WAIT, "6. Esperar hasta la siguiente vuelta").copy(durationMs = wait)
        return base.copy(
            name = "$name en bucle".take(100),
            actions = steps, repeatCount = 0, keepGoing = true, maxMinutes = 0,
            intervalMs = (everyMs - wait).coerceIn(0, 3_600_000)
        )
    }
}
