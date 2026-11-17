package es.macrostudio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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

/** Biblioteca de imágenes de la macro abierta: capturas enteras y recortes. Con «☑ Seleccionar»
 * se marcan varias (o todas) y se borran de golpe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImagesScreen(c: AppController) {
    val p = Studio.project
    var renaming by remember { mutableStateOf<SavedTemplate?>(null) }
    var deleting by remember { mutableStateOf<SavedTemplate?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var shotsVersion by remember { mutableIntStateOf(0) }
    var deletingShot by remember { mutableStateOf<File?>(null) }
    val allShots = remember(Studio.screenshotVersion, shotsVersion) { es.macrostudio.capture.Shots.list(context) }
    // Carpetas: la abierta filtra lo que se ve y es donde van las capturas y recortes nuevos.
    var folderVersion by remember { mutableIntStateOf(0) }
    val shotFolders = remember(Studio.screenshotVersion, shotsVersion, folderVersion) { es.macrostudio.capture.ShotFolders.load(context) }
    val folders = allFolders(shotFolders, p)
    var filter by remember { mutableStateOf<String?>(Studio.shotFolder.ifBlank { null }) }
    fun openFolder(f: String?) { filter = f; Studio.shotFolder = f.orEmpty() }
    fun inFilter(folder: String, f: String? = filter) = f == null || folder == f
    val shots = allShots.filter { inFilter(shotFolders.folderOf(it)) }
    val images = p.templates.filter { inFilter(it.folder) }
    var moving by remember { mutableStateOf(false) }
    var movingOne by remember { mutableStateOf<SavedTemplate?>(null) }
    fun moveTo(folder: String, shotFiles: Collection<File>, imageIds: Set<String>) {
        if (folder.isNotBlank()) es.macrostudio.capture.ShotFolders.create(context, folder)
        if (shotFiles.isNotEmpty()) es.macrostudio.capture.ShotFolders.assign(context, shotFiles, folder)
        if (imageIds.isNotEmpty()) Studio.update(Studio.project.copy(templates = Studio.project.templates.map { if (it.id in imageIds) it.copy(folder = folder) else it }))
        folderVersion++
        c.toast(if (folder.isBlank()) "Sacadas de su carpeta" else "📁 Movidas a «$folder»")
    }
    var selecting by remember { mutableStateOf(false) }
    var selShots by remember { mutableStateOf(setOf<String>()) }
    var selImages by remember { mutableStateOf(setOf<String>()) }
    var confirmBulk by remember { mutableStateOf(false) }
    fun stopSelecting() { selecting = false; selShots = emptySet(); selImages = emptySet() }
    BackHandler { if (selecting) stopSelecting() else c.open(Screen.EDITOR) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { c.open(Screen.EDITOR) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver al editor") } },
                title = { Column { Text("Imágenes"); Text(p.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions = {
                    if (shots.isNotEmpty() || p.templates.isNotEmpty())
                        TextButton(onClick = { if (selecting) stopSelecting() else selecting = true }) { Text(if (selecting) "✓ Listo" else "☑ Seleccionar") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                if (selecting) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("☑ ${selShots.size} captura(s) y ${selImages.size} recorte(s) marcados. Toca para marcar o desmarcar.", style = MaterialTheme.typography.bodySmall)
                    FlowRowCompat {
                        OutlinedButton(onClick = { selShots = if (selShots.size == shots.size) emptySet() else shots.map { it.path }.toSet() }, enabled = shots.isNotEmpty()) { Text(if (selShots.size == shots.size && shots.isNotEmpty()) "☐ Ninguna captura" else "☑ Todas las capturas") }
                        OutlinedButton(onClick = { selImages = if (selImages.size == images.size) emptySet() else images.map { it.id }.toSet() }, enabled = images.isNotEmpty()) { Text(if (selImages.size == images.size && images.isNotEmpty()) "☐ Ningún recorte" else "☑ Todos los recortes") }
                        OutlinedButton(onClick = { moving = true }, enabled = selShots.isNotEmpty() || selImages.isNotEmpty()) { Text("📁 Mover a carpeta (${selShots.size + selImages.size})") }
                        // Para que en el Mac se puedan ver y recortar (buzón › outputs/capturas).
                        OutlinedButton(onClick = {
                            val files = shots.filter { it.path in selShots }
                            c.toast("Buscando el ordenador…")
                            es.macrostudio.update.Finder.ensure(c.activity, progress = {}) { address ->
                                if (address == null) { c.toast("No encuentro el ordenador: tiene que estar en la misma wifi y con el buzón abierto"); return@ensure }
                                es.macrostudio.update.Updater.sendShots(address, files) { sent, error ->
                                    c.toast(error ?: "✓ Enviadas $sent captura(s) al ordenador")
                                    if (error == null) Studio.log("📸 Enviadas $sent captura(s) al ordenador")
                                }
                            }
                        }, enabled = selShots.isNotEmpty()) { Text("📤 Al ordenador (${selShots.size})") }
                        Button(onClick = { confirmBulk = true }, enabled = selShots.isNotEmpty() || selImages.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("🗑 Borrar (${selShots.size + selImages.size})") }
                    }
                } else Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.startBurst() }, modifier = Modifier.weight(1f)) { Text("📸 Varias capturas") }
                    OutlinedButton(onClick = { c.captureFor(null) }, modifier = Modifier.weight(1f)) { Text("📷 Una") }
                    OutlinedButton(onClick = { c.importImageFor(null) }, modifier = Modifier.weight(1f)) { Text("📁 Galería") }
                }
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            GridCells.Adaptive(170.dp), Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FolderBar(
                        folders, count = { f -> allShots.count { inFilter(shotFolders.folderOf(it), f) } + p.templates.count { inFilter(it.folder, f) } }, filter, ::openFolder,
                        onCreate = { name -> val n = es.macrostudio.capture.ShotFolders.create(context, name); folderVersion++; if (n.isNotBlank()) openFolder(n) },
                        onRename = { old, new ->
                            val n = es.macrostudio.capture.ShotFolders.clean(new)
                            es.macrostudio.capture.ShotFolders.rename(context, old, n)
                            Studio.update(Studio.project.copy(templates = Studio.project.templates.map { if (it.folder == old) it.copy(folder = n) else it }))
                            folderVersion++; openFolder(n)
                        },
                        onDelete = { f ->
                            es.macrostudio.capture.ShotFolders.delete(context, f)
                            Studio.update(Studio.project.copy(templates = Studio.project.templates.map { if (it.folder == f) it.copy(folder = "") else it }))
                            folderVersion++; openFolder(null)
                        }
                    )
                    if (!filter.isNullOrBlank()) Hint("📁 Estás en «$filter»: las capturas y recortes nuevos se guardan aquí.")
                    else if (folders.isEmpty() && (allShots.isNotEmpty() || p.templates.isNotEmpty())) Hint("Ordénalas en carpetas: «＋ Carpeta», y luego «☑ Seleccionar» › «📁 Mover a carpeta».")
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle("📸 Capturas enteras (${shots.size})")
                    if (!selecting) ShotSyncBar()
                    if (shots.isEmpty()) Hint("Pulsa «📸 Varias capturas»: vas al juego, pulsas 📷 Foto en cada pantalla que necesites y ✓ Listo. Luego toca aquí una captura para recortar el botón, sin volver al juego.")
                    else if (!selecting) Hint("Toca una captura para recortar un botón de ella. Para borrar varias: «☑ Seleccionar» arriba.")
                }
            }
            items(shots, key = { it.path }) { file ->
                val marked = file.path in selShots
                Column {
                    Box {
                        ShotThumb(file, Modifier.fillMaxWidth().aspectRatio(16f / 9f).combinedClickableCompat(
                            onClick = { if (selecting) selShots = if (marked) selShots - file.path else selShots + file.path else c.cropShot(file, null) },
                            onLongClick = { selecting = true; selShots = selShots + file.path }
                        ))
                        if (selecting) Checkbox(marked, onCheckedChange = { selShots = if (marked) selShots - file.path else selShots + file.path }, modifier = Modifier.align(Alignment.TopStart))
                    }
                    Text(shotLabel(file) + shotFolders.folderOf(file).let { if (filter == null && it.isNotBlank()) " · 📁 $it" else "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle("✂ Imágenes recortadas de esta macro (${images.size})")
                    if (images.isEmpty()) Hint("Las imágenes sirven para que la macro reconozca botones o pantallas. Recórtalas de tus capturas, o captura una con «📷 Una».")
                }
            }
            items(images, key = { it.id }) { saved ->
                val used = actionUsesTemplate(p.actions, saved.id)
                val isStart = p.startOnImage && p.template?.file == saved.template.file
                val marked = saved.id in selImages
                Card(Modifier.combinedClickableCompat(
                    onClick = { if (selecting) selImages = if (marked) selImages - saved.id else selImages + saved.id },
                    onLongClick = { selecting = true; selImages = selImages + saved.id }
                ), colors = if (marked) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TemplateThumb(saved, Modifier.fillMaxWidth().height(96.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) Checkbox(marked, onCheckedChange = { selImages = if (marked) selImages - saved.id else selImages + saved.id })
                            Column(Modifier.weight(1f)) {
                                Text(saved.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Hint(when { isStart -> "🚩 Imagen de inicio"; used -> "Usada en la macro"; else -> "Sin usar" } + " · ${saved.template.width}×${saved.template.height}" + if (filter == null && saved.folder.isNotBlank()) " · 📁 ${saved.folder}" else "")
                            }
                            if (!selecting) {
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones de ${saved.name}") }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("🏷  Cambiar nombre") }, onClick = { menu = false; renaming = saved })
                                        DropdownMenuItem(text = { Text("📁  Mover a carpeta" + if (saved.folder.isNotBlank()) " (ahora «${saved.folder}»)" else "") }, onClick = { menu = false; movingOne = saved })
                                        DropdownMenuItem(text = { Text("✂  Recortar más") }, onClick = { menu = false; c.recropImage(saved.id, null) })
                                        DropdownMenuItem(text = { Text("👁  ¿Se ve ahora?") }, onClick = { menu = false; c.testImage(saved.id) })
                                        DropdownMenuItem(text = { Text("🚩  Usar como imagen de inicio") }, onClick = { menu = false; Studio.update(p.copy(template = saved.template, startOnImage = true)) })
                                        // Ordenar las imágenes: se mueven entre las que se ven ahora (la carpeta abierta).
                                        val at = images.indexOfFirst { it.id == saved.id }
                                        fun moveTo(target: SavedTemplate) {
                                            val list = Studio.project.templates.toMutableList()
                                            val to = list.indexOfFirst { it.id == target.id }
                                            list.removeAll { it.id == saved.id }; list.add(to.coerceIn(0, list.size), saved)
                                            Studio.update(Studio.project.copy(templates = list))
                                        }
                                        if (at > 0) {
                                            DropdownMenuItem(text = { Text("⏮  La primera") }, onClick = { menu = false; moveTo(images.first()) })
                                            DropdownMenuItem(text = { Text("⬅  Antes") }, onClick = { menu = false; moveTo(images[at - 1]) })
                                        }
                                        if (at in 0 until images.lastIndex) {
                                            DropdownMenuItem(text = { Text("➡  Después") }, onClick = { menu = false; moveTo(images[at + 1]) })
                                            DropdownMenuItem(text = { Text("⏭  La última") }, onClick = { menu = false; moveTo(images.last()) })
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text("🗑  Borrar", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = saved })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deletingShot?.let { f -> ShotDeleteDialog(f, onDismiss = { deletingShot = null }) { shotsVersion++ } }
    if (moving) MoveToFolderDialog(folders, onDismiss = { moving = false }) { folder ->
        moving = false
        moveTo(folder, allShots.filter { it.path in selShots }, selImages)
        stopSelecting()
    }
    movingOne?.let { saved -> MoveToFolderDialog(folders, onDismiss = { movingOne = null }) { folder -> movingOne = null; moveTo(folder, emptyList(), setOf(saved.id)) } }
    renaming?.let { saved -> NameDialog("Nombre de la imagen", saved.name, "Guardar", onDismiss = { renaming = null }) { name ->
        renaming = null
        Studio.update(Studio.project.copy(templates = Studio.project.templates.map { if (it.id == saved.id) it.copy(name = name) else it }))
    } }
    deleting?.let { saved ->
        val used = actionUsesTemplate(p.actions, saved.id) || (p.startOnImage && p.template?.file == saved.template.file)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Borrar «${saved.name}»?") },
            text = { if (used) Text("Se usa en la macro: los pasos que la usan se quedarán sin imagen (tendrás que elegir otra).") },
            confirmButton = { TextButton(onClick = { deleting = null; deleteTemplates(setOf(saved.id)) }) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
        )
    }
    if (confirmBulk) {
        val usedCount = p.templates.count { it.id in selImages && (actionUsesTemplate(p.actions, it.id) || (p.startOnImage && p.template?.file == it.template.file)) }
        val signedIn = es.macrostudio.sync.ShotSync.isSignedIn() && selShots.isNotEmpty()
        fun run(everywhere: Boolean) {
            confirmBulk = false
            val files = shots.filter { it.path in selShots }
            files.forEach { f -> if (everywhere) es.macrostudio.sync.ShotSync.deleteEverywhere(context, f) { } else es.macrostudio.capture.Shots.delete(f) }
            if (selImages.isNotEmpty()) deleteTemplates(selImages)
            c.toast("🗑 Borradas ${files.size} captura(s) y ${selImages.size} recorte(s)")
            shotsVersion++; Studio.screenshotVersion++
            stopSelecting()
        }
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text("¿Borrar ${selShots.size} captura(s) y ${selImages.size} recorte(s)?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (usedCount > 0) Text("⚠ $usedCount recorte(s) se usan en pasos: esos pasos se quedarán sin imagen.")
                    if (signedIn) Text("«En todos mis aparatos» quita las capturas también de tu cuenta y de tus otros aparatos.")
                    Text("Esto no se puede deshacer.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                if (signedIn) TextButton(onClick = { run(true) }) { Text("Borrar en todos mis aparatos", color = MaterialTheme.colorScheme.error) }
                else TextButton(onClick = { run(false) }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    if (signedIn) TextButton(onClick = { run(false) }) { Text("Solo aquí") }
                    TextButton(onClick = { confirmBulk = false }) { Text("Cancelar") }
                }
            }
        )
    }
}

/** Quita recortes de la macro (aunque estén en uso) y borra sus archivos si ya nadie los usa. */
private fun deleteTemplates(ids: Set<String>) {
    if (Studio.update(Studio.project.withoutTemplates(ids))) es.macrostudio.projects.Backup.deleteOrphanImages(Studio.context, Studio.projects)
}

