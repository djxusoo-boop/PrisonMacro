package es.macrostudio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.macrostudio.BuildConfig
import es.macrostudio.core.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HomeScreen(c: AppController) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    // Modo seleccionar: borrar varias macros de golpe (para empezar de cero).
    var selecting by remember { mutableStateOf(false) }
    var marked by remember { mutableStateOf(setOf<String>()) }
    var confirmBulk by remember { mutableStateOf(false) }
    val busy = Studio.running || Studio.recording
    LaunchedEffect(Unit) { es.macrostudio.update.Updater.checkOnStart(c.activity) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrandMark(34.dp)
                    Column { Text("PrisonMacro", maxLines = 1, softWrap = false); Text("Mis macros · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                } },
                actions = {
                    TextButton(onClick = { es.macrostudio.update.Updater.checkNow(c.activity) { c.toast(it) } }, enabled = !es.macrostudio.update.Updater.busy, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("⬆ Actualizar") }
                    if (Studio.projects.isNotEmpty()) FilledTonalButton(onClick = { creating = true }, modifier = Modifier.padding(end = 4.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Nueva") }
                    IconButton(onClick = { c.open(Screen.SETTINGS) }) { Icon(Icons.Default.Settings, "Ajustes y copias de seguridad") }
                    if (Studio.projects.size > 1 && !busy) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("☑  Borrar varias macros") }, onClick = { menu = false; selecting = true; marked = emptySet() })
                            }
                        }
                    }
                }
            )
        },
    ) { padding ->
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // Arrastrar una macro por su asa ⠿ la cambia de sitio (el orden se guarda en este aparato).
        val reorder = rememberReorder(listState) { dragged, over ->
            val ids = Studio.projects.map { it.id }
            val to = ids.indexOf(over as? String)
            if (to < 0 || dragged !in ids) false else { Studio.moveProject(dragged as String, to); true }
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            es.macrostudio.update.Updater.available?.let { info -> item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🎉 Hay una versión nueva: ${info.versionName}", style = MaterialTheme.typography.titleMedium)
                        Text("Tienes la ${BuildConfig.VERSION_NAME}. Tus macros e imágenes se conservan al actualizar.", style = MaterialTheme.typography.bodySmall)
                        if (es.macrostudio.update.Updater.message.isNotBlank()) Text(es.macrostudio.update.Updater.message, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { es.macrostudio.update.Updater.installAvailable(c.activity) { e -> if (e != null) c.toast(e) } }, enabled = !es.macrostudio.update.Updater.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (es.macrostudio.update.Updater.busy) "Descargando…" else "⬇ Instalar ahora")
                        }
                    }
                }
            } }
            if (es.macrostudio.update.Updater.available == null && es.macrostudio.update.Updater.busy) item {
                Text("⬆ ${es.macrostudio.update.Updater.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (Studio.running) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("▶ «${Studio.project.name}» se está ejecutando", style = MaterialTheme.typography.titleSmall)
                            Text(Studio.status, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        Button(onClick = { es.macrostudio.engine.Engine.stop("Parada manual") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("■ Parar") }
                    }
                }
            }
            item { PermissionsCard(c) }
            if (selecting && !busy) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("☑ ${marked.size} de ${Studio.projects.size} macro(s) marcadas. Toca las macros para marcarlas.", style = MaterialTheme.typography.bodySmall)
                        FlowRowCompat {
                            TextButton(onClick = { marked = if (marked.size == Studio.projects.size) emptySet() else Studio.projects.map { it.id }.toSet() }) { Text(if (marked.size == Studio.projects.size) "☐ Ninguna" else "☑ Todas") }
                            Button(onClick = { confirmBulk = true }, enabled = marked.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("🗑 Borrar (${marked.size})") }
                            TextButton(onClick = { selecting = false; marked = emptySet() }) { Text("✓ Listo") }
                        }
                    }
                }
            }
            if (Studio.projects.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Todavía no tienes macros", style = MaterialTheme.typography.titleLarge)
                        Text("Dentro podrás grabar tus toques o añadir pasos uno a uno: tocar, esperar, buscar una imagen…")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Crear mi primera macro") }
                            OutlinedButton(onClick = { c.open(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text("♻ Restaurar una copia o recibir macros") }
                        }
                    }
                }
            }
            items(Studio.projects, key = { it.id }) { p ->
                if (selecting) {
                    val on = p.id in marked
                    Card(Modifier.fillMaxWidth().clickable { marked = if (on) marked - p.id else marked + p.id },
                        colors = CardDefaults.cardColors(containerColor = if (on) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, onCheckedChange = { marked = if (on) marked - p.id else marked + p.id })
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${countActions(p.actions)} paso(s) · ${p.templates.size} imagen(es)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else Box(reorder.itemModifier(p.id)) { MacroCard(c, p, busy, reorder, onRename = { renaming = p }, onDelete = { deleting = p }) }
            }
        }
    }
    if (creating) NewMacroDialog(c, onDismiss = { creating = false })
    renaming?.let { p -> NameDialog("Cambiar nombre", p.name, "Guardar", onDismiss = { renaming = null }) { name ->
        renaming = null
        if (p.id == Studio.project.id) Studio.update(Studio.project.copy(name = name))
        else runCatching { Studio.saveAll(listOf(p.copy(name = name))) }.onFailure { Studio.fail("No se pudo cambiar el nombre: ${it.message}") }
    } }
    if (confirmBulk) AlertDialog(
        onDismissRequest = { confirmBulk = false },
        title = { Text("¿Borrar ${marked.size} macro(s)?") },
        text = { Text("Se borran con sus pasos y sus imágenes recortadas. Tus capturas enteras («📸 Mis capturas») no se tocan. No se puede deshacer: si quieres guardarlas, haz antes una copia en ⚙ Ajustes.") },
        confirmButton = { TextButton(onClick = {
            confirmBulk = false
            val n = marked.size
            marked.forEach { Studio.delete(it) }
            marked = emptySet(); selecting = false
            c.toast("🗑 $n macro(s) borrada(s)")
        }) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Cancelar") } }
    )
    deleting?.let { p -> AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("¿Borrar «${p.name}»?") },
        text = { Text("Se borrará la macro y sus imágenes. Si quieres conservarla, expórtala antes o haz una copia de seguridad.") },
        confirmButton = { TextButton(onClick = { deleting = null; Studio.delete(p.id) }) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
    ) }
}

