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

private val packageRegex = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** Editor a pantalla completa de un paso. */
@Composable fun ActionEditor(c: AppController, a: Action) {
    val isDraft = c.draftId == a.id
    val info = kindInfo(a.kind)
    var name by remember(a.id) { mutableStateOf(a.name) }
    var description by remember(a.id) { mutableStateOf(a.description) }
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
    var waitSec by remember(a.id) { mutableStateOf(secondsText(if (a.waitMs > 0) a.waitMs else Studio.project.timeoutMs)) }
    // Toque (1 punto) o deslizamiento (2 puntos). Una trayectoria grabada (>2) se conserva tal cual.
    val recordedPath = a.kind == Kind.GESTURE && a.points.size > 2
    var swipe by remember(a.id, a.points) { mutableStateOf(a.points.size == 2) }
    var x2 by remember(a.id, a.points) { mutableStateOf(pctText(a.points.getOrNull(1)?.x ?: .5f)) }
    var y2 by remember(a.id, a.points) { mutableStateOf(pctText(a.points.getOrNull(1)?.y ?: .2f)) }
    var picking by remember { mutableStateOf(false) }
    var choosingApp by remember { mutableStateOf(false) }

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
    val validPackage = a.kind != Kind.OPEN_APP || packageName.matches(packageRegex)
    val needsImage = a.kind in imageKinds
    val problems = buildList {
        if (name.isBlank()) add("Escribe un nombre")
        if (!validDuration) add(if (durationInSeconds) "Revisa el tiempo" else "Revisa la duración")
        if (a.kind == Kind.REPEAT && cnt == null) add("Las repeticiones van de 1 a 100")
        if (!validPoint) add("X e Y van de 0 a 100")
        if (!validClick) add("Revisa el punto del toque")
        if (!validWait) add("El tiempo de búsqueda va de 1 s a 1 h")
        if (!validColor) add("Revisa el color, la tolerancia o el punto")
        if (!validPackage) add("Elige la aplicación")
        if (needsImage && templateId.isBlank()) add("Elige o captura una imagen")
    }

    /** Construye el paso con lo escrito. Con [lenient], usa los valores
     * originales donde haya errores (para guardar antes de salir de la app). */
    fun build(lenient: Boolean): Action? {
        if (!lenient && problems.isNotEmpty()) return null
        return a.copy(
            name = name.trim().ifBlank { a.name }, description = description.trim(),
            templateId = templateId,
            durationMs = if (validDuration) d!! else a.durationMs,
            count = cnt ?: a.count,
            points = when {
                a.kind != Kind.GESTURE || recordedPath || px == null || py == null -> a.points
                swipe && px2 != null && py2 != null -> listOf(Point(px / 100, py / 100), Point(px2 / 100, py2 / 100))
                swipe -> a.points
                else -> listOf(Point(px / 100, py / 100))
            },
            skipIfMissing = skipIfMissing,
            waitMs = if (a.kind == Kind.CLICK_IMAGE && waitMs != null) (if (waitMs == Studio.project.timeoutMs) 0 else waitMs) else a.waitMs,
            packageName = if (validPackage && a.kind == Kind.OPEN_APP) packageName else a.packageName,
            clickTarget = if (validClick) clickTarget else a.clickTarget, clickStyle = clickStyle,
            clickOffsetX = if (validClick && clickTarget != ClickTarget.CENTER) cx!! else a.clickOffsetX,
            clickOffsetY = if (validClick && clickTarget != ClickTarget.CENTER) cy!! else a.clickOffsetY,
            targetColor = color ?: a.targetColor, colorTolerance = tol ?: a.colorTolerance, colorMode = colorMode,
            colorX = colX?.div(100) ?: a.colorX, colorY = colY?.div(100) ?: a.colorY
        )
    }
    fun store(edited: Action) = Studio.update(Studio.project.copy(actions = updateAction(Studio.project.actions, a.id) { edited }))
    /** Guarda lo escrito y sale a otra pantalla (captura, galería, punto). */
    fun leaveAnd(then: () -> Unit) { build(true)?.let { store(it) }; c.draftId = null; c.editingId = null; then() }

    Dialog(onDismissRequest = { c.closeActionEditor(discardDraft = true) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { c.closeActionEditor(discardDraft = true) }) { Icon(Icons.Default.Close, "Cancelar") }
                    Text("${info.icon}  ${if (isDraft) "Nuevo paso" else "Editar paso"} · ${info.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Button(onClick = { build(false)?.let { if (store(it)) c.closeActionEditor(discardDraft = false) } }, enabled = problems.isEmpty(), modifier = Modifier.padding(end = 8.dp)) { Text("✓ Guardar") }
                }
                if (problems.isNotEmpty()) Text("⚠ " + problems.joinToString(" · "), color = Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint(info.hint)
                    OutlinedTextField(name, { name = it.take(100) }, label = { Text("Nombre del paso") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    if (needsImage) ImageSection(
                        c, templateId,
                        title = when (a.kind) { Kind.CLICK_IMAGE -> "Imagen que hay que tocar"; Kind.WAIT_IMAGE -> "Imagen que hay que esperar"; else -> "Imagen que hay que comprobar" },
                        onPick = { picking = true },
                        onCapture = { leaveAnd { c.captureFor(a.id) } },
                        onGallery = { leaveAnd { c.importImageFor(a.id) } },
                        onRecrop = { id -> leaveAnd { c.recropImage(id, a.id) } },
                        onTest = { id -> leaveAnd { c.testImage(id) } }
                    )

                    when (a.kind) {
                        Kind.CLICK_IMAGE -> {
                            SectionTitle("Dónde tocar al encontrarla")
                            ChoiceChips(listOf(ClickTarget.CENTER to "En el centro", ClickTarget.OFFSET to "Desplazado", ClickTarget.FIXED to "En otro punto de la pantalla"), clickTarget) {
                                if (it != clickTarget) { clickX = if (it == ClickTarget.FIXED) "50" else "0"; clickY = clickX }
                                clickTarget = it
                            }
                            if (clickTarget == ClickTarget.OFFSET) {
                                Hint("Distancia desde el centro, en % del tamaño de la imagen (-100 a 100). Ej.: X 100 = un ancho de imagen a la derecha.")
                                XYFields(clickX, clickY, { clickX = it }, { clickY = it })
                            }
                            if (clickTarget == ClickTarget.FIXED) {
                                Hint("La imagen sirve de aviso y el toque se hace en este punto de la pantalla (0–100 %).")
                                XYFields(clickX, clickY, { clickX = it }, { clickY = it })
                                OutlinedButton(onClick = { leaveAnd { c.pickPoint(a.id) } }, modifier = Modifier.fillMaxWidth()) { Text("👆 Elegir el punto tocando la pantalla") }
                            }
                            SectionTitle("Tipo de toque")
                            ChoiceChips(listOf(ClickStyle.SINGLE to "Un toque", ClickStyle.DOUBLE to "Doble toque", ClickStyle.LONG to "Pulsación larga"), clickStyle) {
                                clickStyle = it
                                if (it == ClickStyle.LONG && (duration.toLongOrNull() ?: 0) < 500) duration = "800"
                            }
                            if (clickStyle == ClickStyle.LONG) OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Duración de la pulsación (ms, mínimo 500)") }, singleLine = true)
                            SectionTitle("Si la imagen no aparece")
                            SecondsField("Buscarla durante (segundos)", waitSec, listOf("2", "5", "10", "30", "60")) { waitSec = it }
                            MissingChoice(skipIfMissing) { skipIfMissing = it }
                        }
                        Kind.WAIT_IMAGE -> {
                            SecondsField("Esperar como máximo (segundos)", duration, listOf("10", "30", "60", "120")) { duration = it }
                            Hint("Comprueba la pantalla cada 0,7 s. En cuanto aparezca, sigue con el siguiente paso.")
                            SectionTitle("Si se agota el tiempo")
                            MissingChoice(skipIfMissing) { skipIfMissing = it }
                        }
                        Kind.IF_IMAGE, Kind.IF_COLOR -> Hint("Después de guardar, añade pasos en «✔ Si se ve» y «✖ Si NO se ve» directamente en la lista.")
                        Kind.REPEAT -> {
                            OutlinedTextField(count, { count = it.filter(Char::isDigit).take(3) }, label = { Text("Cuántas veces (1–100)") }, singleLine = true)
                            Hint("Después de guardar, añade los pasos que se repiten dentro de este bloque en la lista.")
                        }
                        Kind.WAIT -> SecondsField("Tiempo de espera (segundos)", duration, listOf("0,5", "1", "2", "5", "10")) { duration = it }
                        Kind.GESTURE -> {
                            if (!recordedPath) ChoiceChips(listOf(false to "👆 Tocar", true to "↕ Deslizar"), swipe) { swipe = it }
                            if (!recordedPath && swipe) {
                                SectionTitle("Deslizamiento")
                                OutlinedButton(onClick = { leaveAnd { c.pickPoint(a.id, swipe = true) } }, modifier = Modifier.fillMaxWidth()) { Text("👆 Dibujarlo arrastrando el dedo en la pantalla") }
                                Hint("O escribe en % de la pantalla dónde empieza y dónde acaba (0 = arriba/izquierda, 100 = abajo/derecha).")
                                Text("Desde", style = MaterialTheme.typography.labelLarge)
                                XYFields(x, y, { x = it }, { y = it })
                                Text("Hasta", style = MaterialTheme.typography.labelLarge)
                                XYFields(x2, y2, { x2 = it }, { y2 = it })
                                OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Duración del deslizamiento (ms)") }, singleLine = true)
                                Hint("300 ms = deslizamiento rápido · 1000 ms o más = arrastre lento.")
                            } else if (!recordedPath) {
                                SectionTitle("Punto de la pantalla")
                                OutlinedButton(onClick = { leaveAnd { c.pickPoint(a.id) } }, modifier = Modifier.fillMaxWidth()) { Text("👆 Elegir el punto tocando la pantalla") }
                                Hint("O escríbelo en % de la pantalla: 0 = arriba/izquierda, 100 = abajo/derecha.")
                                XYFields(x, y, { x = it }, { y = it })
                                OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Duración del toque (ms)") }, singleLine = true)
                                Hint("80 ms = toque normal · 600 ms o más = pulsación larga.")
                            } else {
                                Text("Trayectoria grabada con ${a.points.size} puntos.")
                                OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Duración del gesto (ms)") }, singleLine = true)
                            }
                        }
                        Kind.OPEN_APP -> {
                            Button(onClick = { choosingApp = true }, modifier = Modifier.fillMaxWidth()) { Text("📱 Elegir app instalada") }
                            OutlinedTextField(packageName, { packageName = it.trim().take(255) }, label = { Text("Paquete Android") }, placeholder = { Text("com.ejemplo.juego") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Hint("Solo abre la app; no espera a que cargue. Añade después «Esperar a ver una imagen» con la pantalla de llegada.")
                        }
                        Kind.CLOSE_ACTIVE -> {
                            SecondsField("Espera para que se abra Recientes (0,5–3 s)", duration, listOf("0,5", "1", "2")) { duration = it }
                            Hint("Abre Recientes, desliza la tarjeta de la app hacia arriba y vuelve a Inicio. No equivale a «Forzar detención».")
                        }
                        else -> Unit
                    }
                    if (a.kind == Kind.IF_COLOR) {
                        Button(onClick = { leaveAnd { c.pickColor(a.id) } }, modifier = Modifier.fillMaxWidth()) { Text("🎯 Elegir el color tocándolo en una captura") }
                        Hint("Irás a tu juego, pulsarás 📷 Capturar en el panel y tocarás el punto cuyo color quieres detectar. Se guardan el color y su posición.")
                    }
                    if (a.kind == Kind.IF_COLOR) ColorSection(colorHex, tolerance, colorMode, colorX, colorY, { colorHex = it }, { tolerance = it }, { colorMode = it }, { colorX = it }, { colorY = it })

                    OutlinedTextField(description, { description = it.take(500) }, label = { Text("Nota (opcional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
    if (picking) ImagePickerDialog(selectedId = templateId, onDismiss = { picking = false }) { templateId = it.id; picking = false }
    if (choosingApp) AppChooserDialog(onDismiss = { choosingApp = false }) { pkg, label ->
        packageName = pkg; choosingApp = false
        if (name == a.name || name.isBlank()) name = "Abrir $label".take(100)
    }
}

private fun pctText(v: Float): String { val n = v * 100; return if (n == n.toInt().toFloat()) n.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", n) }

@Composable private fun MissingChoice(skip: Boolean, onChange: (Boolean) -> Unit) {
    ChoiceChips(listOf(false to "■ Parar la macro", true to "⏭ Saltar este paso y seguir"), skip, onSelect = onChange)
    Hint(if (skip) "Si no aparece, la macro sigue con el siguiente paso." else "Si no aparece, la macro se detiene y te avisa.")
}

@Composable private fun XYFields(x: String, y: String, onX: (String) -> Unit, onY: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(x, { onX(it.take(7)) }, label = { Text("X (%)") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(y, { onY(it.take(7)) }, label = { Text("Y (%)") }, singleLine = true, modifier = Modifier.weight(1f))
    }
}

@Composable private fun SecondsField(label: String, value: String, quick: List<String>, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(8)) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRowCompat { quick.forEach { q -> AssistChip(onClick = { onChange(q) }, label = { Text("$q s") }) } }
}

@Composable private fun ImageSection(
    c: AppController, templateId: String, title: String,
    onPick: () -> Unit, onCapture: () -> Unit, onGallery: () -> Unit, onRecrop: (String) -> Unit, onTest: (String) -> Unit
) {
    val selected = Studio.project.templates.firstOrNull { it.id == templateId }
    SectionTitle(title)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TemplateThumb(selected, Modifier.size(130.dp, 90.dp).clickable(onClick = onPick))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selected == null) {
                    Text("Todavía no hay imagen", color = Warning)
                    Hint("Lo más fácil: «📷 Capturar». Irás a tu juego, pulsarás Capturar en el panel y recortarás el botón.")
                } else {
                    Text(selected.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Hint("${selected.template.width}×${selected.template.height} px · capturada a ${selected.template.screenWidth}×${selected.template.screenHeight}")
                }
            }
        }
    }
    FlowRowCompat {
        Button(onClick = onCapture) { Text("📷 Capturar") }
        OutlinedButton(onClick = onPick) { Text("🖼 Mis imágenes") }
        OutlinedButton(onClick = onGallery) { Text("📁 Galería / archivos") }
        if (selected != null) {
            OutlinedButton(onClick = { onRecrop(selected.id) }) { Text("✂ Recortar") }
            OutlinedButton(onClick = { onTest(selected.id) }) { Text("👁 ¿Se ve ahora?") }
        }
    }
    if (!Studio.captureReady) Hint("📷 Capturar pedirá permiso para grabar la pantalla la primera vez.")
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
    Hint("Cuánto puede variar el color. 20–40 suele ir bien.")
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
