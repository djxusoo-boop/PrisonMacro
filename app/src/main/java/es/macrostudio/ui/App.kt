package es.macrostudio.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.macrostudio.core.*
import es.macrostudio.gestures.MacroAccessibilityService

@Composable fun PrisonApp(activity: ComponentActivity) {
    val c = rememberAppController(activity)
    // Al volver de capturar, recortar o elegir un punto, reabre el paso correspondiente.
    LaunchedEffect(Studio.requestedScreen, Studio.reopenActionId, Studio.panelNeedsCapture) {
        Studio.requestedScreen?.let { name ->
            Studio.requestedScreen = null
            c.open(when (name) { "images" -> Screen.IMAGES; "home" -> Screen.HOME; else -> Screen.EDITOR })
        }
        Studio.panelNeedsCapture?.let { what ->
            Studio.panelNeedsCapture = null
            if (what == "capture") c.captureFor(Studio.pendingTemplateActionId)
            else c.withCapture { MacroAccessibilityService.instance?.showBar(); activity.moveTaskToBack(true); c.toast("Listo. Pulsa ▶ en el panel para empezar") }
        }
        Studio.reopenActionId?.let { id ->
            Studio.reopenActionId = null
            if (findAction(Studio.project.actions, id) != null) { c.open(Screen.EDITOR); c.editAction(id) }
        }
    }
    PrisonTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (c.screen) {
                Screen.HOME -> HomeScreen(c)
                Screen.EDITOR -> EditorScreen(c)
                Screen.IMAGES -> ImagesScreen(c)
                Screen.SETTINGS -> SettingsScreen(c)
                Screen.CLOUD -> CloudScreen(c)
                Screen.LOG -> LogScreen(c)
            }
        }
        c.editingId?.let { id ->
            val action = findAction(Studio.project.actions, id)
            if (action == null) { c.editingId = null; c.draftId = null } else key(id) { ActionEditor(c, action) }
        }
        AppDialogs(c)
    }
}

@Composable private fun AppDialogs(c: AppController) {
    when (val d = c.dialog) {
        null -> Unit
        AppDialog.NeedAccessibility -> AlertDialog(
            onDismissRequest = { c.dialog = null },
            title = { Text("Activa la accesibilidad") },
            text = { Text("PrisonMacro necesita el servicio de accesibilidad para tocar la pantalla y mostrar el panel flotante.\n\nEn la lista, busca «PrisonMacro» y actívalo. Si Android lo bloquea («ajuste restringido»), ve a Ajustes › Apps › PrisonMacro › ⋮ › Permitir ajustes restringidos.") },
            confirmButton = { TextButton(onClick = { c.dialog = null; c.openAccessibilitySettings() }) { Text("Abrir ajustes") } },
            dismissButton = { TextButton(onClick = { c.dialog = null }) { Text("Ahora no") } }
        )
        is AppDialog.Issues -> AlertDialog(
            onDismissRequest = { c.dialog = null },
            title = { Text("Falta completar «${d.project.name}»") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { d.issues.forEach { Text("• $it") } } },
            confirmButton = { TextButton(onClick = { c.dialog = null; c.openEditor(d.project) }) { Text("Abrir el editor") } },
            dismissButton = { TextButton(onClick = { c.dialog = null }) { Text("Cerrar") } }
        )
        is AppDialog.ConfirmRestore -> AlertDialog(
            onDismissRequest = { c.dialog = null },
            title = { Text("¿Restaurar la copia de seguridad?") },
            text = { Text("Contiene ${d.count} macro(s). Las que ya tengas de esta misma copia se reemplazarán; el resto de tus macros no se tocan.") },
            confirmButton = { TextButton(onClick = { c.dialog = null; c.restore(d.bytes) }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { c.dialog = null }) { Text("Cancelar") } }
        )
        is AppDialog.Message -> AlertDialog(
            onDismissRequest = { c.dialog = null }, title = { Text(d.title) }, text = { Text(d.text) },
            confirmButton = { TextButton(onClick = { c.dialog = null }) { Text("Entendido") } }
        )
    }
}