/**
 * Elegir imagen, todo en un sitio y con buscador:
 *  · ✂ Recortadas: las de esta macro y las de tus otras macros (se copian al elegirlas).
 *  · 📸 Capturas enteras: tus capturas guardadas; al tocar una, recortas el botón.
 *  · Abajo: hacer capturas nuevas o sacar una imagen de la galería.
 * Los botones de capturas solo salen si se pasan [onShot], [onCapture]…
 */
@Composable fun ImagePickerDialog(
    selectedId: String?, onDismiss: () -> Unit,
    onShot: ((File) -> Unit)? = null, onCapture: (() -> Unit)? = null, onGallery: (() -> Unit)? = null, onBurst: (() -> Unit)? = null,
    onChoose: (SavedTemplate) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    val p = Studio.project
    val q = query.trim()
    // Carpetas: la elegida filtra y es donde van las capturas nuevas (igual que en «Imágenes»).
    val shotFolders = remember(Studio.screenshotVersion) { es.macrostudio.capture.ShotFolders.load(context) }
    val folders = allFolders(shotFolders, p)
    var filter by remember { mutableStateOf<String?>(Studio.shotFolder.ifBlank { null }) }
    fun inFilter(folder: String, f: String? = filter) = f == null || folder == f
    fun matches(t: SavedTemplate, macro: String = "") = (q.isBlank() || t.name.contains(q, ignoreCase = true) || macro.contains(q, ignoreCase = true) || t.folder.contains(q, ignoreCase = true))
    val mine = p.templates.filter { matches(it) && inFilter(it.folder) }
    // De otras macros: solo las que tienen su archivo, y cada imagen una sola vez (ni repetidas
    // entre macros ni iguales a una de esta macro).
    val othersAll = remember(Studio.projects, p.templates) {
        val dir = Studio.context.filesDir
        fun key(t: SavedTemplate): String? = File(dir, t.template.file).takeIf { it.isFile }?.let { f -> runCatching { java.security.MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { b -> "%02x".format(b) } }.getOrNull() }
        val seen = p.templates.mapNotNull(::key).toMutableSet()
        Studio.projects.filter { it.id != p.id }.flatMap { other -> other.templates.map { other to it } }.filter { (_, t) -> key(t)?.let { seen.add(it) } == true }
    }
    val others = othersAll.filter { (other, t) -> matches(t, other.name) && inFilter(t.folder) }
    val allShots = remember(Studio.screenshotVersion) { es.macrostudio.capture.Shots.list(context) }
    val shots = allShots.filter { (q.isBlank() || shotLabel(it).contains(q, ignoreCase = true) || shotFolders.folderOf(it).contains(q, ignoreCase = true)) && inFilter(shotFolders.folderOf(it)) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // decorFitsSystemWindows=false: así Android le dice dónde están las barras del sistema y
        // los botones de abajo no quedan tapados (en horizontal se cortaban).
        FullScreenDialogWindow()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Elegir imagen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
                OutlinedTextField(query, { query = it.take(60) }, label = { Text("🔍 Buscar (nombre, macro o fecha: «9 nov»)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                if (folders.isNotEmpty()) Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp)) {
                    FolderBar(folders, count = { f -> if (tab == 0) p.templates.count { inFilter(it.folder, f) } else allShots.count { inFilter(shotFolders.folderOf(it), f) } }, filter, { f -> filter = f; Studio.shotFolder = f.orEmpty() })
                }
                if (onShot != null) TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 6.dp)) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("✂ Recortadas (${mine.size + others.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("📸 Capturas enteras (${shots.size})") })
                }
                LazyVerticalGrid(
                    GridCells.Adaptive(if (tab == 0) 150.dp else 170.dp), Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (tab == 0 || onShot == null) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Column { SectionTitle("De esta macro (${mine.size})"); if (mine.isEmpty()) Hint(if (q.isBlank()) "Esta macro todavía no tiene imágenes recortadas." else "Ninguna con «$q».") }
                        }
                        items(mine, key = { "m" + it.id }) { saved -> PickerTile(saved, saved.id == selectedId, null) { onChoose(saved) } }
                        if (others.isNotEmpty()) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Column { SectionTitle("De tus otras macros (${others.size})"); Hint("Al elegir una, se copia a esta macro (si ya la tiene, se usa la misma).") }
                            }
                            items(others, key = { (o, t) -> "o" + o.id + t.id }) { (other, saved) -> PickerTile(saved, false, other.name) { copyFromOtherMacro(saved)?.let(onChoose) } }
                        }
                    } else {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ShotSyncBar()
                                Hint(if (shots.isEmpty()) "No hay capturas" + (if (q.isBlank()) ". Haz «📸 Varias capturas» abajo." else " con «$q».") else "Toca una captura y recorta el botón que quieras.")
                            }
                        }
                        items(shots, key = { it.path }) { file ->
                            Column {
                                ShotThumb(file, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { onShot(file) })
                                Text(shotLabel(file), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (onCapture != null || onGallery != null || onBurst != null) Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        onCapture?.let { Button(onClick = it, modifier = Modifier.weight(1.4f), contentPadding = PaddingValues(4.dp)) { Text("📷 Capturar en el juego") } }
                        onBurst?.let { OutlinedButton(onClick = it, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("📸 Varias") } }
                        onGallery?.let { OutlinedButton(onClick = it, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("📁 Galería") } }
                    }
                }
            }
        }
    }
}

