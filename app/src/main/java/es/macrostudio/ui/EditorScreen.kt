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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
            // Las reglas «⚡» van arriba, con las demás: vigilan toda la macro, da igual dónde estén.
            target.parentId == null && kind == Kind.WHEN_IMAGE -> p.actions.indexOfFirst { it.kind != Kind.WHEN_IMAGE }.let { i -> if (i < 0) p.actions + a else p.actions.take(i) + a + p.actions.drop(i) }
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
                            DropdownMenuItem(text = { Text("☑  Seleccionar pasos (para borrar varios)") }, enabled = !busy && p.actions.isNotEmpty(), onClick = { menu = false; Studio.sorting = false; Studio.selecting = true; Studio.selectedSteps = emptySet() })
                            DropdownMenuItem(text = { Text(if (Studio.sorting) "✓  Terminar de ordenar" else "↕  Ordenar pasos") }, enabled = !busy, onClick = { menu = false; Studio.sorting = !Studio.sorting })
                            val blocks = containerIds(p.actions)
                            if (blocks.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("▶  Plegar todos los bloques") }, onClick = { menu = false; Studio.collapsed = Studio.collapsed + blocks })
                                DropdownMenuItem(text = { Text("▼  Desplegar todos los bloques") }, onClick = { menu = false; Studio.collapsed = Studio.collapsed - blocks })
                            }
                            DropdownMenuItem(text = { Text("🧹  Juntar las esperas (${countWaits(p.actions)})") }, enabled = !busy && countWaits(p.actions) > 0, onClick = {
                                menu = false
                                val (merged, n) = mergeWaits(p.actions)
                                if (n > 0 && Studio.update(p.copy(actions = merged))) c.toast("$n paso(s) «Esperar» convertidos en esperas del paso de al lado. ↶ Deshacer si no te gusta.")
                                else c.toast("No hay esperas que se puedan juntar")
                            })
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
                    if (Studio.running || Studio.stoppedActionId != null) Text(
                        (if (Studio.running) "▶ " else "") + Studio.status,
                        style = MaterialTheme.typography.labelSmall, color = if (!Studio.running && Studio.stoppedActionId != null) Danger else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
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
        val scroll = rememberScrollState()
        // Arrastrar un paso por su asa ⠿ lo cambia de sitio dentro de su bloque.
        val stepReorder = remember(scroll) {
            ColumnReorder(scroll,
                neighbor = { key, down -> siblingNeighbor(Studio.project.actions, key, down) },
                move = { key, down -> Studio.update(Studio.project.copy(actions = moveAction(Studio.project.actions, key, if (down) 1 else -1))) })
        }
        CompositionLocalProvider(LocalStepReorder provides stepReorder) {
        Column(Modifier.padding(padding).fillMaxSize()
            .onGloballyPositioned { val b = it.boundsInRoot(); stepReorder.viewTop = b.top; stepReorder.viewBottom = b.bottom }
            .verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (issues.isNotEmpty() && p.actions.isNotEmpty()) IssuesCard(issues, missing = stepsWithoutImage(p), onPick = { c.editAction(it) }, fixRules = if (fixEmptyRules(p.actions, p).second > 0) ({
                val (fixed, n) = fixEmptyRules(Studio.project.actions, Studio.project)
                if (Studio.update(Studio.project.copy(actions = fixed))) c.toast("🎯 $n regla(s) ⚡ ya tocan su imagen")
            }) else null)
            // En un BlueStacks nuevo (o al pasarle la macro) aún no se sabe cómo cerrar allí el juego.
            if (closesGame(p.actions)) WhichDeviceCard(c)
            StartCard(p, onClick = { showSettings = true })
            if (p.actions.isEmpty()) EmptyEditor(onAdd = { addTarget = AddTarget() }, onRecord = { c.record() })
            Studio.clipboard?.let { clip ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (Studio.clipboardCut) "✂ Cortado" else "📋 Copiado"}: «${clip.name}»", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { pasteAt(p, clip, null) }, enabled = !busy) { Text("📌 Pegar al final") }
                        TextButton(onClick = { Studio.clipboard = null }) { Text("✕") }
                    }
                }
            }
            if (Studio.selecting) SelectBar(p, busy)
            if (Studio.sorting) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("↕ Modo ordenar: «▲ Subir» y «▼ Bajar» en cada paso. «⤴ Sacar» lo saca de su bloque. Para meterlo en un bloque: ⋮ › ✂ Cortar y luego 📌 Pegar aquí.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { Studio.sorting = false }) { Text("✓ Listo") }
                }
            }
            StepList(c, p.actions, "", busy, onAdd = { addTarget = it })
            if (p.actions.isNotEmpty()) TextButton(onClick = { addTarget = AddTarget() }, enabled = !busy) { Text("＋ Añadir paso al final") }
            Spacer(Modifier.height(24.dp))
        }
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

