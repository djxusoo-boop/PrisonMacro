package es.macrostudio.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.macrostudio.R
import es.macrostudio.core.*
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

val Teal = Color(0xFF6DE2D1)
val Danger = Color(0xFFFF8A9A)
val Warning = Color(0xFFFFC870)

@Composable fun PrisonTheme(content: @Composable () -> Unit) {
    val typography = Typography(
        titleLarge = TextStyle(fontSize = 21.sp),
        headlineSmall = TextStyle(fontSize = 24.sp),
        titleMedium = TextStyle(fontSize = 17.sp),
        bodyLarge = TextStyle(fontSize = 17.sp),
        bodyMedium = TextStyle(fontSize = 15.sp),
        bodySmall = TextStyle(fontSize = 13.sp),
        labelLarge = TextStyle(fontSize = 15.sp),
        labelMedium = TextStyle(fontSize = 14.sp),
        labelSmall = TextStyle(fontSize = 12.sp)
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Teal, onPrimary = Color(0xFF00201C),
            secondary = Color(0xFFB9A5FF),
            background = Color(0xFF0E1320), surface = Color(0xFF171F30),
            surfaceVariant = Color(0xFF222C42), primaryContainer = Color(0xFF1D4A48),
            errorContainer = Color(0xFF4A1F28), onErrorContainer = Color(0xFFFFD9DE)
        ),
        typography = typography,
        content = content
    )
}

/** Icono, nombre y explicación de cada tipo de paso. */
data class KindInfo(val icon: String, val title: String, val hint: String)

fun kindInfo(kind: Kind): KindInfo = when (kind) {
    Kind.GESTURE -> KindInfo("👆", "Tocar / deslizar", "Toca o arrastra en un punto fijo de la pantalla")
    Kind.CLICK_IMAGE -> KindInfo("🎯", "Buscar imagen y tocarla", "Cuando aparezca la imagen, la toca")
    Kind.WAIT_IMAGE -> KindInfo("⏳", "Esperar a ver una imagen", "Sigue cuando aparezca la imagen")
    Kind.IF_IMAGE -> KindInfo("🖼", "Si se ve una imagen…", "Hace unos pasos si aparece y otros si no")
    Kind.IF_COLOR -> KindInfo("🎨", "Si se ve un color…", "Hace unos pasos si aparece un color")
    Kind.WAIT -> KindInfo("⏱", "Esperar", "Pausa un tiempo fijo")
    Kind.REPEAT -> KindInfo("🔁", "Repetir pasos", "Repite varias veces los pasos de dentro")
    Kind.OPEN_APP -> KindInfo("📱", "Abrir app o juego", "Abre una aplicación instalada")
    Kind.BACK -> KindInfo("↩", "Botón Atrás", "Pulsa el botón Atrás de Android")
    Kind.HOME -> KindInfo("🏠", "Botón Inicio", "Vuelve a la pantalla de inicio")
    Kind.RECENTS -> KindInfo("🗂", "Apps recientes", "Abre la lista de apps recientes")
    Kind.CLOSE_ACTIVE -> KindInfo("✖", "Cerrar la app actual", "La quita de Recientes y vuelve a Inicio")
    Kind.NOTIFICATIONS -> KindInfo("🔔", "Abrir notificaciones", "Despliega la barra de notificaciones")
    Kind.QUICK_SETTINGS -> KindInfo("⚙", "Ajustes rápidos", "Despliega los ajustes rápidos")
}

/** Grupos del menú «Añadir paso». */
val kindGroups: List<Pair<String, List<Kind>>> = listOf(
    "Tocar la pantalla" to listOf(Kind.GESTURE, Kind.CLICK_IMAGE),
    "Esperar" to listOf(Kind.WAIT, Kind.WAIT_IMAGE),
    "Decidir y repetir" to listOf(Kind.IF_IMAGE, Kind.IF_COLOR, Kind.REPEAT),
    "Android y apps" to listOf(Kind.OPEN_APP, Kind.CLOSE_ACTIVE, Kind.BACK, Kind.HOME, Kind.RECENTS, Kind.NOTIFICATIONS, Kind.QUICK_SETTINGS)
)

