package es.macrostudio.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.roundToLong

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
    Kind.GESTURE -> KindInfo("👆", "Tocar un punto", "Toca o desliza siempre en el mismo sitio")
    Kind.CLICK_IMAGE -> KindInfo("🎯", "Tocar una imagen", "Espera a que salga el botón y lo toca")
    Kind.WAIT_IMAGE -> KindInfo("⏳", "Esperar a que salga una imagen", "Sigue cuando aparece (por ejemplo, el lobby)")
    Kind.IF_IMAGE -> KindInfo("🖼", "Si se ve una imagen…", "Hace unos pasos si aparece y otros si no")
    Kind.IF_COLOR -> KindInfo("🎨", "Si se ve un color…", "Hace unos pasos si aparece un color")
    Kind.WAIT -> KindInfo("⏱", "Esperar", "Unos segundos o minutos")
    Kind.REPEAT -> KindInfo("🔁", "Repetir pasos", "Repite varias veces los pasos de dentro")
    Kind.OPEN_APP -> KindInfo("📱", "Abrir un juego", "Abre una app instalada")
    Kind.BACK -> KindInfo("↩", "Botón Atrás", "Pulsa el botón Atrás de Android")
    Kind.HOME -> KindInfo("🏠", "Botón Inicio", "Vuelve a la pantalla de inicio")
    Kind.RECENTS -> KindInfo("🗂", "Apps recientes", "Abre la lista de apps recientes")
    Kind.CLOSE_ACTIVE -> KindInfo("✖", "Cerrar el juego", "Lo cierra desde Recientes y vuelve a Inicio")
    Kind.NOTIFICATIONS -> KindInfo("🔔", "Abrir notificaciones", "Despliega la barra de notificaciones")
    Kind.QUICK_SETTINGS -> KindInfo("⚙", "Ajustes rápidos", "Despliega los ajustes rápidos")
    Kind.STOP -> KindInfo("■", "Parar la macro", "Termina la macro aquí. Útil dentro de «Si se ve…»")
    Kind.WHEN_IMAGE -> KindInfo("⚡", "Tocar una imagen siempre que salga", "Vigila toda la macro: anuncios, ventanas, la X…")
}

/** Los pasos de siempre: salen grandes en «Añadir paso». */
val basicKinds = listOf(Kind.CLICK_IMAGE, Kind.WHEN_IMAGE, Kind.WAIT, Kind.WAIT_IMAGE, Kind.GESTURE, Kind.OPEN_APP, Kind.CLOSE_ACTIVE)

/** El resto, plegado bajo «Más tipos de paso». */
val kindGroups: List<Pair<String, List<Kind>>> = listOf(
    "Decidir y repetir" to listOf(Kind.IF_IMAGE, Kind.IF_COLOR, Kind.REPEAT, Kind.STOP),
    "Botones de Android" to listOf(Kind.BACK, Kind.HOME, Kind.RECENTS, Kind.NOTIFICATIONS, Kind.QUICK_SETTINGS)
)

/** Nombres de serie de versiones anteriores (para seguir reconociéndolos como automáticos). */
private val oldDefaultNames = setOf("Tocar / deslizar", "Buscar imagen y tocarla", "Esperar a ver una imagen", "Abrir app o juego", "Cerrar la app actual", "Siempre que se vea una imagen")

/** Regla «⚡» sencilla: solo toca su propia imagen. Se enseña en una línea, sin bloque. */
fun isSimpleRule(a: Action): Boolean = a.kind == Kind.WHEN_IMAGE && a.otherwise.isEmpty() && a.children.size == 1 &&
    a.children[0].kind == Kind.CLICK_IMAGE && a.children[0].templateId == a.templateId && a.children[0].enabled

val imageKinds = setOf(Kind.CLICK_IMAGE, Kind.WAIT_IMAGE, Kind.IF_IMAGE, Kind.WHEN_IMAGE)
val containerKinds = setOf(Kind.IF_IMAGE, Kind.IF_COLOR, Kind.REPEAT, Kind.WHEN_IMAGE)

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

/** «⏱ 2 s antes · 15 s después» (vacío si el paso no espera). */
fun delaysSummary(a: Action): String {
    val parts = listOfNotNull(
        a.delayBeforeMs.takeIf { it > 0 }?.let { "${readableDuration(it)} antes" },
        a.delayAfterMs.takeIf { it > 0 }?.let { "${readableDuration(it)} después" }
    )
    return if (parts.isEmpty()) "" else "⏱ " + parts.joinToString(" · ")
}

/** Convierte los pasos «Esperar» sueltos en esperas del paso de al lado: detrás de otro paso →
 * «esperar después» de ese paso; al principio → «esperar antes» del siguiente. Devuelve la lista y cuántos juntó. */
