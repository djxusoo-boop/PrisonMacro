package es.macrostudio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.macrostudio.core.*
import java.io.File
import java.util.UUID

/** Biblioteca de imágenes de la macro abierta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImagesScreen(c: AppController) {
    val p = Studio.project
    var renaming by remember { mutableStateOf<SavedTemplate?>(null) }
    var deleting by remember { mutableStateOf<SavedTemplate?>(null) }
    BackHandler { c.open(Screen.EDITOR) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { c.open(Screen.EDITOR) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver al editor") } },
                title = { Column { Text("Imágenes"); Text(p.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.captureFor(null) }, modifier = Modifier.weight(1f)) { Text("📷 Capturar pantalla") }
                    OutlinedButton(onClick = { c.importImageFor(null) }, modifier = Modifier.weight(1f)) { Text("📁 Desde galería") }
                }
            }
        }
    ) { padding ->
        if (p.templates.isEmpty()) {
            Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Todavía no hay imágenes", style = MaterialTheme.typography.titleLarge)
                Text("Las imágenes sirven para que la macro reconozca botones o pantallas. Pulsa «📷 Capturar pantalla»: irás a tu juego con un panel flotante; pulsa Capturar y recorta el botón.")
                Hint("También se crean automáticamente al capturar desde un paso «Buscar imagen y tocarla», «Esperar a ver una imagen» o «Si se ve una imagen».")
            }
        } else LazyVerticalGrid(
            GridCells.Adaptive(170.dp), Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(p.templates, key = { it.id }) { saved ->
                val used = actionUsesTemplate(p.actions, saved.id)
                val isStart = p.startOnImage && p.template?.file == saved.template.file
                Card {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TemplateThumb(saved, Modifier.fillMaxWidth().height(96.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(saved.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Hint(when { isStart -> "🚩 Imagen de inicio"; used -> "Usada en la macro"; else -> "Sin usar" } + " · ${saved.template.width}×${saved.template.height}")
                            }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones de ${saved.name}") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("🏷  Cambiar nombre") }, onClick = { menu = false; renaming = saved })
                                    DropdownMenuItem(text = { Text("✂  Recortar más") }, onClick = { menu = false; c.recropImage(saved.id, null) })
                                    DropdownMenuItem(text = { Text("👁  ¿Se ve ahora?") }, onClick = { menu = false; c.testImage(saved.id) })
                                    DropdownMenuItem(text = { Text("🚩  Usar como imagen de inicio") }, onClick = { menu = false; Studio.update(p.copy(template = saved.template, startOnImage = true)) })
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(if (used || isStart) "🗑  Borrar (está en uso)" else "🗑  Borrar", color = if (used || isStart) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) },
                                        enabled = !used && !isStart, onClick = { menu = false; deleting = saved }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { saved -> NameDialog("Nombre de la imagen", saved.name, "Guardar", onDismiss = { renaming = null }) { name ->
        renaming = null
        Studio.update(Studio.project.copy(templates = Studio.project.templates.map { if (it.id == saved.id) it.copy(name = name) else it }))
    } }
    deleting?.let { saved -> AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("¿Borrar «${saved.name}»?") },
        confirmButton = { TextButton(onClick = {
            deleting = null
            val project = Studio.project
            val keepTemplate = project.template?.takeIf { it.file != saved.template.file }
            if (Studio.update(project.copy(template = keepTemplate, startOnImage = project.startOnImage && keepTemplate != null, templates = project.templates.filterNot { it.id == saved.id })))
                es.macrostudio.projects.Backup.deleteOrphanImages(Studio.context, Studio.projects)
        }) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
    ) }
}

/** Selector de imagen: las de esta macro y, debajo, las de otras macros (se copian al elegirlas). */
@Composable fun ImagePickerDialog(selectedId: String?, onDismiss: () -> Unit, onChoose: (SavedTemplate) -> Unit) {
    var query by remember { mutableStateOf("") }
    val p = Studio.project
    fun matches(t: SavedTemplate) = query.isBlank() || t.name.contains(query.trim(), ignoreCase = true)
    val others = Studio.projects.filter { it.id != p.id }.flatMap { other -> other.templates.filter(::matches).map { other to it } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elegir imagen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.templates.size + others.size > 6) OutlinedTextField(query, { query = it.take(60) }, label = { Text("Buscar por nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (p.templates.isEmpty()) Hint("Esta macro todavía no tiene imágenes. Cierra y usa «📷 Capturar».")
                p.templates.filter(::matches).forEach { saved -> PickerRow(saved, saved.id == selectedId, null) { onChoose(saved) } }
                if (others.isNotEmpty()) {
                    SectionTitle("De otras macros")
                    Hint("Al elegir una, se copia a esta macro.")
                    others.forEach { (other, saved) ->
                        PickerRow(saved, false, other.name) {
                            runCatching {
                                val newFile = "template-${UUID.randomUUID()}.png"
                                File(Studio.context.filesDir, saved.template.file).copyTo(File(Studio.context.filesDir, newFile))
                                val copy = saved.copy(id = UUID.randomUUID().toString(), template = saved.template.copy(file = newFile))
                                if (Studio.update(Studio.project.copy(templates = Studio.project.templates + copy))) onChoose(copy) else File(Studio.context.filesDir, newFile).delete()
                            }.onFailure { Studio.fail("No se pudo copiar la imagen: ${it.message}") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable private fun PickerRow(saved: SavedTemplate, selected: Boolean, from: String?, onClick: () -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TemplateThumb(saved, Modifier.size(80.dp, 54.dp))
            Column(Modifier.weight(1f)) {
                Text(saved.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(from?.let { "de «$it»" } ?: "${saved.template.width}×${saved.template.height} px")
            }
            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
        }
    }
}
