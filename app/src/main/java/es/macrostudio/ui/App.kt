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
    LaunchedEffect(Unit) { c.dropLeftoverDraft() }
    // Al volver de capturar, recortar o elegir un punto, reabre el paso correspondiente.
    LaunchedEffect(Studio.requestedScreen, Studio.reopenActionId, Studio.panelNeedsCapture) {
        Studio.requestedScreen?.let { name ->
            Studio.requestedScreen = null
            c.open(when (name) { "images" -> Screen.IMAGES; "home" -> Screen.HOME; "settings" -> Screen.SETTINGS; else -> Screen.EDITOR })
        }
        Studio.panelNeedsCapture?.let { what ->
            Studio.panelNeedsCapture = null
            if (what == "capture") c.captureFor(Studio.pendingTemplateActionId)
            else if (what == "burst") c.startBurst()
            else c.withCapture { MacroAccessibilityService.instance?.showBar("👉 Listo: pulsa ▶ para empezar"); activity.moveTaskToBack(true) }
        }
        Studio.reopenActionId?.let { id ->
            Studio.reopenActionId = null
            findAction(Studio.project.actions, id)?.let { a ->
                c.open(Screen.EDITOR)
                // Paso sencillo que ya tiene lo que necesitaba: vuelve a la lista (no hace falta pulsar Guardar otra vez).
                val hasImage = Studio.project.templates.any { it.id == a.templateId }
                when {
                    a.kind in setOf(Kind.CLICK_IMAGE, Kind.WAIT_IMAGE, Kind.WHEN_IMAGE) && hasImage -> c.toast("✓ Listo: «${a.name}»")
                    a.kind == Kind.GESTURE -> Unit
                    else -> c.editAction(id)
                }
            }
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
            if (action == null) { c.editingId = null; c.draftId = null } else key(id, action.kind) { ActionEditor(c, action) } // otro tipo de paso = editor nuevo
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