fun mergeWaits(list: List<Action>): Pair<List<Action>, Int> {
    val result = mutableListOf<Action>(); var pendingBefore = 0L; var merged = 0; var here = 0
    for (original in list) {
        val inner1 = mergeWaits(original.children); val inner2 = mergeWaits(original.otherwise)
        var a = original.copy(children = inner1.first, otherwise = inner2.first); merged += inner1.second + inner2.second
        if (a.kind == Kind.WAIT && a.enabled) {
            if (result.isNotEmpty()) { val last = result.removeAt(result.size - 1); result.add(last.copy(delayAfterMs = (last.delayAfterMs + a.durationMs).coerceAtMost(3_600_000))) }
            else pendingBefore += a.durationMs
            here++; continue
        }
        if (pendingBefore > 0) { a = a.copy(delayBeforeMs = (a.delayBeforeMs + pendingBefore).coerceAtMost(3_600_000)); pendingBefore = 0 }
        result.add(a)
    }
    return if (pendingBefore > 0) list to 0 else result to (merged + here)
}

fun countWaits(list: List<Action>): Int = list.sumOf { (if (it.kind == Kind.WAIT && it.enabled) 1 else 0) + countWaits(it.children) + countWaits(it.otherwise) }

/** Convierte "1,5" o "2" (segundos) en milisegundos. */
/** Convierte "1,5", "2", "30 s" o "4 min" en milisegundos. */
fun parseSeconds(text: String): Long? {
    val t = text.trim().lowercase(Locale.ROOT).replace(',', '.')
    val minutes = t.endsWith("min") || t.endsWith("m")
    val number = t.removeSuffix("min").removeSuffix("m").removeSuffix("s").trim()
    val v = number.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= 1_000_000 } ?: return null
    return ((if (minutes) v * 60 else v) * 1000).roundToLong()
}
/** Lo contrario: "4 min" si son minutos justos; si no, segundos ("1,5"). */
fun secondsText(ms: Long): String = when {
    ms >= 60_000 && ms % 60_000 == 0L -> "${ms / 60_000} min"
    ms % 1000 == 0L -> (ms / 1000).toString()
    else -> String.format(Locale("es"), "%.1f", ms / 1000.0)
}

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

/** Nombre automático de un paso con imagen. */
fun autoStepName(kind: Kind, image: String): String = when (kind) {
    Kind.CLICK_IMAGE -> "Tocar «$image»"
    Kind.WAIT_IMAGE -> "Esperar «$image»"
    Kind.IF_IMAGE -> "Si se ve «$image»"
    Kind.WHEN_IMAGE -> "Siempre que salga «$image»"
    Kind.REPEAT -> "Repetir hasta ver «$image»"
    else -> kindInfo(kind).title
}.take(100)

/** ¿El paso tiene todavía el nombre de serie o uno puesto solo («Tocar «Imagen 2»»)? */
fun hasAutoName(a: Action, name: String = a.name): Boolean =
    name.isBlank() || name == kindInfo(a.kind).title.removeSuffix("…") || name in oldDefaultNames || name.startsWith("Siempre que se vea «") || (name.endsWith("»") && name.contains("«"))

/** Pone la imagen al paso y, si su nombre era automático, lo cambia al de la imagen nueva. */
fun withImage(a: Action, templateId: String, imageName: String): Action {
    val named = a.copy(templateId = templateId, name = if (hasAutoName(a)) autoStepName(a.kind, imageName) else a.name)
    // «⚡ Tocar una imagen siempre que salga»: su toque va con la imagen (nueva o cambiada).
    return when {
        a.kind == Kind.WHEN_IMAGE && a.children.isEmpty() -> named.copy(children = listOf(ruleTap(templateId, imageName)))
        isSimpleRule(a) -> named.copy(children = listOf(a.children[0].copy(templateId = templateId, name = ruleTap(templateId, imageName).name)))
        else -> named
    }
}

/** El toque que hace una regla «⚡» sobre su propia imagen. */
fun ruleTap(templateId: String, imageName: String): Action =
    newAction(Kind.CLICK_IMAGE).copy(name = "Tocar «$imageName»".take(100), templateId = templateId, waitMs = 2_000, skipIfMissing = true)

/** Reglas «⚡» con imagen pero sin nada dentro: les pone el toque a su imagen. Devuelve la lista y cuántas arregló. */
fun fixEmptyRules(list: List<Action>, p: Project): Pair<List<Action>, Int> {
    var n = 0
    fun walk(items: List<Action>): List<Action> = items.map { a ->
        val image = p.templates.firstOrNull { it.id == a.templateId }
        val fixed = if (a.kind == Kind.WHEN_IMAGE && image != null && a.children.none { it.enabled }) { n++; a.copy(children = a.children + ruleTap(image.id, image.name)) } else a
        fixed.copy(children = walk(fixed.children), otherwise = walk(fixed.otherwise))
    }
    return walk(list) to n
}