val imageKinds = setOf(Kind.CLICK_IMAGE, Kind.WAIT_IMAGE, Kind.IF_IMAGE)
val containerKinds = setOf(Kind.IF_IMAGE, Kind.IF_COLOR, Kind.REPEAT)

fun newAction(kind: Kind): Action = Action(
    kind = kind,
    name = kindInfo(kind).title.removeSuffix("…"),
    durationMs = when (kind) { Kind.WAIT -> 1000; Kind.WAIT_IMAGE -> 30_000; Kind.CLOSE_ACTIVE -> 1000; else -> 80 },
    count = if (kind == Kind.REPEAT) 3 else 2
)

fun readableDuration(ms: Long): String = when {
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> String.format(Locale("es"), if (ms % 1000 == 0L) "%.0f s" else "%.1f s", ms / 1000.0)
    ms % 60_000 == 0L -> "${ms / 60_000} min"
    else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
}

/** Convierte "1,5" o "2" (segundos) en milisegundos. */
fun parseSeconds(text: String): Long? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).roundToInt().toLong() }
fun secondsText(ms: Long): String = if (ms % 1000 == 0L) (ms / 1000).toString() else String.format(Locale("es"), "%.1f", ms / 1000.0)

fun clickTargetLabel(value: ClickTarget): String = when (value) {
    ClickTarget.CENTER -> "en el centro"
    ClickTarget.OFFSET -> "desplazado"
    ClickTarget.FIXED -> "en un punto fijo"
}
fun clickStyleLabel(value: ClickStyle): String = when (value) {
    ClickStyle.SINGLE -> "un toque"
    ClickStyle.DOUBLE -> "doble toque"
    ClickStyle.LONG -> "pulsación larga"
}

/** Resumen de una línea de lo que hará un paso. */
fun actionSummary(a: Action, p: Project): String {
    val image = p.templates.firstOrNull { it.id == a.templateId }
    fun pct(v: Float) = "${(v * 100).roundToInt()} %"
    return when (a.kind) {
        Kind.GESTURE -> if (a.points.size <= 1) "Toca en X ${pct(a.points.first().x)} · Y ${pct(a.points.first().y)}" + if (a.durationMs >= 500) " · ${readableDuration(a.durationMs)}" else ""
            else "Desliza ${a.points.size} puntos en ${readableDuration(a.durationMs)}"
        Kind.CLICK_IMAGE -> image?.let { "Toca «${it.name}» ${clickTargetLabel(a.clickTarget)} · ${clickStyleLabel(a.clickStyle)}" } ?: "⚠ Falta elegir la imagen"
        Kind.WAIT_IMAGE -> image?.let { "Espera a ver «${it.name}» (máx. ${readableDuration(a.durationMs)})" } ?: "⚠ Falta elegir la imagen"
        Kind.IF_IMAGE -> image?.let { "Si se ve «${it.name}»" } ?: "⚠ Falta elegir la imagen"
        Kind.IF_COLOR -> "Si se ve ${rgbHex(a.targetColor)} (tolerancia ${a.colorTolerance})"
        Kind.WAIT -> readableDuration(a.durationMs)
        Kind.REPEAT -> "${a.count} veces · ${a.children.size} paso(s) dentro"
        Kind.OPEN_APP -> a.packageName
        else -> kindInfo(a.kind).hint
    }
}

private val thumbCache = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
}

@Composable fun TemplateThumb(saved: SavedTemplate?, modifier: Modifier = Modifier) {
    val file = saved?.template?.file
    val bitmap = remember(file) {
        file?.let { name -> thumbCache.get(name) ?: BitmapFactory.decodeFile(File(Studio.context.filesDir, name).path)?.also { thumbCache.put(name, it) } }
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF0B101B)).border(1.dp, Color(0xFF2E3A55), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), saved?.name, Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit)
        else Text("Sin\nimagen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun BrandMark(size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFF071018)).border(1.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.prison_logo), "Logo de PrisonMacro", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

@Composable fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Fila de opciones exclusivas en forma de chips. */
@Composable fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    FlowRowCompat {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, enabled = enabled, label = { Text(label) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable fun StatusPill(ok: Boolean, okText: String, badText: String) {
    Text(
        if (ok) "✓ $okText" else "○ $badText",
        style = MaterialTheme.typography.labelMedium,
        color = if (ok) MaterialTheme.colorScheme.primary else Warning,
        maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}