/** Pasos activos que todavía necesitan su imagen (para ofrecer un botón directo a cada uno). */
private fun stepsWithoutImage(p: Project): List<Action> {
    val ids = p.templates.map { it.id }.toSet()
    fun walk(list: List<Action>): List<Action> = list.filter { it.enabled }.flatMap { a ->
        (if ((a.kind in imageKinds && a.templateId !in ids) || (a.kind == Kind.OPEN_APP && a.packageName == QuickMacro.NO_APP)) listOf(a) else emptyList()) + walk(a.children) + walk(a.otherwise)
    }
    return walk(p.actions)
}

@Composable private fun IssuesCard(issues: List<String>, missing: List<Action>, onPick: (String) -> Unit, fixRules: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        // Los pasos sin imagen ya salen como botones: no se repiten en la lista de avisos.
        val shown = issues.filterNot { line -> missing.any { line.startsWith("«${it.name.ifBlank { "" }}»") } }
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (shown.isEmpty()) "🖼 Para terminar, elige lo que falta" else "⚠ Falta completar antes de reproducir", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            shown.take(4).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
            if (shown.size > 4) Text("• y ${shown.size - 4} más", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            fixRules?.let { Button(onClick = it, modifier = Modifier.padding(top = 4.dp)) { Text("🎯 Arreglarlo: que toquen su imagen") } }
            if (missing.isNotEmpty()) FlowRowCompat {
                missing.take(6).forEach { a -> Button(onClick = { onPick(a.id) }) { Text((if (a.kind == Kind.OPEN_APP) "📱 Elegir el juego de «" else "🖼 Imagen para «") + a.name.take(32) + "»", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            }
        }
    }
}

@Composable private fun StartCard(p: Project, onClick: () -> Unit) {
    val start = p.templates.firstOrNull { it.template.file == p.template?.file }
    Row(
        Modifier.fillMaxWidth().clip8().background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("🔁", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(when (p.repeatCount) { 0 -> "Sin fin, hasta que pulses ■"; 1 -> "Una vez"; else -> "${p.repeatCount} veces" }, style = MaterialTheme.typography.bodyLarge)
            Hint(buildString {
                append(if (p.startOnImage) "Empieza cuando se vea una imagen" else "Empieza al pulsar ▶")
                if (p.repeatCount != 1 && p.intervalMs > 0) append(" · ${readableDuration(p.intervalMs)} de pausa entre vueltas")
                if (p.repeatCount == 0 && p.keepGoing) append(" · si algo falla, vuelve a empezar")
            })
            if (p.actions.any { it.enabled }) Text(totalText(p), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (p.startOnImage) TemplateThumb(start, Modifier.size(60.dp, 42.dp))
        Text("Cambiar", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable private fun EmptyEditor(onAdd: () -> Unit, onRecord: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Esta macro está vacía", style = MaterialTheme.typography.titleLarge)
            Text("Añade los pasos uno a uno (tocar una imagen, esperar…) o graba tus toques en el juego.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd) { Text("＋ Añadir paso") }
                OutlinedButton(onClick = onRecord) { Text("● Grabar") }
            }
        }
    }
}

fun Modifier.clip8() = clip(RoundedCornerShape(8.dp))

/** El arrastre de pasos de la pantalla del editor (para los pasos de dentro de los bloques también). */
private val LocalStepReorder = staticCompositionLocalOf<ColumnReorder?> { null }

@Composable private fun StepList(c: AppController, items: List<Action>, prefix: String, busy: Boolean, onAdd: (AddTarget) -> Unit) {
    // En la lista principal se va sumando el tiempo: «llevas…» hasta el final de cada paso.
    var elapsed = Span.of(Studio.project.delayMs)
    items.forEachIndexed { index, a ->
        val time = stepTime(a, Studio.project)
        elapsed += time
        val upTo = if (prefix.isEmpty()) elapsed else null
        key(a.id) { StepRow(c, a, "$prefix${index + 1}", busy, onAdd, time, upTo) }
    }
}

@Composable private fun StepRow(c: AppController, a: Action, number: String, busy: Boolean, onAdd: (AddTarget) -> Unit, time: Span = Span.ZERO, elapsed: Span? = null) {
    val p = Studio.project
    val info = kindInfo(a.kind)
    val current = Studio.currentAction == a.id && Studio.running
    // Aquí se paró la macro la última vez (por un problema): se marca en rojo.
    val stoppedHere = !Studio.running && Studio.stoppedActionId == a.id
    val image = p.templates.firstOrNull { it.id == a.templateId }
    // «⚡» que solo toca su imagen: se ve como un paso normal, sin bloque.
    val simple = isSimpleRule(a) || (a.kind == Kind.WHEN_IMAGE && a.children.isEmpty() && a.otherwise.isEmpty())
    val block = a.kind in containerKinds && !simple
    val reorder = LocalStepReorder.current
    Card(
        Modifier.fillMaxWidth().then(reorder?.itemModifier(a.id) ?: Modifier),
        colors = CardDefaults.cardColors(containerColor = when { current -> Color(0xFF244D51); stoppedHere -> Color(0xFF5A2430); else -> MaterialTheme.colorScheme.surface })
    ) {
        if (stoppedHere) Text(Studio.stopInfo.replace("\n", " · "), color = Danger, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 10.dp))
        Row(
            Modifier.fillMaxWidth().combinedClickableCompat(
                onClick = { if (busy) Unit else if (Studio.selecting) toggleSelected(a) else c.editAction(a.id) },
                // Mantener pulsado un paso empieza a seleccionar (para borrar varios).
                onLongClick = { if (!busy) { Studio.sorting = false; Studio.selecting = true; toggleSelected(a) } }
            ).padding(start = 8.dp, top = 6.dp, bottom = 6.dp).alpha(if (a.enabled) 1f else .45f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (Studio.selecting) Checkbox(checked = a.id in Studio.selectedSteps, onCheckedChange = { toggleSelected(a) }, enabled = !busy)
            else if (reorder != null && !busy && (siblingsOf(p.actions, a.id)?.size ?: 0) > 1)
                DragHandle(a.name, a.id, onStart = { reorder.start(a.id) }, onDrag = { reorder.drag(it) }, onEnd = { reorder.end() })
            val isBlock = a.kind in containerKinds && !simple
            val folded = a.id in Studio.collapsed
            if (isBlock) Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .18f))
                .clickable { Studio.collapsed = if (folded) Studio.collapsed - a.id else Studio.collapsed + a.id }, contentAlignment = Alignment.Center) {
                Text(if (folded) "▶" else "▼", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(number, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Text(info.icon, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                Text(a.name + if (!a.enabled) " (desactivado)" else "", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val summary = actionSummary(a, p)
                // Sin repetir lo que ya dice el nombre («Tocar «X»» / «Toca «X»») ni el tiempo de una espera.
                val redundant = a.kind == Kind.WAIT || summary.removePrefix("Toca ") == a.name.removePrefix("Tocar ")
                if (!redundant) Text(summary, style = MaterialTheme.typography.bodySmall, color = if (summary.startsWith("⚠")) Warning else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // Una sola línea pequeña: esperas del paso, cuánto tarda y cuánto lleva la macro hasta aquí.
                val extra = listOfNotNull(
                    delaysSummary(a).ifBlank { null },
                    if (a.enabled && a.kind != Kind.WHEN_IMAGE && a.kind != Kind.STOP) "⌛ ${shortSpan(time)}" + (elapsed?.let { " · llevas ${shortSpan(it)}" } ?: "") else null
                )
                if (extra.isNotEmpty()) Text(extra.joinToString("   "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (a.kind in imageKinds || a.templateId.isNotBlank()) TemplateThumb(image, Modifier.size(56.dp, 40.dp))
            if (a.kind == Kind.IF_COLOR) Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(a.targetColor)))
            if (!Studio.selecting) StepMenu(c, a, busy, onAdd)
        }
        if (Studio.sorting) {
            val pos = siblingPosition(p.actions, a.id)
            fun change(list: List<Action>) = Studio.update(Studio.project.copy(actions = list))
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { change(moveAction(Studio.project.actions, a.id, -1)) }, enabled = !busy && pos?.first == false, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("▲ Subir") }
                FilledTonalButton(onClick = { change(moveAction(Studio.project.actions, a.id, 1)) }, enabled = !busy && pos?.second == false, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("▼ Bajar") }
                if (parentOf(p.actions, a.id) != null) FilledTonalButton(onClick = { change(moveOutOfBlock(Studio.project.actions, a.id)) }, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("⤴ Sacar") }
            }
        }
        if (block && a.id in Studio.collapsed) {
            val inside = countActions(a.children) + countActions(a.otherwise)
            Text("▶ $inside paso(s) dentro (plegado) · toca para verlos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 44.dp, bottom = 8.dp).clickable { Studio.collapsed = Studio.collapsed - a.id })
        }
        if (block && a.id !in Studio.collapsed) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (a.kind) {
                    Kind.WHEN_IMAGE -> Branch(image?.let { "⚡ Al ver «${it.name}» hace esto (y después ${afterRuleLabel(a.afterRule)}):" } ?: "⚡ Cuando se vea la imagen, hace esto:", c, a, a.children, number, false, busy, onAdd, "Añade lo que debe hacer cuando aparezca")
                    Kind.REPEAT -> Branch(image?.let { "🔁 Se repite hasta ver «${it.name}» (máx. ${a.count}):" } ?: "🔁 Se repite ${a.count} veces:", c, a, a.children, number, false, busy, onAdd, "Añade los pasos que quieres repetir")
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
        // «Si se ve la imagen» no toca nada por sí solo: este botón añade el paso que toca la imagen encontrada.
        if ((parent.kind == Kind.IF_IMAGE || parent.kind == Kind.WHEN_IMAGE) && !otherwise && parent.templateId.isNotBlank()) {
            val image = Studio.project.templates.firstOrNull { it.id == parent.templateId }
            FilledTonalButton(onClick = {
                val tap = newAction(Kind.CLICK_IMAGE).copy(name = "Tocar «${image?.name ?: "la imagen"}»".take(100), templateId = parent.templateId, waitMs = 2_000, skipIfMissing = true)
                Studio.update(Studio.project.copy(actions = addInside(Studio.project.actions, parent.id, false, tap)))
            }, enabled = !busy, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("🎯 Tocar esta imagen (en su centro)") }
        }
        Row {
            TextButton(onClick = { onAdd(AddTarget(parentId = parent.id, otherwise = otherwise)) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("＋ Añadir paso aquí")
            }
            // Graba en el juego los toques que tiene que hacer este bloque (se guardan aquí dentro).
            TextButton(onClick = { c.recordInto(parent.id, otherwise) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("● Grabar aquí", color = Danger)
            }
            Studio.clipboard?.let { clip ->
                TextButton(onClick = { pasteAt(Studio.project, clip, AddTarget(parentId = parent.id, otherwise = otherwise)) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("📌 Pegar aquí")
                }
            }
        }
    }
}

@Composable private fun StepMenu(c: AppController, a: Action, busy: Boolean, onAdd: (AddTarget) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    fun change(list: List<Action>) = Studio.update(Studio.project.copy(actions = list))
    if (ordering) ChangeOrderDialog(a, onDismiss = { ordering = false }) { o ->
        ordering = false
        val image = Studio.project.templates.firstOrNull { it.id == a.templateId }?.name ?: ""
        if (change(updateAction(Studio.project.actions, a.id) { changeOrder(it, o, image, Studio.project.timeoutMs) }))
            c.toast("✓ Ahora: ${o.short}. La imagen y el sitio del toque siguen igual.")
    }
    Box {
        IconButton(onClick = { open = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Opciones de ${a.name}") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("✏️  Editar") }, onClick = { open = false; c.editAction(a.id) })
            if (orderOf(a) != null) DropdownMenuItem(text = { Text("🔁  Cambiar lo que hace") }, onClick = { open = false; ordering = true })
            DropdownMenuItem(text = { Text("▶  Probar este paso") }, onClick = { open = false; c.testStep(a) })
            if (a.templateId.isNotBlank()) DropdownMenuItem(text = { Text("👁  ¿Se ve la imagen ahora?") }, onClick = { open = false; c.testImage(a.templateId) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("⬆  Subir") }, onClick = { open = false; change(moveAction(Studio.project.actions, a.id, -1)) })
            DropdownMenuItem(text = { Text("⬇  Bajar") }, onClick = { open = false; change(moveAction(Studio.project.actions, a.id, 1)) })
            DropdownMenuItem(text = { Text("＋  Añadir paso antes") }, onClick = { open = false; onAdd(AddTarget(beforeId = a.id)) })
            DropdownMenuItem(text = { Text("＋  Añadir paso después") }, onClick = { open = false; onAdd(AddTarget(afterId = a.id)) })
            DropdownMenuItem(text = { Text("⧉  Duplicar") }, onClick = { open = false; change(duplicateAfter(Studio.project.actions, a.id)) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("📋  Copiar") }, onClick = { open = false; Studio.clipboard = a; Studio.clipboardCut = false })
            DropdownMenuItem(text = { Text("✂  Cortar") }, onClick = { open = false; Studio.clipboard = a; Studio.clipboardCut = true; change(updateAction(Studio.project.actions, a.id) { null }) })
            Studio.clipboard?.let { clip ->
                DropdownMenuItem(text = { Text("📌  Pegar «${clip.name.take(24)}» antes") }, onClick = { open = false; pasteAt(Studio.project, clip, AddTarget(beforeId = a.id)) })
                DropdownMenuItem(text = { Text("📌  Pegar «${clip.name.take(24)}» después") }, onClick = { open = false; pasteAt(Studio.project, clip, AddTarget(afterId = a.id)) })
                if (a.kind == Kind.REPEAT || a.kind == Kind.WHEN_IMAGE) DropdownMenuItem(text = { Text("📌  Pegar dentro del bloque") }, onClick = { open = false; pasteAt(Studio.project, clip, AddTarget(parentId = a.id)) })
                if (a.kind == Kind.IF_IMAGE || a.kind == Kind.IF_COLOR) {
                    DropdownMenuItem(text = { Text("📌  Pegar dentro de «✔ Si se ve»") }, onClick = { open = false; pasteAt(Studio.project, clip, AddTarget(parentId = a.id)) })
                    DropdownMenuItem(text = { Text("📌  Pegar dentro de «✖ Si NO se ve»") }, onClick = { open = false; pasteAt(Studio.project, clip, AddTarget(parentId = a.id, otherwise = true)) })
                }
            }
            if (isSimpleRule(a)) DropdownMenuItem(text = { Text("＋  Hacer más cosas cuando salga") }, onClick = { open = false; onAdd(AddTarget(parentId = a.id)) })
            if (parentOf(Studio.project.actions, a.id) != null) DropdownMenuItem(text = { Text("⤴  Sacar del bloque") }, onClick = { open = false; change(moveOutOfBlock(Studio.project.actions, a.id)) })
            DropdownMenuItem(text = { Text(if (a.enabled) "⏸  Desactivar" else "✅  Activar") }, onClick = { open = false; change(updateAction(Studio.project.actions, a.id) { it.copy(enabled = !it.enabled) }) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("🗑  Borrar", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; change(updateAction(Studio.project.actions, a.id) { null }) })
        }
    }
}

@Composable fun AddStepDialog(onDismiss: () -> Unit, onChoose: (Kind) -> Unit) {
    // Arriba, en grande, los pasos de siempre. El resto queda plegado en «Más tipos de paso».
    var more by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.92f), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("¿Qué hace este paso?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                }
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    androidx.compose.foundation.lazy.grid.GridCells.Adaptive(250.dp), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    basicKinds.forEach { kind -> item { KindTile(kind, big = true) { onChoose(kind) } } }
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = { more = !more }) { Text(if (more) "▲ Menos tipos de paso" else "▼ Más tipos de paso (reloj, si se ve…, repetir, color, botones de Android)") }
                    }
                    if (more) kindGroups.forEach { (group, kinds) ->
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SectionTitle(group) }
                        kinds.forEach { kind -> item { KindTile(kind, big = false) { onChoose(kind) } } }
                    }
                }
            }
        }
    }
}

@Composable private fun KindTile(kind: Kind, big: Boolean, onClick: () -> Unit) {
    val info = kindInfo(kind)
    Row(
        Modifier.fillMaxWidth().clip8().background(if (big) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(if (big) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(info.icon, style = MaterialTheme.typography.headlineSmall)
        Column {
            Text(info.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(info.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Cómo empieza y cuántas veces se repite la macro, y la sensibilidad de la búsqueda. */
@Composable private fun MacroSettingsDialog(c: AppController, onDismiss: () -> Unit) {
    val p = Studio.project
    var startOnImage by remember { mutableStateOf(p.startOnImage) }
    // 0 = sin fin, 1 = una vez, 2 = varias veces.
    var howMany by remember { mutableIntStateOf(when (p.repeatCount) { 0 -> 0; 1 -> 1; else -> 2 }) }
    val endless = howMany == 0
    var keepGoing by remember { mutableStateOf(p.keepGoing) }
    var repeat by remember { mutableStateOf(if (p.repeatCount > 1) p.repeatCount.toString() else "5") }
    var minutes by remember { mutableStateOf(p.maxMinutes.toString()) }
    var interval by remember { mutableStateOf(secondsText(p.intervalMs)) }
    var delay by remember { mutableStateOf(secondsText(p.delayMs)) }
    var timeout by remember { mutableStateOf(secondsText(p.timeoutMs)) }
    var mode by remember { mutableStateOf(p.mode) }
    var threshold by remember { mutableFloatStateOf(p.threshold.toFloat()) }
    var picking by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val repeatN = when (howMany) { 0 -> 0; 1 -> 1; else -> repeat.toIntOrNull()?.takeIf { it in 1..1000 } }
    val minutesN = minutes.toIntOrNull()?.takeIf { it in 0..1440 }
    val intervalMs = parseSeconds(interval)?.takeIf { it in 0..3_600_000 }
    val delayMs = parseSeconds(delay)?.takeIf { it in 0..600_000 }
    val timeoutMs = parseSeconds(timeout)?.takeIf { it in 1000..3_600_000 }
    val valid = repeatN != null && minutesN != null && intervalMs != null && delayMs != null && timeoutMs != null
    fun collect(base: Project) = base.copy(startOnImage = startOnImage && base.template != null, repeatCount = repeatN!!, keepGoing = keepGoing, maxMinutes = minutesN!!, intervalMs = intervalMs!!, delayMs = delayMs!!, timeoutMs = timeoutMs!!, mode = mode, threshold = threshold.toDouble().coerceIn(.7, 1.0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes de la macro") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("🔁 Cuántas veces")
                ChoiceChips(listOf(1 to "Una vez", 2 to "Varias veces", 0 to "Sin fin (hasta ■)"), howMany) {
                    // «Sin fin» es sin fin de verdad: quita la parada de seguridad de serie (30 min), que la paraba sola.
                    if (it == 0 && howMany != 0 && minutes == "30") minutes = "0"
                    howMany = it
                }
                if (howMany == 2) OutlinedTextField(repeat, { repeat = it.filter(Char::isDigit).take(4) }, label = { Text("Vueltas (1–1000)") }, isError = repeatN == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                SectionTitle("🚩 Cuándo empieza")
                ChoiceChips(listOf(false to "Al pulsar ▶", true to "Cuando se vea una imagen"), startOnImage) { startOnImage = it }
                if (startOnImage) {
                    val start = p.templates.firstOrNull { it.template.file == p.template?.file }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TemplateThumb(start, Modifier.size(90.dp, 62.dp))
                        Text(start?.name ?: if (p.template != null) "Imagen de inicio" else "Sin imagen elegida", color = if (p.template == null) Warning else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (valid) Studio.update(collect(p)); onDismiss(); c.captureFor(Studio.START_IMAGE_TARGET) }) { Text("📷 Capturar") }
                        OutlinedButton(onClick = { picking = true }, enabled = p.templates.isNotEmpty()) { Text("🖼 Mis imágenes") }
                    }
                }
                TextButton(onClick = { more = !more }, contentPadding = PaddingValues(0.dp)) { Text(if (more) "▲ Menos ajustes" else "▼ Más ajustes (fallos, pausas, búsqueda de imágenes)") }
                if (more) {
                    if (endless) {
                        Text("Si un paso falla o la macro se para sola", style = MaterialTheme.typography.bodyMedium)
                        ChoiceChips(listOf(true to "Volver a empezar", false to "Parar la macro"), keepGoing) { keepGoing = it }
                        if (keepGoing) Hint("Si se para sin que pulses ■ (por ejemplo, Android corta la app), a los 10 s vuelve a darle a ▶ sola.")
                    }
                    if (howMany != 1) OutlinedTextField(interval, { interval = it.take(8) }, label = { Text("Pausa entre vueltas (s)") }, isError = intervalMs == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(delay, { delay = it.take(8) }, label = { Text("Espera antes de empezar (s)") }, isError = delayMs == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Parar sola a los … minutos (0 = nunca)") }, isError = minutesN == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SectionTitle("🔍 Búsqueda de imágenes")
                    ChoiceChips(listOf(SearchMode.ANYWHERE to "En toda la pantalla", SearchMode.EXACT to "Solo en su sitio original"), mode) { mode = it }
                    Text("Parecido exigido: ${(threshold * 100).toInt()} % (recomendado 93–95 %)", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = threshold, onValueChange = { threshold = it }, valueRange = .7f..1f)
                    if (threshold < .92f) Text("⚠ Por debajo de 92 % puede confundir imágenes parecidas.", color = Warning, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(timeout, { timeout = it.take(8) }, label = { Text("Tiempo máximo buscando una imagen (s)") }, isError = timeoutMs == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
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

/** Pega una copia nueva del paso copiado/cortado. Sin destino, al final de la macro. */
fun pasteAt(p: Project, clip: Action, target: AddTarget?) {
    val copy = clip.duplicate()
    val list = when {
        target == null -> p.actions + copy
        target.beforeId != null -> insertBefore(p.actions, target.beforeId, copy)
        target.afterId != null -> insertAfter(p.actions, target.afterId, copy)
        target.parentId != null -> addInside(p.actions, target.parentId, target.otherwise, copy)
        else -> p.actions + copy
    }
    if (Studio.update(p.copy(actions = list))) {
        Studio.status = "Pegado: ${clip.name}"
        if (Studio.clipboardCut) { Studio.clipboard = null; Studio.clipboardCut = false }
        target?.parentId?.let { Studio.collapsed = Studio.collapsed - it }
    }
}

/** Marca o desmarca un paso; un bloque se marca con todo lo de dentro. */
private fun toggleSelected(a: Action) {
    val ids = allActionIds(listOf(a))
    Studio.selectedSteps = if (a.id in Studio.selectedSteps) Studio.selectedSteps - ids else Studio.selectedSteps + ids
}

/** Barra del modo seleccionar: todos, ninguno y borrar. */
@Composable private fun SelectBar(p: Project, busy: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    val all = allActionIds(p.actions)
    val chosen = Studio.selectedSteps.intersect(all)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("☑ ${chosen.size} de ${all.size} paso(s) seleccionado(s). Toca los pasos para marcarlos.", style = MaterialTheme.typography.bodySmall)
            FlowRowCompat {
                TextButton(onClick = { Studio.selectedSteps = all }) { Text("☑ Todos") }
                TextButton(onClick = { Studio.selectedSteps = emptySet() }, enabled = chosen.isNotEmpty()) { Text("☐ Ninguno") }
                Button(onClick = { confirm = true }, enabled = chosen.isNotEmpty() && !busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("🗑 Borrar (${chosen.size})") }
                TextButton(onClick = { Studio.selecting = false; Studio.selectedSteps = emptySet() }) { Text("✓ Listo") }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(if (chosen.size == all.size) "¿Borrar todos los pasos?" else "¿Borrar ${chosen.size} paso(s)?") },
        text = { Text("Las imágenes de la macro no se borran. Si te equivocas, ⋮ › ↶ Deshacer.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (Studio.update(Studio.project.copy(actions = removeActions(Studio.project.actions, chosen)))) { Studio.selectedSteps = emptySet(); Studio.selecting = false }
        }) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } }
    )
}

/** «🔁 Cambiar lo que hace»: otra orden para el mismo paso, con la misma imagen y el mismo sitio del toque. */
@Composable private fun ChangeOrderDialog(a: Action, onDismiss: () -> Unit, onChoose: (ImageOrder) -> Unit) {
    val current = orderOf(a)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Qué hace cuando ve la imagen?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint("La imagen y el sitio donde toca se quedan como están.")
                possibleOrders(a).forEach { o ->
                    if (o == current) Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("✓ ${o.label}") }
                    else OutlinedButton(onClick = { onChoose(o) }, modifier = Modifier.fillMaxWidth()) { Text(o.label) }
                }
                if (hasInnerSteps(a)) Hint("Tiene pasos dentro: para «Tocarla» o «Esperar», saca antes esos pasos.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

private fun closesGame(items: List<Action>): Boolean = items.any { it.enabled && (it.kind == Kind.CLOSE_ACTIVE || closesGame(it.children) || closesGame(it.otherwise)) }

/** PC Windows sin saber aún dónde está «Cerrar todo»: avisa de que se buscará solo al pulsar ▶. */
@Composable private fun WhichDeviceCard(c: AppController) {
    val ctx = c.activity
    val changes = Studio.deviceChanges
    val ready = remember(changes) { es.macrostudio.engine.ThisDevice.ready(ctx) }
    if (ready) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🖥 PC de Windows: «Cerrar todo» se busca solo", style = MaterialTheme.typography.titleMedium)
            Hint("Al pulsar ▶ la primera vez, abro Recientes, busco «Cerrar todo» y sigo. Si no lo encuentro, te pido que lo toques una vez.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.markClearAll() }, modifier = Modifier.weight(1f)) { Text("📍 Buscarlo ya") }
                TextButton(onClick = { es.macrostudio.engine.ThisDevice.setKind(ctx, es.macrostudio.engine.ThisDevice.MAC); c.toast("✓ MacBook Air: cerrará el juego deslizando su tarjeta") }) { Text("Es el MacBook") }
            }
        }
    }
}