/** Copia a esta macro una imagen de otra macro (o reutiliza la igual que ya tenga). */
private fun copyFromOtherMacro(saved: SavedTemplate): SavedTemplate? {
    val source = File(Studio.context.filesDir, saved.template.file)
    val bytes = runCatching { source.readBytes() }.getOrNull()
    val same = bytes?.let { b -> Studio.project.templates.firstOrNull { t -> runCatching { File(Studio.context.filesDir, t.template.file).readBytes().contentEquals(b) }.getOrDefault(false) } }
    if (same != null) return same
    return runCatching {
        val newFile = "template-${UUID.randomUUID()}.png"
        source.copyTo(File(Studio.context.filesDir, newFile))
        val copy = saved.copy(id = UUID.randomUUID().toString(), template = saved.template.copy(file = newFile))
        if (Studio.update(Studio.project.copy(templates = Studio.project.templates + copy))) copy else { File(Studio.context.filesDir, newFile).delete(); null }
    }.onFailure { Studio.fail("No se pudo copiar la imagen: ${it.message}") }.getOrNull()
}

/** «9 nov 20:08» a partir de «shot-20261109-200812-829». */
fun shotLabel(file: File): String {
    val m = Regex("shot-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})").find(file.name) ?: return file.nameWithoutExtension
    val (_, month, day, h, min) = m.destructured
    val months = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    return "${day.toInt()} ${months.getOrElse(month.toInt() - 1) { month }} $h:$min"
}

