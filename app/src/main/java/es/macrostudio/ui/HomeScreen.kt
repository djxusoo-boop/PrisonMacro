package es.macrostudio.ui

import androidx.compose.foundation.clickable
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
    val busy = Studio.running || Studio.recording
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrandMark(34.dp)
                    Column { Text("PrisonMacro"); Text("Mis macros · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } },
                actions = { IconButton(onClick = { c.open(Screen.SETTINGS) }) { Icon(Icons.Default.Settings, "Ajustes y copias de seguridad") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Nueva macro") })
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { PermissionsCard(c) }
            if (Studio.projects.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Todavía no tienes macros", style = MaterialTheme.typography.titleLarge)
                        Text("Pulsa «Nueva macro». Dentro podrás grabar tus toques o añadir pasos uno a uno: tocar, esperar, buscar una imagen…")
                        Text("¿Tienes una copia de seguridad? Restáurala desde ⚙ Ajustes.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(Studio.projects, key = { it.id }) { p -> MacroCard(c, p, busy, onRename = { renaming = p }, onDelete = { deleting = p }) }
        }
    }
    if (creating) NameDialog("Nueva macro", "Macro ${Studio.projects.size + 1}", "Crear", onDismiss = { creating = false }) { name ->
        creating = false; Studio.create(name); c.open(Screen.EDITOR)
    }
    renaming?.let { p -> NameDialog("Cambiar nombre", p.name, "Guardar", onDismiss = { renaming = null }) { name ->
        renaming = null
        if (p.id == Studio.project.id) Studio.update(Studio.project.copy(name = name))
        else runCatching { Studio.saveAll(listOf(p.copy(name = name))) }.onFailure { Studio.fail("No se pudo cambiar el nombre: ${it.message}") }
    } }
    deleting?.let { p -> AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("¿Borrar «${p.name}»?") },
        text = { Text("Se borrará la macro y sus imágenes. Si quieres conservarla, expórtala antes o haz una copia de seguridad.") },
        confirmButton = { TextButton(onClick = { deleting = null; Studio.delete(p.id) }) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
    ) }
}

@Composable private fun MacroCard(c: AppController, p: Project, busy: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    val active = p.id == Studio.project.id
    val issues = remember(p) { p.executionIssues() }
    Card(
        Modifier.fillMaxWidth().clickable(enabled = !busy) { c.openEditor(p) },
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("🗑  Borrar", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Los dos permisos que necesita la app, con un botón para cada uno. Se oculta cuando todo está listo. */
@Composable fun PermissionsCard(c: AppController, alwaysShow: Boolean = false) {
    if (!alwaysShow && Studio.accessibilityReady && Studio.captureReady) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!alwaysShow) Text("Antes de empezar", style = MaterialTheme.typography.titleMedium)
            PermissionRow(
                "1. Accesibilidad", "Permite tocar la pantalla y mostrar el panel flotante.",
                Studio.accessibilityReady, if (Studio.accessibilityReady) "Ajustes" else "Activar"
            ) { c.openAccessibilitySettings() }
            PermissionRow(
                "2. Captura de pantalla", "Necesaria para buscar imágenes y colores. Android la pide cada vez que se reinicia.",
                Studio.captureReady, if (Studio.captureReady) "Detener" else "Autorizar"
            ) { if (Studio.captureReady) c.stopCapture() else c.requestCaptureOnly() }
        }
    }
}

@Composable private fun PermissionRow(title: String, text: String, ok: Boolean, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); StatusPill(ok, "listo", "pendiente") }
            Hint(text)
        }
        if (ok) OutlinedButton(onClick = onClick) { Text(button) } else Button(onClick = onClick) { Text(button) }
    }
}

@Composable fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
