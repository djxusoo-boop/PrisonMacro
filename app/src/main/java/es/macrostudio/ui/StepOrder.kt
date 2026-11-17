package es.macrostudio.ui

import es.macrostudio.core.*

/**
 * La «orden» de un paso con imagen: qué hace cuando ve la imagen.
 * Cambiarla conserva la imagen, el sitio donde toca (centro, desplazado u otro punto),
 * el nombre que puso la persona, la nota y las esperas.
 */
enum class ImageOrder(val label: String, val short: String) {
    TAP("👆 Tocarla", "Tocarla"),
    DOUBLE("👆👆 Doble toque", "Doble toque"),
    HOLD("✋ Mantenerla pulsada", "Mantenerla pulsada"),
    ALWAYS("⚡ Tocarla siempre que salga", "Tocarla siempre que salga"),
    WAIT("👀 Solo esperar a que salga", "Solo esperar a que salga"),
    IF("❓ Si sale… y si no…", "Si sale… y si no…")
}

/** Orden actual del paso (null si no es un paso con imagen). */
fun orderOf(a: Action): ImageOrder? = when (a.kind) {
    Kind.CLICK_IMAGE -> when (a.clickStyle) { ClickStyle.DOUBLE -> ImageOrder.DOUBLE; ClickStyle.LONG -> ImageOrder.HOLD; else -> ImageOrder.TAP }
    Kind.WHEN_IMAGE -> ImageOrder.ALWAYS
    Kind.WAIT_IMAGE -> ImageOrder.WAIT
    Kind.IF_IMAGE -> ImageOrder.IF
    else -> null
}

/** El toque de su imagen que hace un bloque («⚡ siempre que salga» o «Si sale»), si lo tiene. */
private fun blockTap(a: Action): Action? =
    a.children.firstOrNull { it.kind == Kind.CLICK_IMAGE && it.templateId == a.templateId }

/** ¿Tiene dentro pasos que se perderían? (algo más que el toque a su imagen). */
fun hasInnerSteps(a: Action): Boolean = when (a.kind) {
    Kind.WHEN_IMAGE, Kind.IF_IMAGE -> a.otherwise.isNotEmpty() || a.children.size > 1 || (a.children.size == 1 && blockTap(a) == null)
    else -> false
}

/** Órdenes a las que se puede pasar sin perder nada. */
fun possibleOrders(a: Action): List<ImageOrder> {
    if (orderOf(a) == null) return emptyList()
    val inner = hasInnerSteps(a)
    return ImageOrder.values().filter { o ->
        when (o) {
            ImageOrder.TAP, ImageOrder.DOUBLE, ImageOrder.HOLD, ImageOrder.WAIT -> !inner
            ImageOrder.ALWAYS -> a.otherwise.isEmpty() // «siempre que salga» no tiene «si no»
            ImageOrder.IF -> true
        }
    }
}

/**
 * Cambia lo que hace el paso. Conserva imagen, sitio y forma del toque, nombre propio, nota y esperas.
 * [imageName] sirve para el nombre automático («Tocar «Jugar»») y para el toque de los bloques.
 * Si la orden no es posible ([possibleOrders]), devuelve el paso tal cual.
 */
fun changeOrder(a: Action, to: ImageOrder, imageName: String, timeoutMs: Long): Action {
    val from = orderOf(a) ?: return a
    if (from == to || to !in possibleOrders(a)) return a
    // De dónde sale el toque: el propio paso o el toque que tiene dentro el bloque.
    val tap = blockTap(a) ?: a
    val image = imageName.ifBlank { "la imagen" }
    fun tapStep() = ruleTap(a.templateId, image).copy(
        clickTarget = tap.clickTarget, clickOffsetX = tap.clickOffsetX, clickOffsetY = tap.clickOffsetY,
        clickStyle = tap.clickStyle, durationMs = if (tap.kind == Kind.CLICK_IMAGE) tap.durationMs else 80
    )
    // Lo que buscaba antes (para no perderlo al pasar de «tocar» a «esperar» y al revés).
    val searchMs = when (a.kind) {
        Kind.CLICK_IMAGE -> if (a.waitMs > 0) a.waitMs else timeoutMs
        Kind.WAIT_IMAGE -> a.durationMs
        else -> 30_000
    }
    val kind = when (to) {
        ImageOrder.TAP, ImageOrder.DOUBLE, ImageOrder.HOLD -> Kind.CLICK_IMAGE
        ImageOrder.ALWAYS -> Kind.WHEN_IMAGE
        ImageOrder.WAIT -> Kind.WAIT_IMAGE
        ImageOrder.IF -> Kind.IF_IMAGE
    }
    // El sitio y la forma del toque se guardan también en el paso, para poder volver atrás sin perderlos.
    val base = a.copy(
        kind = kind,
        name = if (hasAutoName(a)) autoStepName(kind, image).take(100) else a.name,
        clickTarget = tap.clickTarget, clickOffsetX = tap.clickOffsetX, clickOffsetY = tap.clickOffsetY, clickStyle = tap.clickStyle,
        untilGone = false
    )
    return when (to) {
        ImageOrder.TAP, ImageOrder.DOUBLE, ImageOrder.HOLD -> {
            val style = when (to) { ImageOrder.DOUBLE -> ClickStyle.DOUBLE; ImageOrder.HOLD -> ClickStyle.LONG; else -> ClickStyle.SINGLE }
            val tapMs = if (tap.kind == Kind.CLICK_IMAGE) tap.durationMs else 80
            base.copy(
                clickStyle = style, children = emptyList(), otherwise = emptyList(),
                durationMs = if (style == ClickStyle.LONG) maxOf(tapMs, 800) else if (tapMs >= 500) 80 else tapMs.coerceIn(1, 60_000),
                waitMs = if (a.kind == Kind.CLICK_IMAGE) a.waitMs else if (a.kind == Kind.WAIT_IMAGE) searchMs.coerceIn(1_000, 3_600_000) else 0
            )
        }
        ImageOrder.ALWAYS -> base.copy(
            durationMs = 80, waitMs = 3_000, otherwise = emptyList(),
            children = if (a.kind == Kind.IF_IMAGE) a.children else listOf(tapStep())
        )
        ImageOrder.WAIT -> base.copy(durationMs = searchMs.coerceIn(1_000, 600_000), children = emptyList(), otherwise = emptyList(), waitMs = 0)
        ImageOrder.IF -> base.copy(
            durationMs = 80, waitMs = 0,
            children = if (a.kind == Kind.WHEN_IMAGE) a.children else listOf(tapStep()),
            otherwise = a.otherwise
        )
    }
}
