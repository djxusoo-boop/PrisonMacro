package es.macrostudio.engine

import android.app.ActivityManager
import android.content.Context

/**
 * Que «Cerrar todo» (Recientes) no pare PrisonMacro mientras la barra flotante está puesta.
 *
 *  · Mientras la barra está en pantalla y la app no se está viendo, PrisonMacro se esconde
 *    de Recientes: «Cerrar todo» (también el de BlueStacks) no la puede quitar. Al abrir
 *    la app (⌂ en la barra o su icono) vuelve a salir en Recientes.
 *  · Si aun así Android cierra PrisonMacro, al volver la accesibilidad la barra se vuelve
 *    a poner sola y avisa de que la macro se cortó (la captura de pantalla hay que
 *    autorizarla otra vez: Android no deja hacerlo sin preguntarte).
 */
object KeepAlive {
    private const val PREFS = "studio"
    /** ¿Se está viendo alguna pantalla de la app? */
    @Volatile var appVisible = false
    /** Pantallas de PrisonMacro a la vista (la principal y la de recortar). Mientras haya
     * alguna, el panel flotante se aparta para no tapar la app. */
    @Volatile var appScreens = 0; private set
    fun appScreen(visible: Boolean) {
        appScreens = (appScreens + if (visible) 1 else -1).coerceAtLeast(0)
        es.macrostudio.gestures.MacroAccessibilityService.instance?.setAppInFront(appScreens > 0)
    }

    fun hideFromRecents(context: Context, hide: Boolean) {
        runCatching {
            context.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { task -> runCatching { task.setExcludeFromRecents(hide) } }
        }
    }

    fun barShown(context: Context, shown: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("barShown", shown).apply()
        hideFromRecents(context, shown && !appVisible)
    }
    fun wasBarShown(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("barShown", false)

    fun macroRunning(context: Context, running: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (prefs.getBoolean("macroRunning", false) != running) prefs.edit().putBoolean("macroRunning", running).apply()
    }
    /** ¿Se cortó una macro porque Android cerró la app? (y lo olvida). */
    fun takeInterruptedMacro(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return prefs.getBoolean("macroRunning", false).also { if (it) prefs.edit().putBoolean("macroRunning", false).apply() }
    }
}
