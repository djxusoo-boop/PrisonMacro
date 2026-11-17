package es.macrostudio.engine

import android.content.Context
import es.macrostudio.core.Point

/**
 * Lo que depende del aparato y no de la macro: la misma macro vale en el PC de Windows y en
 * el MacBook Air, y cada uno sabe cómo se cierra allí el juego.
 *  · MacBook Air: en Recientes se desliza la tarjeta del juego.
 *  · PC Windows: en Recientes se pulsa «Cerrar todo» (en el sitio que marcó el usuario).
 * Se ve solo por el procesador (PC = x86, MacBook = ARM); se puede cambiar en ⚙ Ajustes.
 * El sitio de «Cerrar todo» se busca solo la primera vez y se guarda en este aparato (no viaja con la macro).
 */
object ThisDevice {
    private const val PREFS = "studio"
    const val MAC = "mac"
    const val WINDOWS = "windows"

    /** Sin preguntar: BlueStacks del PC de Windows corre en un procesador x86; el MacBook Air (Apple) es ARM. */
    fun detected(): String = if (android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true) WINDOWS else MAC
    fun processor(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
    /** Lo que eligió la persona en ⚙ Ajustes o, si no eligió nada, lo que se ve solo. */
    fun kind(context: Context): String = context.getSharedPreferences(PREFS, 0).getString("deviceKind", null) ?: detected()
    fun setKind(context: Context, kind: String) { context.getSharedPreferences(PREFS, 0).edit().putString("deviceKind", kind).apply(); es.macrostudio.core.Studio.deviceChanges++ }
    /** ¿Lo eligió la persona? (si no, se usa [detected]) */
    fun isChosen(context: Context) = context.getSharedPreferences(PREFS, 0).contains("deviceKind")
    /** Listo para cerrar el juego: en el PC, con el sitio de «Cerrar todo» ya guardado (se busca solo al pulsar ▶). */
    fun ready(context: Context) = !usesClearAll(context) || clearAllPoint(context) != null
    fun usesClearAll(context: Context) = kind(context) == WINDOWS

    /** Dónde está «Cerrar todo» en Recientes (0..1), o null si aún no se ha marcado. */
    fun clearAllPoint(context: Context): Point? {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val x = prefs.getFloat("clearAllX", -1f); val y = prefs.getFloat("clearAllY", -1f)
        return if (x in 0f..1f && y in 0f..1f) Point(x, y) else null
    }
    fun setClearAllPoint(context: Context, point: Point) {
        context.getSharedPreferences(PREFS, 0).edit().putFloat("clearAllX", point.x).putFloat("clearAllY", point.y).apply()
        es.macrostudio.core.Studio.deviceChanges++
    }

    fun label(context: Context) = (if (usesClearAll(context)) "PC Windows: pulsa «Cerrar todo»" else "MacBook Air: desliza la tarjeta del juego") +
        if (isChosen(context)) "" else " (lo he visto solo)"
}
