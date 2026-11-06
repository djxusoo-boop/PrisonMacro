package es.macrostudio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.macrostudio.core.*
import es.macrostudio.engine.Engine

/** Dónde se insertará el próximo paso. */
data class AddTarget(val parentId: String? = null, val otherwise: Boolean = false, val afterId: String? = null, val beforeId: String? = null)

private val noConfigKinds = setOf(Kind.BACK, Kind.HOME, Kind.RECENTS, Kind.NOTIFICATIONS, Kind.QUICK_SETTINGS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EditorScreen(c: AppController) {
    val p = Studio.project
    val busy = Studio.running || Studio.recording
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val issues = p.executionIssues()
    BackHandler { c.open(Screen.HOME) }

    fun insert(kind: Kind, target: AddTarget) {
        val a = newAction(kind)
        val list = when {
            target.beforeId != null -> insertBefore(p.actions, target.beforeId, a)
            target.afterId != null -> insertAfter(p.actions, target.afterId, a)
            target.parentId == null -> p.actions + a
            else -> updateAction(p.actions, target.parentId) { if (target.otherwise) it.copy(otherwise = it.otherwise + a) else it.copy(children = it.children + a) }
        }
        if (Studio.update(p.copy(actions = list)) && kind !in noConfigKinds) c.editAction(a.id, isDraft = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { c.open(Screen.HOME) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver a mis macros") } },
                title = {
                    Column(Modifier.clickable(enabled = !busy) { renaming = true }) {
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${countActions(p.actions)} paso(s) · toca para cambiar el nombre", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                actions = {
                    TextButton(onClick = { c.open(Screen.IMAGES) }) { Text("🖼 Imágenes (${p.templates.size})") }
                    IconButton(onClick = { showSettings = true }, enabled = !busy) { Icon(Icons.Default.Settings, "Ajustes de la macro") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("↶  Deshacer") }, enabled = Studio.canUndo && !busy, onClick = { menu = false; Studio.undo() })
                            DropdownMenuItem(text = { Text("↷  Rehacer") }, enabled = Studio.canRedo && !busy, onClick = { menu = false; Studio.redo() })
                            DropdownMenuItem(text = { Text("🧪  Simular (sin tocar nada)") }, enabled = !busy && issues.isEmpty(), onClick = { menu = false; Engine.start(false, true) })
                            DropdownMenuItem(text = { Text("📤  Exportar esta macro") }, onClick = { menu = false; c.exportProject(p) })
                            DropdownMenuItem(text = { Text("📜  Ver registro") }, onClick = { menu = false; c.open(Screen.LOG) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("🗑  Borrar macro", color = MaterialTheme.colorScheme.error) }, enabled = !busy, onClick = { menu = false; deleting = true })
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (Studio.running || Studio.status.isNotBlank()) Text(
                        (if (Studio.running) "▶ " else "") + Studio.status,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { addTarget = AddTarget() }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Añadir paso") }
                        OutlinedButton(onClick = { c.record() }, enabled = !busy) { Text("● Grabar") }
                        if (Studio.running) OutlinedButton(onClick = { Engine.stop("Parada manual") }) { Text("■ Parar") }
                        else FilledTonalButton(onClick = { c.play(p) }, enabled = !busy) { Icon(Icons.Default.PlayArrow, null); Text("Reproducir") }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (issues.isNotEmpty()) IssuesCard(issues)
            StartCard(p, onClick = { showSettings = true })
            if (p.actions.isEmpty()) EmptyEditor(onAdd = { addTarget = AddTarget() }, onRecord = { c.record() })
            StepList(c, p.actions, "", busy, onAdd = { addTarget = it })
            if (p.actions.isNotEmpty()) TextButton(onClick = { addTarget = AddTarget() }, enabled = !busy) { Text("＋ Añadir paso al final") }
            if (p.repeatCount > 1) Hint("Al terminar, la macro vuelve a empezar hasta completar ${p.repeatCount} vueltas.")
            if (p.repeatCount == 0) Hint("Al terminar, la macro vuelve a empezar hasta que pulses ■ en el panel" + if (p.maxMinutes > 0) " (o pasen ${p.maxMinutes} min)." else ".")
            Spacer(Modifier.height(24.dp))
        }
    }

    addTarget?.let { target -> AddStepDialog(onDismiss = { addTarget = null }) { kind -> addTarget = null; insert(kind, target) } }
    if (showSettings) MacroSettingsDialog(c, onDismiss = { showSettings = false })
    if (renaming) NameDialog("Nombre de la macro", p.name, "Guardar", onDismiss = { renaming = false }) { renaming = false; Studio.update(Studio.project.copy(name = it)) }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("¿Borrar «${p.name}»?") },
        text = { Text("Se borrará la macro y sus imágenes. Expórtala antes si quieres conservarla.") },
        confirmButton = { TextButton(onClick = { deleting = false; Studio.delete(p.id); c.open(Screen.HOME) }) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } }
    )
}

@Composable private fun IssuesCard(issues: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("⚠ Falta completar antes de reproducir", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            issues.take(4).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
            if (issues.size > 4) Text("• y ${issues.size - 4} más", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable private fun StartCard(p: Project, onClick: () -> Unit) {
    val start = p.templates.firstOrNull { it.template.file == p.template?.file }
    Row(
        Modifier.fillMaxWidth().clip8().background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("🚩", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(if (p.startOnImage) "Empieza cuando se vea una imagen" else "Empieza al pulsar ▶", style = MaterialTheme.typography.bodyLarge)
            Hint(buildString {
                append(when (p.repeatCount) { 0 -> "Se repite sin fin hasta que pulses ■"; 1 -> "Se ejecuta una vez"; else -> "Se repite ${p.repeatCount} veces" })
                if (p.repeatCount != 1 && p.intervalMs > 0) append(", con ${readableDuration(p.intervalMs)} de pausa")
                append(" · toca para cambiarlo")
            })
        }
        if (p.startOnImage) TemplateThumb(start, Modifier.size(60.dp, 42.dp))
    }
}

@Composable private fun EmptyEditor(onAdd: () -> Unit, onRecord: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Esta macro está vacía", style = MaterialTheme.typography.titleLarge)
            Text("Tienes dos formas de crearla:")
            Text("● Grabar: ve a tu juego, pulsa ● en el panel flotante y haz tus toques. Se guardan como pasos con sus esperas.")
            Text("＋ Añadir paso: elige qué hacer (tocar, esperar, buscar una imagen y tocarla, si se ve una imagen…).")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRecord) { Text("● Grabar") }
                OutlinedButton(onClick = onAdd) { Text("＋ Añadir paso") }
            }
        }
    }
}

