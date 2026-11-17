package es.macrostudio.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.macrostudio.BuildConfig
import es.macrostudio.MainActivity
import es.macrostudio.core.Studio
import es.macrostudio.editor.LabActivity
import es.macrostudio.sync.SyncPhase
import es.macrostudio.sync.activeSessionCount
import es.macrostudio.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } }, title = { Text(title) })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable private fun LinkRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(icon, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Hint(subtitle) }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun SettingsScreen(c: AppController) {
    val busy = Studio.running || Studio.recording
    SubScreen("Ajustes", onBack = { c.open(Screen.HOME) }) {
        SettingsCard("🖥 Este aparato: cómo se cierra el juego") { DeviceCard(c, busy) }
        SettingsCard("⬆ Actualizar la app") { UpdateCard(c) }
        SettingsCard("🔄 Pasar macros con el ordenador (iPhone)") { ReceiveMacrosCard(c); SendLogButton(c) }
        SettingsCard("🔐 Permisos") { PermissionsCard(c, alwaysShow = true) }
        // Lo que casi nunca se usa, plegado.
        var more by remember { mutableStateOf(false) }
        TextButton(onClick = { more = !more }) { Text(if (more) "▲ Menos ajustes" else "▼ Más ajustes (copias de seguridad, nube, registro, orientación)") }
        if (more) {
            SettingsCard("💾 Copias de seguridad") {
                Text("Guarda todas tus macros con sus imágenes en un solo archivo. Guárdalo en Drive, Descargas o donde quieras.")
                Button(onClick = { c.backupAll() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("💾 Crear copia de seguridad (${Studio.projects.size} macros)") }
                OutlinedButton(onClick = { c.openFile() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("♻ Restaurar copia o importar una macro") }
            }
            SettingsCard("☁ Más") {
                LinkRow("☁", "Nube", "Sincroniza macros entre tus dispositivos con tu correo") { c.open(Screen.CLOUD) }
                LinkRow("📜", "Registro", "Qué ha hecho la app: detecciones, errores, pasos") { c.open(Screen.LOG) }
                LinkRow("🧪", "Laboratorio seguro", "Pantalla de prueba para practicar sin tocar otras apps") { c.activity.startActivity(Intent(c.activity, LabActivity::class.java)) }
            }
            SettingsCard("📱 Orientación de PrisonMacro") {
                var mode by remember { mutableIntStateOf(c.activity.getSharedPreferences("studio", 0).getInt("orientationMode", 0)) }
                ChoiceChips(listOf(0 to "Horizontal", 1 to "Vertical", 2 to "Automática"), mode) {
                    mode = it
                    c.activity.getSharedPreferences("studio", 0).edit().putInt("orientationMode", it).apply()
                    (c.activity as? MainActivity)?.applyOrientationMode(it)
                }
                Hint("Las imágenes y los puntos dependen de la orientación de tu juego. Si tu juego es horizontal, deja Horizontal.")
            }
        }
        Hint("PrisonMacro ${BuildConfig.VERSION_NAME} · todo se procesa en tu dispositivo; nada sale de él salvo que uses la Nube.")
    }
}

/** Busca en el ordenador (scripts/serve-updates.sh) una versión más nueva y la instala. */
@Composable private fun UpdateCard(c: AppController) {
    val prefs = c.activity.getSharedPreferences("studio", 0)
    var address by remember { mutableStateOf(prefs.getString("updateAddress", Updater.SAME_COMPUTER) ?: Updater.SAME_COMPUTER) }
    var message by remember { mutableStateOf("Tienes la versión ${BuildConfig.VERSION_NAME}.") }
    var found by remember { mutableStateOf<Updater.Info?>(null) }
    var busy by remember { mutableStateOf(false) }
    val normalized = Updater.normalizeAddress(address)
    // La dirección se busca sola: el campo y la búsqueda a mano quedan plegados.
    var manual by remember { mutableStateOf(normalized == null) }
    if (manual) {
    OutlinedTextField(address, { address = it.take(60); found = null }, label = { Text("Dirección del ordenador") }, singleLine = true, isError = normalized == null, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = {
        busy = true; found = null
        es.macrostudio.update.Finder.search(c.activity, progress = { message = it }) { result ->
            busy = false
            if (result != null) { address = result; message = "✔ Ordenador encontrado: $result" }
            else message = "No encuentro el ordenador. Comprueba que está encendido, en la misma wifi y con el buzón abierto (icono «Abrir buzón PrisonMacro» del Escritorio del Mac)."
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("🔎 Buscar el ordenador automáticamente") }
    Hint(if (normalized == null) "Escribe una dirección de tu red, por ejemplo 192.168.1.20, o pulsa «Buscar el ordenador»." else "No hace falta apuntar la IP: si cambia, la app busca el ordenador sola.")
    }
    Text(message)
    val pending = found
    if (pending == null) Button(onClick = {
        normalized?.let { prefs.edit().putString("updateAddress", it).apply() }
        busy = true; message = "Buscando…"
        es.macrostudio.update.Finder.ensure(c.activity, progress = { message = it }) { target ->
            if (target == null) { busy = false; message = "No encuentro el ordenador. Comprueba que está encendido, en la misma wifi y con el buzón abierto (icono «Abrir buzón PrisonMacro» del Escritorio del Mac)."; return@ensure }
            address = target; message = "Buscando actualización…"
        Updater.check(target) { info, error ->
            busy = false
            when {
                error != null -> message = error
                info == null -> message = "Respuesta vacía"
                Updater.isNewer(info) -> { found = info; message = "Hay una versión nueva: ${info.versionName} (tienes ${BuildConfig.VERSION_NAME})." }
                else -> message = "Ya tienes la última versión (${BuildConfig.VERSION_NAME})."
            }
        }
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("⬆ Buscar actualización") }
    else Button(onClick = {
        val pm = c.activity.packageManager
        if (!pm.canRequestPackageInstalls()) {
            message = "Permite a PrisonMacro instalar apps (activa el interruptor y vuelve), y pulsa otra vez."
            c.activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${c.activity.packageName}")))
            return@Button
        }
        busy = true; message = "Descargando ${pending.versionName}…"
        Updater.downloadAndInstall(c.activity, Updater.normalizeAddress(address) ?: return@Button, pending, progress = { message = "Descargando ${pending.versionName}… $it %" }) { error ->
            busy = false
            message = error ?: "Descargada. Pulsa «Instalar» en el aviso de Android. Tus macros se conservan."
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("⬇ Descargar e instalar ${pending.versionName}") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Hint("En el Mac tiene que estar abierto el buzón («Abrir buzón PrisonMacro» en el Escritorio).") }
        if (!manual) TextButton(onClick = { manual = true }) { Text("Dirección…") }
    }
}

/** Trae las macros que el ordenador ofrece con scripts/serve-updates.sh (carpeta outputs/macros). */
@Composable private fun ReceiveMacrosCard(c: AppController) {
    var message by remember { mutableStateOf("Pasa macros por wifi a través del ordenador (también con el iPhone).") }
    var busy by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val notFound = "No encuentro el ordenador. Comprueba que está encendido, en la misma wifi y con el buzón abierto (icono «Abrir buzón PrisonMacro» del Escritorio del Mac)."
    Text(message)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = {
            busy = true; message = "Buscando el ordenador…"
            es.macrostudio.update.Finder.ensure(c.activity, progress = { message = it }) { address ->
                if (address == null) { busy = false; message = notFound; return@ensure }
                message = "Trayendo macros de $address…"
                Updater.fetchMacros(address) { bytes, error ->
                    busy = false
                    if (bytes == null) message = error ?: "Respuesta vacía"
                    else { message = "Macros recibidas."; c.receiveMacros(bytes) }
                }
            }
        }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("📥 Recibir macros") }
        OutlinedButton(onClick = {
            if (Studio.projects.isEmpty()) { message = "Todavía no tienes macros que enviar."; return@OutlinedButton }
            choosing = true
        }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("📤 Enviar al ordenador") }
    }
    if (choosing) MacroChooserDialog(onDismiss = { choosing = false }) { chosen ->
        choosing = false
        busy = true; message = "Buscando el ordenador…"
        es.macrostudio.update.Finder.ensure(c.activity, progress = { message = it }) { address ->
            if (address == null) { busy = false; message = notFound; return@ensure }
            message = "Enviando ${chosen.size} macro(s) a $address…"
            c.scope.launch {
                val body = withContext(Dispatchers.IO) { runCatching { es.macrostudio.projects.Backup.fullBackup(c.activity, chosen).toByteArray() }.getOrNull() }
                if (body == null) { busy = false; message = "No se pudieron preparar las macros."; return@launch }
                Updater.sendMacros(address, body) { error ->
                    busy = false
                    message = error ?: "Enviada(s) ${chosen.joinToString { "«${it.name}»" }} ✔. En el otro aparato: Recibir."
                }
            }
        }
    }
}

/** Elegir qué macros enviar (por defecto, la que tienes abierta). */
@Composable fun MacroChooserDialog(onDismiss: () -> Unit, onSend: (List<es.macrostudio.core.Project>) -> Unit) {
    var selected by remember { mutableStateOf(setOf(Studio.project.id)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Qué macros envío?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selected = Studio.projects.map { it.id }.toSet() }) { Text("Todas") }
                    TextButton(onClick = { selected = emptySet() }) { Text("Ninguna") }
                }
                Studio.projects.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { selected = if (p.id in selected) selected - p.id else selected + p.id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = p.id in selected, onCheckedChange = { on -> selected = if (on) selected + p.id else selected - p.id })
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSend(Studio.projects.filter { it.id in selected }) }, enabled = selected.isNotEmpty()) { Text("📤 Enviar (${selected.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable fun LogScreen(c: AppController) {
    SubScreen("Registro", onBack = { c.open(c.previous.takeIf { it != Screen.LOG } ?: Screen.HOME) }) {
        Text("Última detección: ${Studio.lastMatch}", color = MaterialTheme.colorScheme.primary)
        SendLogButton(c)
        Hint("Últimos 200 eventos. Solo se guardan en este dispositivo.")
        Studio.logs.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall); HorizontalDivider() }
    }
}

@Composable fun CloudScreen(c: AppController) {
    val busy = Studio.running || Studio.recording
    val sync = Studio.sync.snapshot
    LaunchedEffect(sync.phase) { if (sync.phase == SyncPhase.CONNECTED) Studio.sync.refreshCloudProjects() }
    SubScreen("Nube", onBack = { c.open(Screen.SETTINGS) }) {
        Text("Biblioteca privada para abrir tus macros en tus otros dispositivos (también en el compañero de iPhone).")
        if (sync.phase == SyncPhase.CONNECTED) {
            SettingsCard("Cuenta") {
                Text(sync.email); Hint(sync.message)
                Text("Dispositivos activos: ${activeSessionCount(sync.sessions, System.currentTimeMillis())}")
                sync.sessions.filter { !it.revoked }.forEach { Hint("• ${it.name} · ${it.platform}") }
                OutlinedButton(onClick = { Studio.sync.signOut() }, enabled = !busy) { Text("Cerrar sesión") }
            }
            SettingsCard("Macros en la nube") {
                Button(onClick = { Studio.sync.uploadProject(Studio.project) { e -> if (e == null) { Studio.log("Macro subida a la nube"); c.toast("Subida: ${Studio.project.name}") } else Studio.fail(e) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("⬆ Subir «${Studio.project.name}»", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { Studio.sync.refreshCloudProjects() }, modifier = Modifier.fillMaxWidth()) { Text("↻ Actualizar lista") }
                if (Studio.sync.cloudProjects.isEmpty()) Hint("Todavía no hay macros en esta cuenta.")
                Studio.sync.cloudProjects.forEach { cloud ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cloud.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Hint(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(cloud.updatedAtMs)))
                        }
                        TextButton(onClick = {
                            Studio.sync.downloadProject(cloud.id) { payload, error ->
                                when {
                                    error != null -> Studio.fail(error)
                                    payload.isNullOrBlank() -> Studio.fail("La macro no tiene contenido")
                                    else -> c.receiveMacros(payload.toByteArray(), "de la nube")
                                }
                            }
                        }, enabled = !busy) { Text(if (Studio.projects.any { it.id == cloud.id }) "⟳ Actualizar" else "⬇ Descargar") }
                    }
                }
            }
        } else SettingsCard("Tu cuenta") {
            var email by remember(sync.email) { mutableStateOf(sync.email) }
            var password by remember { mutableStateOf("") }
            var working by remember { mutableStateOf(false) }
            var info by remember { mutableStateOf<String?>(null) }
            Hint("Crea la cuenta una vez y entra con el mismo correo y contraseña en todos tus aparatos (BlueStacks, móvil, iPhone). Luego, lo que subes en uno lo descargas en otro.")
            OutlinedTextField(email, { email = it.take(160); info = null }, label = { Text("Correo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it.take(100); info = null }, label = { Text("Contraseña (mínimo 6)") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                fun go(create: Boolean) {
                    when {
                        email.isBlank() -> info = "Escribe tu correo arriba."
                        password.length < 6 -> info = "Escribe una contraseña de al menos 6 letras o números" + (if (create) " (la que quieras; será la de tu cuenta)." else ".")
                        else -> { working = true; info = null; Studio.sync.passwordAuth(email, password, create) { e -> working = false; info = e; if (e == null) c.toast(if (create) "Cuenta creada y conectada" else "Cuenta conectada") } }
                    }
                }
                Button(onClick = { go(create = false) }, enabled = !working, modifier = Modifier.weight(1f)) { Text("🔓 Entrar") }
                Button(onClick = { go(create = true) }, enabled = !working, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) { Text("✨ Crear cuenta") }
            }
            if (working) Text("Un momento…")
            Hint("¿Primera vez? Escribe tu correo, inventa una contraseña y pulsa «✨ Crear cuenta». En los demás aparatos, el mismo correo y contraseña con «🔓 Entrar».")
            TextButton(onClick = { if (email.isBlank()) info = "Escribe tu correo arriba." else Studio.sync.resetPassword(email) { e -> info = e ?: "Te hemos enviado un correo para poner una contraseña nueva (mira también en Spam). Ábrelo en el navegador, pon la contraseña y vuelve aquí a «Entrar»." } },
                enabled = !working) { Text("¿Olvidaste la contraseña?") }
            info?.let { Text(it, color = if (it.startsWith("Te hemos")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** PC Windows o MacBook Air: cambia cómo hace el paso «✖ Cerrar el juego» en este aparato. */
@Composable fun DeviceCard(c: AppController, busy: Boolean) {
    val ctx = c.activity
    var kind by remember { mutableStateOf(es.macrostudio.engine.ThisDevice.kind(ctx)) }
    // Se vuelve a leer al volver de marcar el botón.
    val point = remember(kind, Studio.deviceChanges) { es.macrostudio.engine.ThisDevice.clearAllPoint(ctx) }
    ChoiceChips(listOf(es.macrostudio.engine.ThisDevice.MAC to "💻 MacBook Air", es.macrostudio.engine.ThisDevice.WINDOWS to "🖥 PC Windows"), kind) {
        kind = it; es.macrostudio.engine.ThisDevice.setKind(ctx, it)
    }
    if (!es.macrostudio.engine.ThisDevice.isChosen(ctx)) Hint("Lo he visto solo (procesador ${es.macrostudio.engine.ThisDevice.processor()}). Si no es así, cámbialo arriba.")
    if (kind == es.macrostudio.engine.ThisDevice.WINDOWS) {
        Text("El paso «✖ Cerrar el juego» abre Recientes y pulsa «Cerrar todo».")
        if (point == null) Hint("«Cerrar todo» se buscará solo la primera vez que pulses ▶.")
        else Hint("✓ «Cerrar todo» está en ${(point.x * 100).toInt()} %, ${(point.y * 100).toInt()} % de la pantalla.")
        OutlinedButton(onClick = { c.markClearAll() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (point == null) "📍 Buscar «Cerrar todo» ya" else "📍 Buscarlo otra vez") }
        Hint("Se abre Recientes y lo busco yo. Si no lo encuentro, te pido que lo toques (no se cierra nada).")
    } else {
        Text("El paso «✖ Cerrar el juego» abre Recientes y desliza hacia arriba la tarjeta del juego.")
    }
    Hint("Esto se guarda solo en este aparato. Tu macro es la misma en los dos: cada uno cierra el juego a su manera.")
}

/** «📜 Enviar el registro al ordenador»: para ver desde el Mac qué pasó en este BlueStacks
 * (por qué se paró, en qué paso falló, qué aparato es…). */
@Composable fun SendLogButton(c: AppController) {
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf("") }
    OutlinedButton(onClick = {
        sending = true; sent = "Buscando el ordenador…"
        es.macrostudio.update.Finder.ensure(c.activity, progress = { sent = it }) { address ->
            if (address == null) { sending = false; sent = "No encuentro el ordenador: abre el buzón en el Mac (icono «Abrir buzón PrisonMacro»)."; return@ensure }
            val size = es.macrostudio.gestures.MacroAccessibilityService.instance?.screenSize()
                ?: c.activity.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
            val header = listOf(
                "PrisonMacro ${BuildConfig.VERSION_NAME} · ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("es")).format(java.util.Date())}",
                "Aparato: ${es.macrostudio.engine.ThisDevice.label(c.activity)} · procesador ${es.macrostudio.engine.ThisDevice.processor()} · «Cerrar todo» ${es.macrostudio.engine.ThisDevice.clearAllPoint(c.activity)?.let { "en ${(it.x * 100).toInt()} %, ${(it.y * 100).toInt()} %" } ?: "sin guardar"}",
                "Pantalla: ${size.first}×${size.second} · Macro: ${Studio.project.name}",
                "Accesibilidad: ${if (Studio.accessibilityReady) "sí" else "no"} · Captura: ${if (Studio.captureReady) "sí" else "no"}",
                "Último aviso: ${Studio.stopInfo.replace("\n", " · ")}", "----"
            )
            Updater.sendLog(address, (header + Studio.logs).joinToString("\n")) { error ->
                sending = false; sent = error ?: "✓ Registro enviado al ordenador"
            }
        }
    }, enabled = !sending, modifier = Modifier.fillMaxWidth()) { Text("📜 Enviar el registro al ordenador (si algo va mal)") }
    if (sent.isNotBlank()) Hint(sent)
}