@Composable private fun MacroCard(c: AppController, p: Project, busy: Boolean, reorder: Reorder, onRename: () -> Unit, onDelete: () -> Unit) {
    val active = p.id == Studio.project.id
    val issues = remember(p) { p.executionIssues() }
    Card(
        Modifier.fillMaxWidth().clickable(enabled = !busy) { c.openEditor(p) },
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(start = 2.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (Studio.projects.size > 1 && !busy) DragHandle(reorder, p.id, p.name) else Spacer(Modifier.width(4.dp))
            val start = p.templates.firstOrNull { it.template.file == p.template?.file }
            if (p.startOnImage) TemplateThumb(start, Modifier.size(54.dp, 40.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${countActions(p.actions)} paso(s) · ${p.templates.size} imagen(es)" +
                        (if (p.repeatCount == 0) " · sin fin" else if (p.repeatCount > 1) " · ×${p.repeatCount}" else "") +
                        (if (p.startOnImage) " · empieza al ver imagen" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (issues.isNotEmpty()) Text("⚠ ${issues.first()}", style = MaterialTheme.typography.labelSmall, color = Warning, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledIconButton(onClick = { c.play(p) }, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.PlayArrow, "Reproducir ${p.name}") }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Opciones de ${p.name}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("✏️  Editar") }, onClick = { menu = false; c.openEditor(p) })
                    DropdownMenuItem(text = { Text("🏷  Cambiar nombre") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("⧉  Duplicar") }, onClick = {
                        menu = false
                        runCatching { Studio.saveAll(listOf(p.copy(id = UUID.randomUUID().toString(), name = (p.name + " (copia)").take(100)))) }
                            .onSuccess { c.toast("Macro duplicada") }.onFailure { Studio.fail("No se pudo duplicar: ${it.message}") }
                    })
                    DropdownMenuItem(text = { Text("📤  Exportar / compartir") }, onClick = { menu = false; c.exportProject(p) })
                    val index = Studio.projects.indexOfFirst { it.id == p.id }
                    val last = Studio.projects.lastIndex
                    if (last > 0) {
                        HorizontalDivider()
                        if (index > 0) {
                            DropdownMenuItem(text = { Text("⏫  Arriba del todo") }, onClick = { menu = false; Studio.moveProject(p.id, 0) })
                            DropdownMenuItem(text = { Text("⬆  Subir") }, onClick = { menu = false; Studio.moveProject(p.id, index - 1) })
                        }
                        if (index in 0 until last) {
                            DropdownMenuItem(text = { Text("⬇  Bajar") }, onClick = { menu = false; Studio.moveProject(p.id, index + 1) })
                            DropdownMenuItem(text = { Text("⏬  Abajo del todo") }, onClick = { menu = false; Studio.moveProject(p.id, last) })
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("🗑  Borrar", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Los dos permisos que necesita la app, con un botón para cada uno. Se oculta cuando todo está listo. */
@Composable fun PermissionsCard(c: AppController, alwaysShow: Boolean = false) {
    // En el inicio solo hace falta la accesibilidad: la captura la pide la app sola cuando
    // se necesita (Android la retira en cada reinicio y el aviso no se iba nunca).
    if (!alwaysShow && Studio.accessibilityReady) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!alwaysShow) Text("Antes de empezar", style = MaterialTheme.typography.titleMedium)
            PermissionRow(
                if (alwaysShow) "1. Accesibilidad" else "Accesibilidad", "Permite tocar la pantalla y mostrar el panel flotante.",
                Studio.accessibilityReady, if (Studio.accessibilityReady) "Ajustes" else "Activar"
            ) { c.openAccessibilitySettings() }
            if (alwaysShow) PermissionRow(
                "2. Captura de pantalla", "Necesaria para buscar imágenes y colores. Android la pide cada vez que se reinicia.",
                Studio.captureReady, if (Studio.captureReady) "Detener" else "Autorizar"
            ) { if (Studio.captureReady) c.stopCapture() else c.requestCaptureOnly() }
            else Hint("La captura de pantalla (para buscar imágenes) la pedirá la app sola cuando una macro la necesite.")
        }
    }
}

@Composable private fun PermissionRow(title: String, text: String, ok: Boolean, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); StatusPill(ok, "listo", "falta") }
            Hint(text)
        }
        if (ok) OutlinedButton(onClick = onClick) { Text(button) } else Button(onClick = onClick) { Text(button) }
    }
}

