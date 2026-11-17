package es.macrostudio.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import es.macrostudio.BuildConfig
import es.macrostudio.core.Studio
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Actualización por wifi desde el Mac (o PC) donde se compila la app.
 *
 * El ordenador ejecuta `scripts/serve-updates.sh`, que publica en
 * `http://<ordenador>:8765/` un `version.json` y la APK. La app compara el
 * versionCode, descarga la APK, comprueba su SHA-256 y se la pasa a Android,
 * que pide confirmación. Android solo acepta la actualización si está firmada
 * con la misma clave que la app instalada.
 *
 * Solo se aceptan direcciones de la red local: el servidor no usa HTTPS.
 */
object Updater {
    const val DEFAULT_PORT = 8765

    /** Actualización encontrada al abrir la app (o al pulsar ⬆), para el aviso de la pantalla principal. */
    var available by androidx.compose.runtime.mutableStateOf<Info?>(null)
    var availableAt by androidx.compose.runtime.mutableStateOf("")
    var message by androidx.compose.runtime.mutableStateOf("")
    var busy by androidx.compose.runtime.mutableStateOf(false)
    private var checkedOnStart = false

    /** Al abrir la app: mira en silencio si el ordenador tiene una versión más nueva. */
    fun checkOnStart(context: Context) {
        if (checkedOnStart) return
        checkedOnStart = true
        Finder.quick(context) { address -> if (address != null) check(address) { info, _ -> if (info != null && isNewer(info)) { available = info; availableAt = address } } }
    }

    /** Botón ⬆: busca el ordenador (aunque haya cambiado de IP) y la versión. */
    fun checkNow(context: Context, done: (String) -> Unit) {
        busy = true; message = "Buscando el ordenador…"
        Finder.ensure(context, progress = { message = it }) { address ->
            if (address == null) { busy = false; message = ""; done("No encuentro el ordenador. Comprueba que está encendido, en la misma wifi y con el buzón abierto (icono «Abrir buzón PrisonMacro» del Escritorio del Mac)."); return@ensure }
            message = "Mirando si hay versión nueva…"
            check(address) { info, error ->
                busy = false; message = ""
                when {
                    error != null -> done(error)
                    info != null && isNewer(info) -> { available = info; availableAt = address; done("Hay una versión nueva: ${info.versionName}") }
                    else -> done("Ya tienes la última versión (${BuildConfig.VERSION_NAME}).")
                }
            }
        }
    }

