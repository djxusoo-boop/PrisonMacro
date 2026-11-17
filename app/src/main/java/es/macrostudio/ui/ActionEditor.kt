package es.macrostudio.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import es.macrostudio.core.*

/** Pasos que solo necesitan su imagen: al elegirla en un paso nuevo, se guardan solos. */
private val quickSaveKinds = setOf(Kind.CLICK_IMAGE, Kind.WAIT_IMAGE, Kind.WHEN_IMAGE)

private val packageRegex = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** Editor a pantalla completa de un paso. */
@Composable fun ActionEditor(c: AppController, a: Action) {
    val isDraft = c.draftId == a.id
    val info = kindInfo(a.kind)
    var name by remember(a.id) { mutableStateOf(a.name) }
    var description by remember(a.id) { mutableStateOf(a.description) }
    // Los tiempos que había cuando se escribió el nombre o la nota: si cambian, el texto cambia con ellos
    // («Jugando (15 s)» → «Jugando (20 s)»). Siempre se calcula desde lo escrito, así no se pisa al teclear.
    var nameAt by remember(a.id) { mutableStateOf(a) }
    var descAt by remember(a.id) { mutableStateOf(a) }
    var templateId by remember(a.id, a.templateId) { mutableStateOf(a.templateId) }
    // Duración: en segundos para esperas, en milisegundos para toques.
    val durationInSeconds = a.kind in setOf(Kind.WAIT, Kind.WAIT_IMAGE, Kind.CLOSE_ACTIVE)
    var duration by remember(a.id) { mutableStateOf(if (durationInSeconds) secondsText(a.durationMs) else a.durationMs.toString()) }
    var count by remember(a.id) { mutableStateOf(a.count.toString()) }
    var x by remember(a.id, a.points) { mutableStateOf(pctText(a.points.firstOrNull()?.x ?: .5f)) }
    var y by remember(a.id, a.points) { mutableStateOf(pctText(a.points.firstOrNull()?.y ?: .5f)) }
    var packageName by remember(a.id) { mutableStateOf(a.packageName.takeIf { a.kind == Kind.OPEN_APP && it != "es.macrostudio" } ?: "") }
    var clickTarget by remember(a.id, a.clickTarget) { mutableStateOf(a.clickTarget) }
    var clickStyle by remember(a.id) { mutableStateOf(a.clickStyle) }
    var clickX by remember(a.id, a.clickOffsetX) { mutableStateOf(pctText(a.clickOffsetX / 100f)) }
    var clickY by remember(a.id, a.clickOffsetY) { mutableStateOf(pctText(a.clickOffsetY / 100f)) }
    var colorHex by remember(a.id) { mutableStateOf(rgbHex(a.targetColor)) }
    var tolerance by remember(a.id) { mutableStateOf(a.colorTolerance.toString()) }
    var colorMode by remember(a.id) { mutableStateOf(a.colorMode) }
    var colorX by remember(a.id) { mutableStateOf(pctText(a.colorX)) }
    var colorY by remember(a.id) { mutableStateOf(pctText(a.colorY)) }
    var skipIfMissing by remember(a.id) { mutableStateOf(a.skipIfMissing) }
    var beforeSec by remember(a.id) { mutableStateOf(secondsText(a.delayBeforeMs)) }
    var afterSec by remember(a.id) { mutableStateOf(secondsText(a.delayAfterMs)) }
    var waitSec by remember(a.id) { mutableStateOf(secondsText(if (a.waitMs > 0) a.waitMs else Studio.project.timeoutMs)) }
    // «⚡ Siempre que se vea»: qué hace después y cuánto descansa antes de poder volver a actuar.
    var afterRule by remember(a.id) { mutableStateOf(a.afterRule) }
    var restSec by remember(a.id) { mutableStateOf(secondsText(if (a.waitMs > 0) a.waitMs else 3000)) }
    // «Esperar a que ya no salga»: cuánto rato seguido sin verla.
    var untilGone by remember(a.id) { mutableStateOf(a.untilGone) }
    var goneSec by remember(a.id) { mutableStateOf(secondsText(if (a.waitMs > 0) a.waitMs else es.macrostudio.engine.Engine.GONE_DEFAULT_MS)) }
    // Toque (1 punto) o deslizamiento (2 puntos). Una trayectoria grabada (>2) se conserva tal cual.
    val recordedPath = a.kind == Kind.GESTURE && a.points.size > 2
    var swipe by remember(a.id, a.points) { mutableStateOf(a.points.size == 2) }
    var x2 by remember(a.id, a.points) { mutableStateOf(pctText(a.points.getOrNull(1)?.x ?: .5f)) }
    var y2 by remember(a.id, a.points) { mutableStateOf(pctText(a.points.getOrNull(1)?.y ?: .2f)) }
    // Repetir: un número fijo de veces o hasta que se vea una imagen.
    var untilImage by remember(a.id) { mutableStateOf(a.kind == Kind.REPEAT && a.templateId.isNotEmpty()) }
    // Paso nuevo con imagen: lo primero que hace falta es la imagen, así que se abre ya la elección.
    // (también al abrir un paso que aún no la tiene, por ejemplo los de «⚡ Macro rápida»).
    val hadNoImage = remember(a.id) { Studio.project.templates.none { it.id == a.templateId } }
    var picking by remember { mutableStateOf(hadNoImage && a.kind in imageKinds) }
    var more by remember(a.id) { mutableStateOf(false) }
    // Al elegir o capturar la imagen, el paso se nombra solo («Tocar «Jugar»») si aún
    // tenía el nombre genérico o uno puesto así automáticamente.
    LaunchedEffect(templateId) {
        val img = Studio.project.templates.firstOrNull { it.id == templateId }?.name ?: return@LaunchedEffect
        if (hasAutoName(a, name)) name = autoStepName(a.kind, img)
    }
    // «Abrir un juego» sin juego: se abre ya la lista de apps (y al elegir, queda hecho).
    val hadNoApp = remember(a.id) { a.kind == Kind.OPEN_APP && (a.packageName.isBlank() || a.packageName == QuickMacro.NO_APP) }
    var choosingApp by remember { mutableStateOf(hadNoApp) }
    /** Para qué se elige una captura guardada: el id del paso (recortar imagen) o COLOR_PREFIX+id (elegir color). */
    var choosingShot by remember { mutableStateOf<String?>(null) }

    fun pct(t: String) = t.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
    val d: Long? = if (durationInSeconds) parseSeconds(duration) else duration.toLongOrNull()
    val validDuration = d != null && when (a.kind) {
        Kind.GESTURE -> d in 1..60_000
        Kind.CLICK_IMAGE -> d in 1..60_000 && (clickStyle != ClickStyle.LONG || d >= 500)
        Kind.WAIT_IMAGE -> d in 1_000..600_000
        Kind.CLOSE_ACTIVE -> d in 500..3_000
        else -> d in 1..600_000
    }
    val cnt = count.toIntOrNull()?.takeIf { it in 1..100 }
    val px = pct(x)?.takeIf { it in 0f..100f }; val py = pct(y)?.takeIf { it in 0f..100f }
    val cx = pct(clickX); val cy = pct(clickY)
    val validClick = a.kind != Kind.CLICK_IMAGE || when (clickTarget) {
        ClickTarget.CENTER -> true
        ClickTarget.OFFSET -> cx != null && cy != null && cx in -100f..100f && cy in -100f..100f
        ClickTarget.FIXED -> cx != null && cy != null && cx in 0f..100f && cy in 0f..100f
    }
    val color = parseRgbHex(colorHex); val tol = tolerance.toIntOrNull()?.takeIf { it in 0..255 }
    val colX = pct(colorX)?.takeIf { it in 0f..100f }; val colY = pct(colorY)?.takeIf { it in 0f..100f }
    val validColor = a.kind != Kind.IF_COLOR || (color != null && tol != null && (colorMode == SearchMode.ANYWHERE || (colX != null && colY != null)))
    val px2 = pct(x2)?.takeIf { it in 0f..100f }; val py2 = pct(y2)?.takeIf { it in 0f..100f }
    val validPoint = a.kind != Kind.GESTURE || recordedPath || (px != null && py != null && (!swipe || (px2 != null && py2 != null)))
    val waitMs = parseSeconds(waitSec)?.takeIf { it in 1000..3_600_000 }
    val validWait = a.kind != Kind.CLICK_IMAGE || waitMs != null
    val restMs = parseSeconds(restSec)?.takeIf { it in 500..3_600_000 }
    val validRest = a.kind != Kind.WHEN_IMAGE || restMs != null
    val validPackage = a.kind != Kind.OPEN_APP || packageName.matches(packageRegex)
    val beforeMs = parseSeconds(beforeSec)?.takeIf { it in 0..3_600_000 }
    val afterMs = parseSeconds(afterSec)?.takeIf { it in 0..3_600_000 }
    val needsImage = a.kind in imageKinds || (a.kind == Kind.REPEAT && untilImage)
    /** El paso con los tiempos tal como están escritos ahora (los que estén mal, como estaban). */
    val timesNow = a.copy(
        durationMs = if (validDuration) d!! else a.durationMs,
        delayBeforeMs = beforeMs ?: a.delayBeforeMs, delayAfterMs = afterMs ?: a.delayAfterMs,
        waitMs = when {
            a.kind == Kind.CLICK_IMAGE && waitMs != null -> if (waitMs == Studio.project.timeoutMs) 0 else waitMs
            a.kind == Kind.WHEN_IMAGE && restMs != null -> restMs
            a.kind == Kind.WAIT_IMAGE -> if (untilGone) parseSeconds(goneSec)?.coerceIn(1_000, 600_000) ?: es.macrostudio.engine.Engine.GONE_DEFAULT_MS else 0
            else -> a.waitMs
        }
    )
    fun shownName() = TimeInText.retime(name, timeChanges(nameAt, timesNow, Studio.project.timeoutMs)).take(100)
    fun shownDescription() = TimeInText.retime(description, timeChanges(descAt, timesNow, Studio.project.timeoutMs)).take(500)
    val problems = buildList {
        if (name.isBlank()) add("Escribe un nombre")
        if (!validDuration) add(if (durationInSeconds) "Revisa el tiempo" else "Revisa la duración")
        if (a.kind == Kind.REPEAT && cnt == null) add("Las repeticiones van de 1 a 100")
        if (!validPoint) add("X e Y van de 0 a 100")
        if (!validClick) add("Revisa el punto del toque")
        if (!validWait) add("El tiempo de búsqueda va de 1 s a 1 h")
        if (!validRest) add("El descanso va de 0,5 s a 1 h")
        if (!validColor) add("Revisa el color, la tolerancia o el punto")
        if (!validPackage) add("Elige la aplicación")
        if (beforeMs == null || afterMs == null) add("Revisa las esperas (0 a 3600 s)")
        if (needsImage && templateId.isBlank()) add("Elige o captura una imagen")
    }

    /** Construye el paso con lo escrito. Con [lenient], usa los valores
     * originales donde haya errores (para guardar antes de salir de la app). */
    fun build(lenient: Boolean, autoTap: Boolean = true): Action? {
        if (!lenient && problems.isNotEmpty()) return null
        return a.copy(
            name = shownName().trim().ifBlank { a.name }, description = shownDescription().trim(),
            templateId = if (a.kind == Kind.REPEAT && !untilImage) "" else templateId,
            durationMs = timesNow.durationMs,
            count = cnt ?: a.count,
            points = when {
                a.kind != Kind.GESTURE || recordedPath || px == null || py == null -> a.points
                swipe && px2 != null && py2 != null -> listOf(Point(px / 100, py / 100), Point(px2 / 100, py2 / 100))
                swipe -> a.points
                else -> listOf(Point(px / 100, py / 100))
            },
            skipIfMissing = skipIfMissing,
            delayBeforeMs = timesNow.delayBeforeMs, delayAfterMs = timesNow.delayAfterMs, waitMs = timesNow.waitMs,
            afterRule = afterRule,
            untilGone = a.kind == Kind.WAIT_IMAGE && untilGone,
            // Una regla nueva ya sabe tocar su imagen: no hace falta añadir el paso a mano.
            children = when {
                autoTap && a.kind == Kind.WHEN_IMAGE && a.children.isEmpty() && templateId.isNotBlank() -> listOf(ruleTap(templateId))
                // Regla sencilla con imagen nueva: su toque pasa a tocar la imagen nueva.
                isSimpleRule(a) && templateId.isNotBlank() && templateId != a.templateId -> listOf(a.children[0].copy(templateId = templateId, name = ruleTap(templateId).name))
                else -> a.children
            },
            packageName = if (validPackage && a.kind == Kind.OPEN_APP) packageName else a.packageName,
            clickTarget = if (validClick) clickTarget else a.clickTarget, clickStyle = clickStyle,
            clickOffsetX = if (validClick && clickTarget != ClickTarget.CENTER) cx!! else a.clickOffsetX,
            clickOffsetY = if (validClick && clickTarget != ClickTarget.CENTER) cy!! else a.clickOffsetY,
            targetColor = color ?: a.targetColor, colorTolerance = tol ?: a.colorTolerance, colorMode = colorMode,
            colorX = colX?.div(100) ?: a.colorX, colorY = colY?.div(100) ?: a.colorY
        )
    }
    /** Lo que ha cambiado solo al cambiar un tiempo («el nombre y la nota»), para avisar al guardar. */
    var retimed by remember { mutableStateOf("") }
    fun store(edited: Action): Boolean {
        // El nombre del paso y la nota ya llegan con el tiempo nuevo; aquí, también el nombre de la macro
        // («Zooba cada 5 min» → «Zooba cada 6 min»).
        val p = Studio.project
        val macroName = TimeInText.retime(p.name, timeChanges(a, edited, p.timeoutMs)).take(100)
        val parts = listOfNotNull(
            "el nombre".takeIf { shownName() != name }, "la nota".takeIf { shownDescription() != description },
            "el nombre de la macro".takeIf { macroName != p.name }
        )
        retimed = if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " y " + parts.last()
        return Studio.update(p.copy(name = macroName, actions = updateAction(p.actions, a.id) { edited }))
    }
    /** Guarda lo escrito y sale a otra pantalla (captura, galería, punto). */
    fun leaveAnd(then: () -> Unit) { build(true)?.let { store(it) }; c.draftId = null; c.editingId = null; then() }
    /** Guarda el bloque (sin añadir solo «Tocar esta imagen») y va al juego a grabar sus toques. */
    fun recordInside(otherwise: Boolean) { build(true, autoTap = false)?.let { store(it) }; c.draftId = null; c.editingId = null; c.recordInto(a.id, otherwise) }

    /** Cambia lo que hace el paso («la orden») sin tocar la imagen ni el sitio del toque. */
    fun changeTo(o: ImageOrder) {
        if (a.kind == Kind.CLICK_IMAGE && o in setOf(ImageOrder.TAP, ImageOrder.DOUBLE, ImageOrder.HOLD)) {
            // Mismo paso, otra forma de tocar: se cambia aquí mismo.
            clickStyle = when (o) { ImageOrder.DOUBLE -> ClickStyle.DOUBLE; ImageOrder.HOLD -> ClickStyle.LONG; else -> ClickStyle.SINGLE }
            val ms = duration.toLongOrNull() ?: 80
            if (o == ImageOrder.HOLD && ms < 500) duration = "800"
            if (o != ImageOrder.HOLD && ms >= 500) duration = "80"
            return
        }
        val current = build(true, autoTap = false) ?: return
        val image = Studio.project.templates.firstOrNull { it.id == current.templateId }?.name ?: ""
        val changed = changeOrder(current, o, image, Studio.project.timeoutMs)
        // (sin cambiar tiempos en los textos: aquí cambia el tipo de paso, no un tiempo)
        if (Studio.update(Studio.project.copy(actions = updateAction(Studio.project.actions, a.id) { changed })))
            c.toast("✓ Ahora: ${o.short}. La imagen y el sitio del toque siguen igual.")
    }

    /** Elegir una imagen ya hecha. En un paso nuevo de imagen, con eso basta: se guarda solo y se cierra. */
    fun chooseImage(saved: SavedTemplate) {
        templateId = saved.id; picking = false
        if (hadNoImage && a.kind in quickSaveKinds) {
            if (hasAutoName(a, name)) name = autoStepName(a.kind, saved.name)
            build(true)?.let { if (store(it)) { c.closeActionEditor(discardDraft = false); c.toast(if (isDraft) "✓ Paso añadido: ${it.name}" else "✓ «${it.name}» ya tiene su imagen") } }
        }
    }
    // Lo que no es imprescindible va plegado en «⚙ Más opciones», con un resumen de lo que hay cambiado.
    val moreSummary = buildList {
        if (a.kind == Kind.CLICK_IMAGE) {
            if (clickTarget != ClickTarget.CENTER) add("toca ${clickTargetLabel(clickTarget)}")
            if (clickStyle != ClickStyle.SINGLE) add(clickStyleLabel(clickStyle))
            if (skipIfMissing) add("si no sale, sigue")
            parseSeconds(waitSec)?.takeIf { it != Studio.project.timeoutMs }?.let { add("la busca ${readableDuration(it)}") }
        }
        if (a.kind == Kind.WAIT_IMAGE && skipIfMissing && !untilGone) add("si no sale, sigue")
        if (a.kind == Kind.WHEN_IMAGE && afterRule != AfterRule.CONTINUE) add("después ${afterRuleLabel(afterRule)}")
        beforeMs?.takeIf { it > 0 }?.let { add("espera ${readableDuration(it)} antes") }
        afterMs?.takeIf { it > 0 }?.let { add("espera ${readableDuration(it)} después") }
        if (description.isNotBlank()) add("con nota")
    }

    Dialog(onDismissRequest = { c.closeActionEditor(discardDraft = true) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullScreenDialogWindow()
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { c.closeActionEditor(discardDraft = true) }) { Icon(Icons.Default.Close, "Cancelar") }
                    Text("${info.icon}  ${info.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Button(onClick = { build(false)?.let { if (store(it)) { c.closeActionEditor(discardDraft = false); if (retimed.isNotEmpty()) c.toast("⏱ Tiempo cambiado también en $retimed") } } }, enabled = problems.isEmpty(), modifier = Modifier.padding(end = 8.dp)) { Text("✓ Guardar") }
                }
                val shown = problems.filter { !(it.startsWith("Elige o captura") && needsImage) }
                if (shown.isNotEmpty()) Text("⚠ " + shown.joinToString(" · "), color = Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val imageSection: @Composable () -> Unit = { ImageSection(
                        templateId,
                        title = when (a.kind) { Kind.CLICK_IMAGE -> "Imagen que hay que tocar"; Kind.WHEN_IMAGE -> "Imagen que hay que tocar siempre que salga"; Kind.WAIT_IMAGE -> "Imagen que hay que esperar"; Kind.REPEAT -> "Repetir hasta que se vea esta imagen"; else -> "Imagen que hay que comprobar" },
                        onPick = { picking = true },
                        onRecrop = { id -> leaveAnd { c.recropImage(id, a.id) } },
                        onTest = { id -> leaveAnd { c.testImage(id) } }
                    ) }
                    if (needsImage && a.kind != Kind.REPEAT) imageSection()
                    // «La orden»: qué hace cuando ve la imagen. Se cambia sin perder la imagen ni el sitio del toque.
                    orderOf(a.copy(clickStyle = clickStyle))?.let { current ->
                        SectionTitle("¿Qué hace cuando ve la imagen?")
                        ChoiceChips(possibleOrders(a).map { it to it.label }, current) { if (it != current) changeTo(it) }
                        if (hasInnerSteps(a)) Hint("Tiene pasos dentro: para «Tocarla» o «Esperar», saca antes esos pasos.")
                    }

                    // ---------- Lo imprescindible de cada tipo de paso ----------
                    when (a.kind) {
                        Kind.WAIT_IMAGE -> {
                            ChoiceChips(listOf(false to "👀 Hasta que salga", true to "🙈 Hasta que ya no salga"), untilGone) { untilGone = it }
                            if (untilGone) {
                                Hint("Sirve para «hasta que no quede ninguna caja por abrir»: mientras espera, tus reglas ⚡ siguen tocando. Sigue cuando lleva un rato sin verla.")
                                SecondsField("Rato seguido sin verla (segundos)", goneSec, listOf("5", "10", "15", "20")) { goneSec = it }
                            }
                            SecondsField("Esperar como máximo (segundos)", duration, listOf("10", "30", "60", "120", "300")) { duration = it }
                        }
                        Kind.WHEN_IMAGE -> Hint("Mientras la macro funciona, en cuanto salga esta imagen la toca, esté donde esté la macro.")
                        Kind.IF_IMAGE, Kind.IF_COLOR -> {
                            Hint("Después de guardar, pon los pasos en «✔ Si se ve» y «✖ Si NO se ve» en la lista, o grábalos:")
                            RecordInsideButton("● Grabar toques para «✔ Si se ve»") { recordInside(false) }
                            RecordInsideButton("● Grabar toques para «✖ Si NO se ve»") { recordInside(true) }
                        }
                        Kind.REPEAT -> {
                            ChoiceChips(listOf(false to "Un número de veces", true to "Hasta ver una imagen"), untilImage) { untilImage = it }
                            if (untilImage) imageSection()
                            OutlinedTextField(count, { count = it.filter(Char::isDigit).take(3) }, label = { Text(if (untilImage) "Como máximo (1–100 veces)" else "Cuántas veces (1–100)") }, singleLine = true)
                            RecordInsideButton("● Grabar los toques que se repiten") { recordInside(false) }
                        }
                        Kind.WAIT -> SecondsField("Tiempo (segundos, o «4 min»)", duration, listOf("1", "2", "5", "10", "30", "60", "240", "300", "600")) { duration = it }
                        Kind.GESTURE -> {
                            if (!recordedPath) {
                                ChoiceChips(listOf(false to "👆 Tocar", true to "↕ Deslizar"), swipe) { swipe = it }
                                val marked = !(x == "50" && y == "50")
                                Text(if (!marked) "⚠ Todavía no has marcado dónde tocar" else if (swipe) "✓ Deslizamiento marcado" else "✓ Punto marcado", color = if (marked) MaterialTheme.colorScheme.primary else Warning, style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { leaveAnd { c.pickPoint(a.id, swipe = swipe) } }, modifier = Modifier.fillMaxWidth()) { Text(if (swipe) "👆 Dibujarlo con el dedo en el juego" else "👆 Marcar el punto en el juego") }
                                OutlinedButton(onClick = { choosingShot = (if (swipe) Studio.SWIPE_PREFIX else Studio.POINT_PREFIX) + a.id }, modifier = Modifier.fillMaxWidth()) { Text("🗂 Marcarlo en una captura guardada") }
                            } else Text("Trayectoria grabada con ${a.points.size} puntos.")
                        }
                        Kind.OPEN_APP -> {
                            if (packageName.isNotBlank()) Text("✓ Abre: ${appLabel(c.activity, packageName)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = { choosingApp = true }, modifier = Modifier.fillMaxWidth()) { Text(if (packageName.isBlank()) "📱 Elegir el juego" else "📱 Cambiar de juego") }
                            OutlinedTextField(packageName, { packageName = it.trim().take(255) }, label = { Text("Paquete (si no sale en la lista)") }, placeholder = { Text("com.ejemplo.juego") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        Kind.CLOSE_ACTIVE -> {
                            Text("En este aparato: ${es.macrostudio.engine.ThisDevice.label(c.activity)}", style = MaterialTheme.typography.bodyMedium)
                            Hint("Se cambia en ⚙ Ajustes › 🖥 Este aparato.")
                        }
                        Kind.IF_COLOR -> Unit
                        else -> Unit
                    }
                    if (a.kind == Kind.IF_COLOR) {
                        Button(onClick = { leaveAnd { c.pickColor(a.id) } }, modifier = Modifier.fillMaxWidth()) { Text("🎯 Elegir el color tocándolo en el juego") }
                        OutlinedButton(onClick = { choosingShot = Studio.COLOR_PREFIX + a.id }, modifier = Modifier.fillMaxWidth()) { Text("🗂 Elegirlo en una captura guardada") }
                        ColorSection(colorHex, tolerance, colorMode, colorX, colorY, { colorHex = it }, { tolerance = it }, { colorMode = it }, { colorX = it }, { colorY = it })
                    }

                    // ---------- ⚙ Más opciones (plegado) ----------
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).clickable { more = !more }.padding(12.dp)) {
                        Text((if (more) "▲" else "▼") + "  ⚙ Más opciones", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Hint(if (moreSummary.isEmpty()) (if (a.kind == Kind.WAIT) "Nombre y nota." else "Nombre, esperas y detalles.") + " No hace falta tocarlas." else moreSummary.joinToString(" · "))
                    }
                    if (more) {
                        OutlinedTextField(shownName(), { name = it.take(100); nameAt = timesNow }, label = { Text("Nombre del paso") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (TimeInText.find(name + " " + description).isNotEmpty()) Hint("⏱ El tiempo escrito en el nombre y en la nota cambia solo cuando cambias el tiempo.")
                        when (a.kind) {
                            Kind.CLICK_IMAGE -> {
                                SectionTitle("Dónde tocar al encontrarla")
                                ChoiceChips(listOf(ClickTarget.CENTER to "En el centro", ClickTarget.OFFSET to "Desplazado", ClickTarget.FIXED to "En otro punto de la pantalla"), clickTarget) {
                                    if (it != clickTarget) { clickX = if (it == ClickTarget.FIXED) "50" else "0"; clickY = clickX }
                                    clickTarget = it
                                }
                                if (clickTarget == ClickTarget.OFFSET) {
                                    Hint("Distancia desde el centro, en % del tamaño de la imagen (-100 a 100).")
                                    XYFields(clickX, clickY, { clickX = it }, { clickY = it })
                                }
                                if (clickTarget == ClickTarget.FIXED) {
                                    XYFields(clickX, clickY, { clickX = it }, { clickY = it })
                                    OutlinedButton(onClick = { leaveAnd { c.pickPoint(a.id) } }, modifier = Modifier.fillMaxWidth()) { Text("👆 Marcar el punto en el juego") }
                                    OutlinedButton(onClick = { choosingShot = Studio.POINT_PREFIX + a.id }, modifier = Modifier.fillMaxWidth()) { Text("🗂 Marcarlo en una captura guardada") }
                                }
                                // (un toque, doble o mantener pulsado se elige arriba, en «¿Qué hace cuando ve la imagen?»)
                                if (clickStyle == ClickStyle.LONG) OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Duración de la pulsación (ms, mínimo 500)") }, singleLine = true)
                                SectionTitle("Si la imagen no sale")
                                SecondsField("Buscarla durante (segundos)", waitSec, listOf("2", "5", "10", "30", "60")) { waitSec = it }
                                MissingChoice(skipIfMissing) { skipIfMissing = it }
                            }
                            Kind.WAIT_IMAGE -> if (!untilGone) {
                                SectionTitle("Si no sale a tiempo")
                                MissingChoice(skipIfMissing) { skipIfMissing = it }
                            } else Hint("Si al llegar al máximo aún sale, sigue igualmente.")
                            Kind.WHEN_IMAGE -> {
                                SectionTitle("Después de tocarla…")
                                ChoiceChips(listOf(
                                    AfterRule.CONTINUE to "Seguir donde estaba",
                                    AfterRule.SKIP_STEP to "Saltar el paso que esperaba",
                                    AfterRule.EXIT_LOOP to "Salir del bucle",
                                    AfterRule.RESTART to "Empezar la vuelta de nuevo",
                                    AfterRule.STOP to "Parar la macro"
                                ), afterRule) { afterRule = it }
                                SecondsField("Descanso antes de volver a tocarla (segundos)", restSec, listOf("1", "3", "5", "10", "30")) { restSec = it }
                                RecordInsideButton("● Grabar más toques para cuando salga") { recordInside(false) }
                            }
                            Kind.GESTURE -> if (!recordedPath) {
                                Hint("En % de la pantalla: 0 = arriba/izquierda, 100 = abajo/derecha.")
                                if (swipe) {
                                    Text("Desde", style = MaterialTheme.typography.labelLarge); XYFields(x, y, { x = it }, { y = it })
                                    Text("Hasta", style = MaterialTheme.typography.labelLarge); XYFields(x2, y2, { x2 = it }, { y2 = it })
                                    OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Duración del deslizamiento (ms)") }, singleLine = true)
                                } else {
                                    XYFields(x, y, { x = it }, { y = it })
                                    OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Duración del toque (ms; 600 o más = pulsación larga)") }, singleLine = true)
                                }
                            } else OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Duración del gesto (ms)") }, singleLine = true)
                            Kind.CLOSE_ACTIVE -> SecondsField("Espera para que se abra Recientes (0,5–3 s)", duration, listOf("0,5", "1", "2")) { duration = it }
                            else -> Unit
                        }
                        if (a.kind != Kind.STOP && a.kind != Kind.WHEN_IMAGE && a.kind != Kind.WAIT) {
                            SectionTitle("⏱ Esperas")
                            SecondsField("Esperar antes (segundos)", beforeSec, listOf("0", "1", "2", "5", "15", "40")) { beforeSec = it }
                            SecondsField("Esperar después (segundos, o «4 min»)", afterSec, listOf("0", "1", "2", "5", "15", "60", "300")) { afterSec = it }
                        }
                        OutlinedTextField(shownDescription(), { description = it.take(500); descAt = timesNow }, label = { Text("Nota (opcional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
    choosingShot?.let { target -> ShotPickerDialog(
        onDismiss = { choosingShot = null },
        onBurst = { choosingShot = null; leaveAnd { c.startBurst() } },
        onChoose = { file -> choosingShot = null; leaveAnd { c.cropShot(file, target) } }
    ) }
    if (picking) ImagePickerDialog(
        selectedId = templateId, onDismiss = { picking = false },
        onShot = { file -> picking = false; leaveAnd { c.cropShot(file, a.id) } },
        onCapture = { picking = false; leaveAnd { c.captureFor(a.id) } },
        onGallery = { picking = false; leaveAnd { c.importImageFor(a.id) } },
        onBurst = { picking = false; leaveAnd { c.startBurst() } }
    ) { chooseImage(it) }
    if (choosingApp) AppChooserDialog(onDismiss = { choosingApp = false }) { pkg, label ->
        packageName = pkg; choosingApp = false
        // Conserva el número del paso («1. Abrir Zooba»): solo cambia el nombre del juego.
        if (name == a.name || name.isBlank()) name = (Regex("^\\d+\\.\\s*").find(name)?.value ?: "") + "Abrir $label".take(90)
        // (se guarda directamente: las comprobaciones de la pantalla aún no ven el juego recién elegido)
        if (hadNoApp) build(true)?.copy(packageName = pkg, name = name.trim().ifBlank { "Abrir $label" })?.let { if (store(it)) { c.closeActionEditor(discardDraft = false); c.toast("✓ Listo: «${it.name}»") } }
    }
}

private fun ruleTap(templateId: String): Action = ruleTap(templateId, Studio.project.templates.firstOrNull { it.id == templateId }?.name ?: "la imagen")

/** Botón para grabar en el juego los toques de un bloque (se guardan dentro de él). */
@Composable private fun RecordInsideButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label, color = Danger) }
}

/** Nombre de una app instalada («Chrome»); si no está instalada aquí, el paquete. */
private fun appLabel(context: android.content.Context, pkg: String): String =
    runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

private fun pctText(v: Float): String { val n = v * 100; return if (n == n.toInt().toFloat()) n.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", n) }

@Composable private fun MissingChoice(skip: Boolean, onChange: (Boolean) -> Unit) {
    ChoiceChips(listOf(false to "✖ Darlo por fallado", true to "⏭ Saltarlo y seguir"), skip, onSelect = onChange)
    if (!skip) Hint("Fallado: si la macro es sin fin, la vuelta empieza otra vez; si no, se para.")
}

@Composable private fun XYFields(x: String, y: String, onX: (String) -> Unit, onY: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(x, { onX(it.take(7)) }, label = { Text("X (%)") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(y, { onY(it.take(7)) }, label = { Text("Y (%)") }, singleLine = true, modifier = Modifier.weight(1f))
    }
}

/** Campo de tiempo: se puede escribir «30» (segundos) o «4 min». Los atajos de 60 s o más salen en minutos. */
@Composable fun SecondsField(label: String, value: String, quick: List<String>, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(10)) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRowCompat {
        quick.forEach { q ->
            val shown = parseSeconds(q)?.let { secondsText(it) } ?: q
            AssistChip(onClick = { onChange(shown) }, label = { Text(if (shown.endsWith("min")) shown else "$shown s") })
        }
    }
}

@Composable private fun ImageSection(templateId: String, title: String, onPick: () -> Unit, onRecrop: (String) -> Unit, onTest: (String) -> Unit) {
    val selected = Studio.project.templates.firstOrNull { it.id == templateId }
    SectionTitle(title)
    Card(Modifier.clickable(onClick = onPick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TemplateThumb(selected, Modifier.size(130.dp, 90.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selected == null) Text("Toca aquí para elegir o capturar la imagen", color = Warning)
                else {
                    Text(selected.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    templateWarning(selected)?.let { Text("⚠ $it", color = Warning, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    FlowRowCompat {
        Button(onClick = onPick) { Text(if (selected == null) "🖼 Elegir imagen" else "🖼 Cambiar") }
        if (selected != null) {
            OutlinedButton(onClick = { onRecrop(selected.id) }) { Text("✂ Recortar") }
            OutlinedButton(onClick = { onTest(selected.id) }) { Text("👁 ¿Se ve ahora?") }
        }
    }
}

@Composable private fun ColorSection(
    hex: String, tolerance: String, mode: SearchMode, x: String, y: String,
    onHex: (String) -> Unit, onTolerance: (String) -> Unit, onMode: (SearchMode) -> Unit, onX: (String) -> Unit, onY: (String) -> Unit
) {
    SectionTitle("Color que hay que buscar")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color(parseRgbHex(hex) ?: 0xFF000000.toInt())))
        OutlinedTextField(hex, { onHex(it.take(7).uppercase()) }, label = { Text("Color, p. ej. #FF5500") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    FlowRowCompat {
        listOf("Rojo" to "#FF0000", "Verde" to "#00FF00", "Azul" to "#0080FF", "Amarillo" to "#FFFF00", "Blanco" to "#FFFFFF", "Negro" to "#000000").forEach { (label, sample) ->
            AssistChip(onClick = { onHex(sample) }, label = { Text(label) }, leadingIcon = { Box(Modifier.size(14.dp).background(Color(parseRgbHex(sample)!!))) })
        }
    }
    OutlinedTextField(tolerance, { onTolerance(it.filter(Char::isDigit).take(3)) }, label = { Text("Tolerancia (0–255)") }, singleLine = true)
    ChoiceChips(listOf(SearchMode.ANYWHERE to "En toda la pantalla", SearchMode.EXACT to "En un punto concreto"), mode, onSelect = onMode)
    if (mode == SearchMode.EXACT) XYFields(x, y, onX, onY)
}

@Composable private fun AppChooserDialog(onDismiss: () -> Unit, onChoose: (String, String) -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elegir aplicación") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(query, { query = it.take(60) }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (shown.isEmpty()) Hint("No aparece: escribe el paquete a mano en el editor.")
                shown.forEach { (pkg, label) ->
                    Column(Modifier.fillMaxWidth().clickable { onChoose(pkg, label) }.padding(vertical = 8.dp)) {
                        Text(label); Hint(pkg)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}