fun Modifier.clip8() = clip(RoundedCornerShape(8.dp))

@Composable private fun StepList(c: AppController, items: List<Action>, prefix: String, busy: Boolean, onAdd: (AddTarget) -> Unit) {
    items.forEachIndexed { index, a -> key(a.id) { StepRow(c, a, "$prefix${index + 1}", busy, onAdd) } }
}

@Composable private fun StepRow(c: AppController, a: Action, number: String, busy: Boolean, onAdd: (AddTarget) -> Unit) {
    val p = Studio.project
    val info = kindInfo(a.kind)
    val current = Studio.currentAction == a.id && Studio.running
    val image = p.templates.firstOrNull { it.id == a.templateId }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (current) Color(0xFF244D51) else MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !busy) { c.editAction(a.id) }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp).alpha(if (a.enabled) 1f else .45f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(number, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Text(info.icon, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                Text(a.name + if (!a.enabled) " (desactivado)" else "", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val summary = actionSummary(a, p)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = if (summary.startsWith("⚠")) Warning else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (a.kind in imageKinds || a.templateId.isNotBlank()) TemplateThumb(image, Modifier.size(56.dp, 40.dp))
            if (a.kind == Kind.IF_COLOR) Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(a.targetColor)))
            StepMenu(c, a, busy, onAdd)
        }
        if (a.kind in containerKinds) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (a.kind) {
                    Kind.REPEAT -> Branch("🔁 Se repite ${a.count} veces:", c, a, a.children, number, false, busy, onAdd, "Añade los pasos que quieres repetir")
                    else -> {
                        val what = if (a.kind == Kind.IF_IMAGE) "la imagen" else "el color"
                        Branch("✔ Si se ve $what:", c, a, a.children, number, false, busy, onAdd, "Sin pasos: no hace nada")
                        Branch("✖ Si NO se ve:", c, a, a.otherwise, number, true, busy, onAdd, "Sin pasos: sigue con el siguiente")
                    }
                }
            }
        }
    }
}