    /** «⬇ Instalar ahora» del aviso. */
    fun installAvailable(activity: android.app.Activity, done: (String?) -> Unit) {
        val info = available ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            done("Permite a PrisonMacro instalar apps (activa el interruptor y vuelve), y pulsa otra vez «Instalar ahora».")
            activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${activity.packageName}")))
            return
        }
        busy = true; message = "Descargando ${info.versionName}…"
        downloadAndInstall(activity, availableAt, info, progress = { message = "Descargando ${info.versionName}… $it %" }) { error ->
            busy = false
            message = error ?: "Descargada. Pulsa «Instalar» en el aviso de Android. Tus macros se conservan."
            done(error)
        }
    }
    /** Dirección del ordenador visto desde BlueStacks en ese mismo ordenador. */
    const val SAME_COMPUTER = "10.0.2.2:$DEFAULT_PORT"

    data class Info(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val sizeBytes: Long)

    private val worker = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** "192.168.1.20" → "192.168.1.20:8765". Devuelve null si no es una dirección local válida. */
    fun normalizeAddress(text: String): String? {
        val clean = text.trim().removePrefix("http://").trimEnd('/')
        if (clean.isEmpty() || clean.any { it.isWhitespace() || it == '/' || it == '@' }) return null
        val host = clean.substringBefore(':')
        val port = if (':' in clean) clean.substringAfter(':').toIntOrNull() ?: return null else DEFAULT_PORT
        if (port !in 1..65535 || !isLocalHost(host)) return null
        return "$host:$port"
    }

    /** Red local: 10.x, 172.16–31.x, 192.168.x, 127.x o nombres .local. */
    fun isLocalHost(host: String): Boolean {
        if (host.endsWith(".local") && host.length > 6 && host.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 && it == v.toString() } ?: return false }
        return n[0] == 10 || n[0] == 127 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }

    fun parseInfo(json: String): Info {
        val o = JSONObject(json)
        val apk = o.getString("apk")
        require(apk.matches(Regex("[A-Za-z0-9._-]+\\.apk"))) { "Nombre de APK no válido" }
        val sha = o.getString("sha256").lowercase()
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "Huella SHA-256 no válida" }
        val size = o.getLong("sizeBytes")
        require(size in 1..200_000_000) { "Tamaño no válido" }
        return Info(o.getInt("versionCode"), o.getString("versionName").take(40), apk, sha, size)
    }

    fun isNewer(info: Info) = info.versionCode > BuildConfig.VERSION_CODE

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 4000; readTimeout = 15000; instanceFollowRedirects = false
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException, is java.net.UnknownHostException ->
            "No encuentro el ordenador. Comprueba que en él está abierto el buzón (icono «Abrir buzón PrisonMacro» del Escritorio del Mac) y que la dirección es la que muestra."
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Pregunta al ordenador qué versión tiene. Responde en el hilo principal. */
    fun check(address: String, done: (Info?, String?) -> Unit) {
        worker.execute {
            val result = runCatching {
                val c = open("http://$address/version.json")
                try {
                    if (c.responseCode != 200) error("El ordenador respondió ${c.responseCode}")
                    parseInfo(c.inputStream.bufferedReader().use { it.readText().take(10_000) })
                } finally { c.disconnect() }
            }
            main.post { result.fold({ done(it, null) }, { done(null, friendly(it)) }) }
        }
    }

    /** Descarga las macros que el ordenador ofrece (outputs/macros). Responde en el hilo principal. */
    fun fetchMacros(address: String, done: (ByteArray?, String?) -> Unit) {
        worker.execute {
            val result = runCatching {
                val c = open("http://$address/macros.json")
                try {
                    if (c.responseCode == 404) error("El ordenador no tiene ninguna macro preparada para enviar.")
                    if (c.responseCode != 200) error("El ordenador respondió ${c.responseCode}")
                    c.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val read = input.read(buffer); if (read < 0) break; require(out.size() + read <= es.macrostudio.projects.Backup.MAX_BYTES) { "Archivo demasiado grande" }; out.write(buffer, 0, read) }
                        out.toByteArray()
                    }
                } finally { c.disconnect() }
            }
            main.post { result.fold({ done(it, null) }, { done(null, friendly(it)) }) }
        }
    }

    /** Deja macros en el buzón del ordenador para que el iPhone (u otro Android) las recoja. */
    fun sendMacros(address: String, body: ByteArray, done: (String?) -> Unit) {
        worker.execute {
            val error = runCatching {
                val c = open("http://$address/macros.json").apply {
                    requestMethod = "POST"; doOutput = true; readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                    setFixedLengthStreamingMode(body.size)
                }
                try {
                    c.outputStream.use { it.write(body) }
                    when (c.responseCode) {
                        200 -> Unit
                        404, 501 -> error("El ordenador tiene una versión antigua del buzón. Ciérralo y vuelve a abrirlo con el icono «Abrir buzón PrisonMacro» del Escritorio del Mac.")
                        else -> error("El ordenador respondió ${c.responseCode}")
                    }
                } finally { c.disconnect() }
            }.exceptionOrNull()
            main.post { done(error?.let(::friendly)) }
        }
    }

    /** Manda el registro de este aparato al buzón del ordenador (para ver qué pasó en cada BlueStacks). */
    fun sendLog(address: String, text: String, done: (String?) -> Unit) {
        val body = text.toByteArray(Charsets.UTF_8).let { if (it.size > 900_000) it.copyOfRange(it.size - 900_000, it.size) else it }
        worker.execute {
            val error = runCatching {
                val c = open("http://$address/registro.txt").apply {
                    requestMethod = "POST"; doOutput = true; readTimeout = 30000
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    setFixedLengthStreamingMode(body.size)
                }
                try {
                    c.outputStream.use { it.write(body) }
                    when (c.responseCode) {
                        200 -> Unit
                        404, 501 -> error("El ordenador tiene una versión antigua del buzón. Ciérralo y vuelve a abrirlo con el icono «Abrir buzón PrisonMacro» del Escritorio del Mac.")
                        else -> error("El ordenador respondió ${c.responseCode}")
                    }
                } finally { c.disconnect() }
            }.exceptionOrNull()
            main.post { done(error?.let(::friendly)) }
        }
    }

    /** Manda capturas enteras (📸) al buzón del ordenador, una tras otra. [done] recibe cuántas llegaron o el error. */
    fun sendShots(address: String, files: List<java.io.File>, done: (Int, String?) -> Unit) {
        worker.execute {
            var sent = 0
            val error = runCatching {
                files.forEach { file ->
                    val body = file.readBytes()
                    val c = open("http://$address/captura.png").apply {
                        requestMethod = "POST"; doOutput = true; readTimeout = 30000
                        setRequestProperty("Content-Type", "image/png")
                        setFixedLengthStreamingMode(body.size)
                    }
                    try {
                        c.outputStream.use { it.write(body) }
                        when (c.responseCode) {
                            200 -> sent++
                            404, 501 -> error("El ordenador tiene una versión antigua del buzón. Ciérralo y vuelve a abrirlo con el icono «Abrir buzón PrisonMacro» del Escritorio del Mac.")
                            else -> error("El ordenador respondió ${c.responseCode}")
                        }
                    } finally { c.disconnect() }
                }
            }.exceptionOrNull()
            main.post { done(sent, error?.let(::friendly)) }
        }
    }

    /** Descarga la APK, comprueba que llegó entera y pide a Android que la instale. */
    fun downloadAndInstall(context: Context, address: String, info: Info, progress: (Int) -> Unit, done: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val error = runCatching {
                val file = File(app.cacheDir, "update.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                val c = open("http://$address/${info.apk}")
                try {
                    if (c.responseCode != 200) error("El ordenador respondió ${c.responseCode}")
                    var total = 0L; var lastPct = -1
                    c.inputStream.use { input -> file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            total += n; if (total > info.sizeBytes) error("La descarga es más grande de lo anunciado")
                            digest.update(buf, 0, n); out.write(buf, 0, n)
                            val pct = (total * 100 / info.sizeBytes).toInt()
                            if (pct != lastPct) { lastPct = pct; main.post { progress(pct) } }
                        }
                    } }
                    if (total != info.sizeBytes) error("La descarga se cortó")
                } finally { c.disconnect() }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha != info.sha256) { file.delete(); error("La APK descargada no coincide con la del ordenador") }
                install(app, file)
            }.exceptionOrNull()
            main.post { done(error?.let(::friendly)) }
        }
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("PrisonMacro.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Recibe la respuesta de Android: pedir confirmación al usuario o informar del error. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Actualización cancelada"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "No se pudo actualizar: la APK está firmada con otra clave. Instálala con el botón APK."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "No hay espacio suficiente para actualizar"
                    else -> "No se pudo actualizar: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status")
                }
                runCatching { Studio.log(message) }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
