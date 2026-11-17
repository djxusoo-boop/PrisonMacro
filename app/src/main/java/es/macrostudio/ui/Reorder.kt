package es.macrostudio.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.abs

/**
 * Ordenar arrastrando en una LazyColumn: se agarra el asa «⠿» de un elemento y se mueve arriba o abajo.
 * [move] cambia el orden de verdad (del elemento arrastrado al sitio del vecino) y dice si se pudo:
 * así lo que no se ordena (avisos, cabeceras, pasos de otro nivel) no se mueve.
 */
class Reorder(val list: LazyListState, private val move: (dragged: Any, over: Any) -> Boolean) {
    var dragging by mutableStateOf<Any?>(null); private set
    var offset by mutableFloatStateOf(0f); private set

    fun start(key: Any) { dragging = key; offset = 0f }
    fun end() { dragging = null; offset = 0f }

    fun drag(dy: Float) {
        val key = dragging ?: return
        offset += dy
        val info = list.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        // Cerca del borde, la lista se desplaza sola para poder llevarlo lejos.
        val top = cur.offset + offset; val bottom = top + cur.size
        if (dy > 0 && bottom > info.viewportEndOffset - 60) offset += list.dispatchRawDelta(dy)
        else if (dy < 0 && top < info.viewportStartOffset + 60) offset += list.dispatchRawDelta(dy)
        val down = offset > 0
        val other = info.visibleItemsInfo.firstOrNull { it.index == cur.index + (if (down) 1 else -1) } ?: return
        if (abs(offset) < other.size / 2f) return
        if (!move(key, other.key)) return
        // Se queda debajo del dedo: lo que se ha movido en la lista se resta de lo arrastrado.
        offset -= if (down) (other.offset + other.size) - (cur.offset + cur.size) else (other.offset - cur.offset)
    }

    /** Para el elemento entero: lo que se arrastra se mueve con el dedo y queda por encima de los demás. */
    fun itemModifier(key: Any): Modifier =
        if (dragging == key) Modifier.zIndex(1f).graphicsLayer { translationY = offset; shadowElevation = 12f; scaleX = 1.02f; scaleY = 1.02f }
        else Modifier
}

@Composable fun rememberReorder(list: LazyListState, move: (Any, Any) -> Boolean): Reorder {
    val current by rememberUpdatedState(move)
    return remember(list) { Reorder(list) { a, b -> current(a, b) } }
}

/** El asa «⠿» que se agarra para arrastrar (lista de macros). */
@Composable fun DragHandle(reorder: Reorder, key: Any, label: String) =
    DragHandle(label, key, onStart = { reorder.start(key) }, onDrag = { reorder.drag(it) }, onEnd = { reorder.end() })

/** El asa «⠿»: al arrastrarla avisa de cuánto se mueve el dedo en vertical. */
@Composable fun DragHandle(label: String, key: Any, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit) {
    Box(
        Modifier.size(width = 30.dp, height = 44.dp)
            .semantics { contentDescription = "Arrastrar para ordenar $label" }
            .pointerInput(key) {
                detectDragGestures(onDragStart = { onStart() }, onDragEnd = { onEnd() }, onDragCancel = { onEnd() }) { change, amount -> change.consume(); onDrag(amount.y) }
            },
        contentAlignment = Alignment.Center
    ) { Text("⠿", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/**
 * Ordenar arrastrando en una Column con desplazamiento (los pasos de una macro, que pueden estar
 * dentro de bloques). Cada elemento avisa de dónde está con [place]; [neighbor] dice cuál es el de
 * al lado en su mismo bloque y [move] lo cambia de sitio con él (arriba o abajo).
 */
class ColumnReorder(
    private val scroll: androidx.compose.foundation.ScrollState,
    private val neighbor: (key: String, down: Boolean) -> String?,
    private val move: (key: String, down: Boolean) -> Boolean
) {
    private val bounds = HashMap<String, Pair<Float, Int>>()
    var viewTop = 0f; var viewBottom = Float.MAX_VALUE
    var dragging by mutableStateOf<String?>(null); private set
    var offset by mutableFloatStateOf(0f); private set
    // Tras un cambio, se espera a que la lista se coloque de nuevo antes de mirar otro.
    private var waitLayout = false

    fun place(key: String, y: Float, height: Int) {
        val old = bounds[key]; bounds[key] = y to height
        if (key == dragging && old?.first != y) waitLayout = false
    }
    fun start(key: String) { dragging = key; offset = 0f; waitLayout = false }
    fun end() { dragging = null; offset = 0f; waitLayout = false }

    fun drag(dy: Float) {
        val key = dragging ?: return
        offset += dy
        val (y, h) = bounds[key] ?: return
        val top = y + offset; val bottom = top + h
        if (dy > 0 && bottom > viewBottom - 60) offset += scroll.dispatchRawDelta(dy)
        else if (dy < 0 && top < viewTop + 60) offset += scroll.dispatchRawDelta(dy)
        if (waitLayout) return
        val down = offset > 0
        val other = neighbor(key, down) ?: return
        val (oy, oh) = bounds[other] ?: return
        if (abs(offset) < oh / 2f) return
        if (!move(key, down)) return
        offset -= if (down) (oy + oh) - (y + h) else (oy - y)
        waitLayout = true
    }

    fun itemModifier(key: String): Modifier =
        Modifier.onGloballyPositioned { place(key, it.positionInRoot().y, it.size.height) }
            .then(if (dragging == key) Modifier.zIndex(1f).graphicsLayer { translationY = offset; shadowElevation = 12f; scaleX = 1.01f; scaleY = 1.01f } else Modifier)
}