@Composable private fun Branch(title: String, c: AppController, parent: Action, items: List<Action>, number: String, otherwise: Boolean, busy: Boolean, onAdd: (AddTarget) -> Unit, empty: String) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), RoundedCornerShape(8.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = if (otherwise) Danger else MaterialTheme.colorScheme.primary)
        if (items.isEmpty()) Hint(empty)
        StepList(c, items, "$number.${if (otherwise) "n" else ""}", busy, onAdd)
        TextButton(onClick = { onAdd(AddTarget(parentId = parent.id, otherwise = otherwise)) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text("＋ Añadir paso aquí")
        }
    }
}

@Composable private fun StepMenu(c: AppController, a: Action, busy: Boolean, onAdd: (AddTarget) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun change(list: List<Action>) = Studio.update(Studio.project.copy(actions = list))
    Box {
        IconButton(onClick = { open = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Opciones de ${a.name}") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("✏️  Editar") }, onClick = { open = false; c.editAction(a.id) })
            DropdownMenuItem(text = { Text("▶  Probar este paso") }, onClick = { open = false; c.testStep(a) })
            if (a.templateId.isNotBlank()) DropdownMenuItem(text = { Text("👁  ¿Se ve la imagen ahora?") }, onClick = { open = false; c.testImage(a.templateId) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("⬆  Subir") }, onClick = { open = false; change(moveAction(Studio.project.actions, a.id, -1)) })
            DropdownMenuItem(text = { Text("⬇  Bajar") }, onClick = { open = false; change(moveAction(Studio.project.actions, a.id, 1)) })
            DropdownMenuItem(text = { Text("＋  Añadir paso antes") }, onClick = { open = false; onAdd(AddTarget(beforeId = a.id)) })
            DropdownMenuItem(text = { Text("＋  Añadir paso después") }, onClick = { open = false; onAdd(AddTarget(afterId = a.id)) })
            DropdownMenuItem(text = { Text("⧉  Duplicar") }, onClick = { open = false; change(duplicateAfter(Studio.project.actions, a.id)) })
            DropdownMenuItem(text = { Text(if (a.enabled) "⏸  Desactivar" else "✅  Activar") }, onClick = { open = false; change(updateAction(Studio.project.actions, a.id) { it.copy(enabled = !it.enabled) }) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("🗑  Borrar", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; change(updateAction(Studio.project.actions, a.id) { null }) })
        }
    }
}

@Composable fun AddStepDialog(onDismiss: () -> Unit, onChoose: (Kind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Qué debe hacer este paso?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                kindGroups.forEach { (group, kinds) ->
                    SectionTitle(group)
                    kinds.forEach { kind ->
                        val info = kindInfo(kind)
                        Row(
                            Modifier.fillMaxWidth().clip8().clickable { onChoose(kind) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(info.icon, style = MaterialTheme.typography.headlineSmall)
                            Column { Text(info.title, style = MaterialTheme.typography.bodyLarge); Hint(info.hint) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Cómo empieza y cuántas veces se repite la macro, y la sensibilidad de la búsqueda. */
@Composable private fun MacroSettingsDialog(c: AppController, onDismiss: () -> Unit) {
    val p = Studio.project
    var startOnImage by remember { mutableStateOf(p.startOnImage) }
    var endless by remember { mutableStateOf(p.repeatCount == 0) }
    var repeat by remember { mutableStateOf(if (p.repeatCount == 0) "1" else p.repeatCount.toString()) }
    var minutes by remember { mutableStateOf(p.maxMinutes.toString()) }
    var interval by remember { mutableStateOf(secondsText(p.intervalMs)) }
    var delay by remember { mutableStateOf(secondsText(p.delayMs)) }
    var timeout by remember { mutableStateOf(secondsText(p.timeoutMs)) }
    var mode by remember { mutableStateOf(p.mode) }
    var threshold by remember { mutableFloatStateOf(p.threshold.toFloat()) }
    var picking by remember { mutableStateOf(false) }
    val repeatN = if (endless) 0 else repeat.toIntOrNull()?.takeIf { it in 1..1000 }
    val minutesN = minutes.toIntOrNull()?.takeIf { it in 0..1440 }
    val intervalMs = parseSeconds(interval)?.takeIf { it in 0..3_600_000 }
    val delayMs = parseSeconds(delay)?.takeIf { it in 0..600_000 }
    val timeoutMs = parseSeconds(timeout)?.takeIf { it in 1000..3_600_000 }
    val valid = repeatN != null && minutesN != null && intervalMs != null && delayMs != null && timeoutMs != null
    fun collect(base: Project) = base.copy(startOnImage = startOnImage && base.template != null, repeatCount = repeatN!!, maxMinutes = minutesN!!, intervalMs = intervalMs!!, delayMs = delayMs!!, timeoutMs = timeoutMs!!, mode = mode, threshold = threshold.toDouble().coerceIn(.7, 1.0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes de la macro") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("🚩 Cómo empieza")
                ChoiceChips(listOf(false to "Al pulsar ▶", true to "Cuando se vea una imagen"), startOnImage) { startOnImage = it }
                if (startOnImage) {
                    val start = p.templates.firstOrNull { it.template.file == p.template?.file }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TemplateThumb(start, Modifier.size(90.dp, 62.dp))
                        Column(Modifier.weight(1f)) {
                            Text(start?.name ?: if (p.template != null) "Imagen de inicio" else "Sin imagen elegida", color = if (p.template == null) Warning else MaterialTheme.colorScheme.onSurface)
                            Hint("La macro espera a ver esta imagen (hasta el tiempo máximo de búsqueda) antes de cada vuelta.")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (valid) Studio.update(collect(p)); onDismiss(); c.captureFor(Studio.START_IMAGE_TARGET) }) { Text("📷 Capturar") }
                        OutlinedButton(onClick = { picking = true }, enabled = p.templates.isNotEmpty()) { Text("🖼 Mis imágenes") }
                    }
                }
                SectionTitle("🔁 Repetición")
                ChoiceChips(listOf(false to "Un número de veces", true to "Sin fin (hasta ■)"), endless) { endless = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!endless) OutlinedTextField(repeat, { repeat = it.filter(Char::isDigit).take(4) }, label = { Text("Vueltas (1–1000)") }, isError = repeatN == null, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(interval, { interval = it.take(8) }, label = { Text("Pausa entre vueltas (s)") }, isError = intervalMs == null, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(delay, { delay = it.take(8) }, label = { Text("Espera antes de empezar (s)") }, isError = delayMs == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Parar sola a los … minutos (0 = nunca)") }, isError = minutesN == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                Hint("Seguridad por si te olvidas una macro en marcha. Pon 0 para que solo pare cuando pulses ■.")
                SectionTitle("🔍 Búsqueda de imágenes")
                ChoiceChips(listOf(SearchMode.ANYWHERE to "En toda la pantalla", SearchMode.EXACT to "Solo en su sitio original"), mode) { mode = it }
                Text("Parecido exigido: ${(threshold * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
                Slider(value = threshold, onValueChange = { threshold = it }, valueRange = .7f..1f)
                Hint("Más alto = más estricto (menos falsos aciertos). Recomendado: 90–95 %.")
                OutlinedTextField(timeout, { timeout = it.take(8) }, label = { Text("Tiempo máximo buscando una imagen (s)") }, isError = timeoutMs == null, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (Studio.update(collect(Studio.project))) onDismiss() }, enabled = valid) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
    if (picking) ImagePickerDialog(selectedId = null, onDismiss = { picking = false }) { saved ->
        picking = false
        Studio.update(Studio.project.copy(template = saved.template))
    }
}