@Composable private fun PickerTile(saved: SavedTemplate, selected: Boolean, from: String?, onClick: () -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(6.dp)) {
            TemplateThumb(saved, Modifier.fillMaxWidth().height(80.dp))
            Text((if (selected) "✓ " else "") + saved.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Hint(from?.let { "de «$it»" } ?: "${saved.template.width}×${saved.template.height} px")
        }
    }
}

/** Qué carpeta se está viendo: null = todas, "" = las que no tienen carpeta, o su nombre. */
typealias FolderFilter = String?

/**
 * Fila de carpetas para ordenar capturas y recortes: «Todas», «Sin carpeta», cada carpeta
 * con cuántas imágenes tiene y «＋ Carpeta». Con [onManage], la carpeta abierta se puede
 * renombrar o quitar.
 */
@Composable fun FolderBar(
    folders: List<String>, count: (FolderFilter) -> Int, selected: FolderFilter, onSelect: (FolderFilter) -> Unit,
    onCreate: ((String) -> Unit)? = null, onRename: ((String, String) -> Unit)? = null, onDelete: ((String) -> Unit)? = null
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected == null, { onSelect(null) }, label = { Text("Todas (${count(null)})") })
        FilterChip(selected == "", { onSelect("") }, label = { Text("Sin carpeta (${count("")})") })
        folders.forEach { f -> FilterChip(selected == f, { onSelect(f) }, label = { Text("📁 $f (${count(f)})") }) }
        if (onCreate != null) AssistChip(onClick = { creating = true }, label = { Text("＋ Carpeta") })
        if (!selected.isNullOrBlank() && (onRename != null || onDelete != null)) {
            onRename?.let { TextButton(onClick = { renaming = selected }) { Text("✏ Renombrar") } }
            onDelete?.let { TextButton(onClick = { deleting = selected }) { Text("Quitar carpeta", color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (creating && onCreate != null) NameDialog("Nueva carpeta", "", "Crear", onDismiss = { creating = false }) { creating = false; onCreate(it) }
    renaming?.let { old -> NameDialog("Nombre de la carpeta", old, "Guardar", onDismiss = { renaming = null }) { renaming = null; onRename?.invoke(old, it) } }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Quitar la carpeta «$f»?") },
            text = { Text("Las imágenes que tiene NO se borran: pasan a «Sin carpeta».") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete?.invoke(f) }) { Text("Quitar carpeta", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
        )
    }
}

/** Elegir a qué carpeta mover lo marcado (o crear una nueva, o sacarlo de su carpeta). */
@Composable fun MoveToFolderDialog(folders: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📁 Mover a carpeta") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                folders.forEach { f -> OutlinedButton(onClick = { onPick(f) }, modifier = Modifier.fillMaxWidth()) { Text("📁 $f") } }
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Carpeta nueva…") }
                TextButton(onClick = { onPick("") }, modifier = Modifier.fillMaxWidth()) { Text("Sacar de su carpeta (sin carpeta)") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
    if (creating) NameDialog("Nueva carpeta", "", "Crear y mover", onDismiss = { creating = false }) { creating = false; onPick(es.macrostudio.capture.ShotFolders.clean(it)) }
}

/** Todas las carpetas: las de las capturas de este aparato y las de los recortes de la macro. */
fun allFolders(shotFolders: es.macrostudio.capture.ShotFolders.State, p: Project): List<String> =
    (shotFolders.folders + p.templates.map { it.folder }.filter { it.isNotBlank() }).distinct().sortedBy { it.lowercase() }