/** Resumen de una línea de lo que hará un paso. */
fun actionSummary(a: Action, p: Project): String {
    val image = p.templates.firstOrNull { it.id == a.templateId }
    fun pct(v: Float) = "${(v * 100).roundToInt()} %"
    return when (a.kind) {
        Kind.GESTURE -> if (a.points.size <= 1) "Toca en X ${pct(a.points.first().x)} · Y ${pct(a.points.first().y)} de la pantalla" + (if (a.points.first().x == .5f && a.points.first().y == .5f) " (el centro: ¿lo marcaste?)" else "") + if (a.durationMs >= 500) " · ${readableDuration(a.durationMs)}" else ""
            else if (a.points.size == 2) "Desliza de ${pct(a.points[0].x)}, ${pct(a.points[0].y)} a ${pct(a.points[1].x)}, ${pct(a.points[1].y)} en ${readableDuration(a.durationMs)}"
            else "Trayectoria grabada de ${a.points.size} puntos en ${readableDuration(a.durationMs)}"
        Kind.CLICK_IMAGE -> image?.let { "Toca «${it.name}»" + (if (a.clickTarget != ClickTarget.CENTER) " ${clickTargetLabel(a.clickTarget)}" else "") + (if (a.clickStyle != ClickStyle.SINGLE) " · ${clickStyleLabel(a.clickStyle)}" else "") + if (a.skipIfMissing) " · si no está, sigue" else "" } ?: "⚠ Falta elegir la imagen"
        Kind.WAIT_IMAGE -> image?.let { if (a.untilGone) "Espera a que ya no salga «${it.name}» (${readableDuration(if (a.waitMs > 0) a.waitMs else es.macrostudio.engine.Engine.GONE_DEFAULT_MS)} sin verla, máx. ${readableDuration(a.durationMs)})"
            else "Espera a ver «${it.name}» (máx. ${readableDuration(a.durationMs)})" + if (a.skipIfMissing) " · si no, sigue" else "" } ?: "⚠ Falta elegir la imagen"
        Kind.IF_IMAGE -> image?.let { "Si se ve «${it.name}»" } ?: "⚠ Falta elegir la imagen"
        Kind.WHEN_IMAGE -> image?.let { if (isSimpleRule(a)) "Toca «${it.name}» siempre que salga" + (if (a.afterRule != AfterRule.CONTINUE) " · después ${afterRuleLabel(a.afterRule)}" else "") else "En cuanto salga «${it.name}» · después ${afterRuleLabel(a.afterRule)}" } ?: "⚠ Falta elegir la imagen"
        Kind.IF_COLOR -> "Si se ve ${rgbHex(a.targetColor)} (tolerancia ${a.colorTolerance})"
        Kind.WAIT -> readableDuration(a.durationMs)
        Kind.REPEAT -> (image?.let { "Hasta ver «${it.name}» (máx. ${a.count} veces)" } ?: "${a.count} veces") + " · ${a.children.size} paso(s) dentro"
        Kind.OPEN_APP -> if (a.packageName == QuickMacro.NO_APP) "⚠ Falta elegir el juego"
            else "Abre " + runCatching { Studio.context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(a.packageName, 0)).toString() } }.getOrDefault(a.packageName)
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

// ---------- Capturas guardadas (capturas rápidas) ----------

private val shotCache = object : android.util.LruCache<String, android.graphics.Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
}