/** «Nueva macro»: rápida (con 4 preguntas) o vacía (paso a paso). */
@Composable private fun NewMacroDialog(c: AppController, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf("") }
    when (mode) {
        "quick" -> QuickMacroDialog(c, onDismiss)
        // Vacía: sin preguntar el nombre (se cambia tocándolo arriba en el editor).
        "empty" -> LaunchedEffect(Unit) { Studio.create("Macro ${Studio.projects.size + 1}"); c.open(Screen.EDITOR); onDismiss() }
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nueva macro") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceTile("⚡", "Rápida", "Abrir el juego, jugar, cerrarlo y repetir. Te pregunto 4 cosas y la hago yo.") { mode = "quick" }
                    ChoiceTile("📝", "Vacía", "La hago yo paso a paso.") { mode = "empty" }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
        )
    }
}

@Composable private fun ChoiceTile(icon: String, title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip8().background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(icon, style = MaterialTheme.typography.headlineSmall)
        Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(text, style = MaterialTheme.typography.bodySmall) }
    }
}

/** «⚡ Macro rápida»: 4 preguntas y la macro de siempre queda hecha (las imágenes, después). */
@Composable private fun QuickMacroDialog(c: AppController, onDismiss: () -> Unit) {
    var game by remember { mutableStateOf("Zooba") }
    var byIcon by remember { mutableStateOf(true) }
    var play by remember { mutableStateOf("15 s") }
    var every by remember { mutableStateOf("4 min") }
    var popups by remember { mutableStateOf(true) }
    val playMs = parseSeconds(play)?.takeIf { it in 1_000..600_000 }
    val everyMs = parseSeconds(every)?.takeIf { it in 0..4_200_000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚡ Macro rápida") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Abre el juego, juega un rato, lo cierra y vuelve a empezar, sin fin. Las imágenes las eliges después.")
                OutlinedTextField(game, { game = it.take(40) }, label = { Text("1. ¿Qué juego?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("2. ¿Cómo se abre?", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(listOf(true to "🎯 Tocando su icono", false to "📱 Abriendo la app"), byIcon) { byIcon = it }
                SecondsField("3. ¿Cuánto juega antes de cerrarlo?", play, listOf("15", "30", "60", "300")) { play = it }
                SecondsField("4. ¿Cuánto espera con el juego cerrado?", every, listOf("60", "240", "300", "600")) { every = it }
                Text("¿Salen ventanas o anuncios que haya que cerrar (la X)?", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(listOf(true to "Sí", false to "No"), popups) { popups = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Studio.create(game)
                if (Studio.update(QuickMacro.build(Studio.project, game, byIcon, playMs!!, everyMs!!, popups), history = false)) {
                    onDismiss(); c.open(Screen.EDITOR)
                    c.toast("✓ Macro hecha. Ahora elige las imágenes que faltan (botones de arriba).")
                }
            }, enabled = game.isNotBlank() && playMs != null && everyMs != null) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { if (it.text.length <= 100) name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus)) },
        confirmButton = { TextButton(onClick = { onConfirm(name.text.trim()) }, enabled = name.text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
