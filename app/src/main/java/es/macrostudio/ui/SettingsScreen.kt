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
        SettingsCard("💾 Copias de seguridad") {
            Text("Guarda todas tus macros con sus imágenes en un solo archivo. Guárdalo en Drive, Descargas o donde quieras.")
            Button(onClick = { c.backupAll() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("💾 Crear copia de seguridad (${Studio.projects.size} macros)") }
            OutlinedButton(onClick = { c.openFile() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("♻ Restaurar copia o importar una macro") }
            Hint("Al restaurar una copia, las macros que ya tengas con el mismo origen se reemplazan por las de la copia; el resto no se toca. Una macro exportada suelta se añade como nueva.")
        }
        SettingsCard("🔐 Permisos") { PermissionsCard(c, alwaysShow = true) }
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
        Hint("PrisonMacro ${BuildConfig.VERSION_NAME} · todo se procesa en tu dispositivo; nada sale de él salvo que uses la Nube.")
    }
}

@Composable fun LogScreen(c: AppController) {
    SubScreen("Registro", onBack = { c.open(c.previous.takeIf { it != Screen.LOG } ?: Screen.HOME) }) {
        Text("Última detección: ${Studio.lastMatch}", color = MaterialTheme.colorScheme.primary)
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
                                    else -> c.importMacro(payload.toByteArray(), "nube")
                                }
                            }
                        }, enabled = !busy) { Text("⬇ Descargar") }
                    }
                }
            }
        } else SettingsCard("Entrar con tu correo") {
            var email by remember(sync.email) { mutableStateOf(sync.email) }
            OutlinedTextField(email, { email = it.take(160) }, label = { Text("Correo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { Studio.sync.sendEmailLink(email) { e -> if (e == null) Studio.log("Enlace de acceso enviado") else Studio.fail(e) } },
                enabled = email.isNotBlank() && sync.phase != SyncPhase.WAITING_FOR_CODE, modifier = Modifier.fillMaxWidth()
            ) { Text(if (sync.phase == SyncPhase.WAITING_FOR_CODE) "Enlace enviado · revisa tu correo" else "Enviar enlace de acceso") }
            Hint("Recibirás un enlace de un solo uso. Ábrelo en este dispositivo. No se guarda ninguna contraseña.")
            if (sync.phase != SyncPhase.NOT_CONFIGURED) TextButton(onClick = { Studio.sync.clearLocalSetup() }) { Text("Usar otro correo") }
            if (sync.message.isNotBlank()) Hint(sync.message)
        }
    }
}