/** Miniatura de una captura guardada (se lee reducida para no gastar memoria). */
@Composable fun ShotThumb(file: File, modifier: Modifier = Modifier) {
    val bitmap = remember(file.path) {
        shotCache.get(file.path) ?: runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1; while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { shotCache.put(file.path, it) }
    }
    androidx.compose.foundation.layout.Box(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (bitmap != null) androidx.compose.foundation.Image(bitmap.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Text("🖼")
    }
}

/** Elegir una de las capturas guardadas con «📷 Capturas rápidas». */
/** ¿Borrar la captura solo aquí o en todos los aparatos de la cuenta? */
@Composable fun ShotDeleteDialog(file: File, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val signedIn = es.macrostudio.sync.ShotSync.isSignedIn()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Borrar esta captura?") },
        text = { Column { ShotThumb(file, Modifier.fillMaxWidth().aspectRatio(16f / 9f)); if (signedIn) Hint("«En todos» la quita también de tu cuenta y de tus otros aparatos.") } },
        confirmButton = {
            if (signedIn) TextButton(onClick = { es.macrostudio.sync.ShotSync.deleteEverywhere(context, file) { onDeleted() }; onDismiss() }) { Text("Borrar en todos", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = { es.macrostudio.capture.Shots.delete(file); onDeleted(); onDismiss() }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            Row {
                if (signedIn) TextButton(onClick = { es.macrostudio.capture.Shots.delete(file); onDeleted(); onDismiss() }) { Text("Solo aquí") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

/** Barra de «📸 Mis capturas» en la cuenta: estado y botón de sincronizar. */
@Composable fun ShotSyncBar() {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { es.macrostudio.sync.ShotSync.syncNow(context) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(es.macrostudio.sync.ShotSync.status.ifBlank { "Tus capturas se comparten con tu cuenta" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { es.macrostudio.sync.ShotSync.syncNow(context) }, enabled = !es.macrostudio.sync.ShotSync.running) { Text(if (es.macrostudio.sync.ShotSync.running) "…" else "🔄 Sincronizar") }
    }
}

@Composable fun ShotPickerDialog(onDismiss: () -> Unit, onBurst: () -> Unit, onChoose: (File) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<File?>(null) }
    val shots = remember(Studio.screenshotVersion, version) { es.macrostudio.capture.Shots.list(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mis capturas (${shots.size})") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShotSyncBar()
                if (shots.isEmpty()) {
                    Text("Todavía no tienes capturas guardadas.")
                    Hint("Con «📸 Hacer varias capturas» vas al juego, pulsas 📷 Foto en cada pantalla que necesites y luego recortas aquí los botones sin volver al juego.")
                }
                shots.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { file ->
                            Column(Modifier.weight(1f)) {
                                ShotThumb(file, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { onChoose(file) })
                                TextButton(onClick = { deleting = file }, contentPadding = PaddingValues(0.dp)) { Text("🗑 Borrar", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onBurst) { Text("📸 Hacer más capturas") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
    deleting?.let { f -> ShotDeleteDialog(f, onDismiss = { deleting = null }) { version++ } }
}

/** Tocar y mantener pulsado. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

private val warningCache = mutableMapOf<String, String?>()

/** Avisa si un recorte se puede confundir: muy pequeño o casi de un solo color. */
fun templateWarning(saved: SavedTemplate): String? = warningCache.getOrPut(saved.template.file) {
    val t = saved.template
    val small = t.width * t.height < 3_000 || minOf(t.width, t.height) < 30
    val flat = runCatching {
        val b = BitmapFactory.decodeFile(File(Studio.context.filesDir, t.file).path) ?: return@runCatching false
        try {
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val step = maxOf(1, px.size / 4000)
            var n = 0; val sum = DoubleArray(3); val sq = DoubleArray(3)
            for (i in px.indices step step) { n++; for (c in 0..2) { val v = ((px[i] shr (c * 8)) and 255).toDouble(); sum[c] += v; sq[c] += v * v } }
            (0..2).all { c -> sq[c] / n - (sum[c] / n) * (sum[c] / n) < 600.0 }
        } finally { b.recycle() }
    }.getOrDefault(false)
    when {
        small && flat -> "Recorte muy pequeño y casi de un solo color: se confunde con otras partes de la pantalla. Recórtalo más grande, con texto o bordes."
        small -> "Recorte muy pequeño: puede confundirse con otras partes. Mejor recórtalo un poco más grande (con algo de texto o borde)."
        flat -> "Recorte casi de un solo color: se parece a muchas zonas de la pantalla y puede tocar donde no toca. Recorta algo con texto, dibujo o bordes."
        else -> null
    }
}

fun afterRuleLabel(value: AfterRule): String = when (value) {
    AfterRule.CONTINUE -> "sigue donde estaba"
    AfterRule.SKIP_STEP -> "salta el paso que esperaba"
    AfterRule.EXIT_LOOP -> "sale del bucle"
    AfterRule.RESTART -> "empieza la vuelta de nuevo"
    AfterRule.STOP -> "para la macro"
}

/**
 * Hace que un diálogo «a pantalla completa» ocupe de verdad toda la pantalla, también bajo
 * las barras del sistema (el contenido las respeta con systemBarsPadding). Sin esto, Android
 * dejaba la ventana más baja que su contenido y en horizontal los botones de abajo se cortaban.
 */
@Composable fun FullScreenDialogWindow() {
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) {
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            if (android.os.Build.VERSION.SDK_INT >= 30) w.attributes = w.attributes.also { it.fitInsetsTypes = 0 }
        }
    }
}
