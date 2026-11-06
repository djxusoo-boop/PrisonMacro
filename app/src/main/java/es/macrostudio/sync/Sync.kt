package es.macrostudio.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import es.macrostudio.core.Project
import es.macrostudio.projects.ProjectStore
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import org.json.JSONObject
import java.util.UUID

enum class SyncPhase { NOT_CONFIGURED, WAITING_FOR_CODE, CONNECTED, ERROR }

data class DeviceSession(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeenAtMs: Long,
    val revoked: Boolean = false
)

data class SyncSnapshot(
    val email: String = "",
    val phase: SyncPhase = SyncPhase.NOT_CONFIGURED,
    val sessions: List<DeviceSession> = emptyList(),
    val lastSyncAtMs: Long = 0L,
    val message: String = "Aún no hay una cuenta conectada"
)

data class CloudProject(
    val id: String,
    val name: String,
    val updatedAtMs: Long,
    val deviceId: String
)

/** Tres minutos sin presencia equivale a una sesión desconectada. */
fun activeSessionCount(sessions: List<DeviceSession>, nowMs: Long, graceMs: Long = 180_000L): Int =
    sessions.count { !it.revoked && it.lastSeenAtMs in (nowMs - graceMs)..nowMs }

/**
 * Firebase Auth + Realtime Database adapter. El acceso por correo usa un enlace de
 * un solo uso: Firebase exige confirmar la misma dirección al completarlo,
 * por lo que no se guarda ningún código ni contraseña en la app.
 */
class SyncStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("deviceId", it).apply()
    }
    private var sessionsListener: ValueEventListener? = null
    private var heartbeatScheduled = false
    var cloudProjects by mutableStateOf(emptyList<CloudProject>())
        private set
    var snapshot by mutableStateOf(read())
        private set

    private val heartbeat = object : Runnable {
        override fun run() {
            heartbeatScheduled = false
            if (snapshot.phase == SyncPhase.CONNECTED) {
                writePresence()
                scheduleHeartbeat()
            }
        }
    }

    init { refreshAuth() }

    fun isFirebaseConfigured(): Boolean = runCatching {
        FirebaseApp.getApps(appContext).isNotEmpty()
    }.getOrDefault(false)

    fun setWaitingForCode(email: String) {
        val normalized = validEmail(email)
        save(snapshot.copy(email = normalized, phase = SyncPhase.WAITING_FOR_CODE, message = "Preparando el enlace de acceso…"))
    }

    fun sendEmailLink(email: String, onResult: (String?) -> Unit) {
        val normalized = runCatching { validEmail(email) }.getOrElse {
            onResult(it.message ?: "Correo no válido")
            return
        }
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrElse {
            onResult("Firebase todavía no está inicializado")
            return
        }
        val settings = ActionCodeSettings.newBuilder()
            .setUrl("https://prison-ed653.firebaseapp.com/__/auth/links")
            .setHandleCodeInApp(true)
            .setAndroidPackageName(appContext.packageName, true, null)
            .build()
        save(snapshot.copy(email = normalized, phase = SyncPhase.WAITING_FOR_CODE, message = "Enviando enlace de acceso…"))
        auth.sendSignInLinkToEmail(normalized, settings).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                prefs.edit().putString("pendingEmail", normalized).apply()
                save(snapshot.copy(email = normalized, phase = SyncPhase.WAITING_FOR_CODE, message = "Enlace enviado a $normalized. Ábrelo desde ese correo para conectar este dispositivo."))
                onResult(null)
            } else onResult(task.exception?.localizedMessage ?: "No se pudo enviar el enlace")
        }
    }

    /** Completa el enlace recibido por Android App Links. */
    fun handleSignInIntent(intent: Intent?) {
        val link = intent?.dataString ?: return
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull() ?: return
        if (!auth.isSignInWithEmailLink(link)) return
        val email = prefs.getString("pendingEmail", null).orEmpty()
        if (email.isBlank()) {
            save(snapshot.copy(phase = SyncPhase.ERROR, message = "Abre el enlace en el mismo dispositivo que solicitó el acceso."))
            return
        }
        auth.signInWithEmailLink(email, link).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                prefs.edit().remove("pendingEmail").apply()
                save(snapshot.copy(email = auth.currentUser?.email ?: email, phase = SyncPhase.CONNECTED, message = "Cuenta conectada. Sincronización privada activa."))
                startPresence()
                refreshCloudProjects()
            } else save(snapshot.copy(phase = SyncPhase.ERROR, message = task.exception?.localizedMessage ?: "Enlace no válido o caducado"))
        }
    }

    fun refreshAuth() {
        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        if (user == null) {
            cloudProjects = emptyList()
            if (snapshot.phase == SyncPhase.CONNECTED) save(snapshot.copy(phase = SyncPhase.NOT_CONFIGURED, message = "Sin cuenta conectada"))
        } else {
            save(snapshot.copy(email = user.email.orEmpty(), phase = SyncPhase.CONNECTED, message = "Cuenta conectada. Sincronización privada activa."))
            startPresence()
            refreshCloudProjects()
        }
    }

    fun signOut() {
        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        if (user != null) {
            runCatching { database().getReference("users/${user.uid}/devices/$deviceId").updateChildren(mapOf("revoked" to true)) }
        }
        stopPresence()
        runCatching { FirebaseAuth.getInstance().signOut() }
        cloudProjects = emptyList()
        save(SyncSnapshot(message = "Sesión cerrada en este dispositivo"))
    }

    /** Marca presencia cada 60 segundos y observa la lista privada de dispositivos. */
    private fun startPresence() {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: return
        val devices = database().getReference("users/$uid/devices")
        sessionsListener?.let { devices.removeEventListener(it) }
        sessionsListener = object : ValueEventListener {
            override fun onDataChange(value: DataSnapshot) {
                val sessions = value.children.map { child ->
                    DeviceSession(child.key.orEmpty(), child.child("name").getValue(String::class.java) ?: "Dispositivo", child.child("platform").getValue(String::class.java) ?: "Android", child.child("lastSeenAtMs").getValue(Long::class.java) ?: 0L, child.child("revoked").getValue(Boolean::class.java) ?: false)
                }.sortedByDescending { it.lastSeenAtMs }
                save(snapshot.copy(phase = SyncPhase.CONNECTED, sessions = sessions, message = "Cuenta conectada. Sincronización privada activa."))
            }
            override fun onCancelled(error: DatabaseError) {
                save(snapshot.copy(phase = SyncPhase.ERROR, message = "No se pudo leer las sesiones: ${error.message}"))
            }
        }.also { devices.addValueEventListener(it) }
        writePresence()
        scheduleHeartbeat()
    }

    private fun writePresence() {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: return
        runCatching {
            database().getReference("users/$uid/devices/$deviceId").updateChildren(mapOf("name" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(), "platform" to "Android", "lastSeenAtMs" to System.currentTimeMillis(), "revoked" to false))
        }.onFailure { save(snapshot.copy(phase = SyncPhase.ERROR, message = "No se pudo actualizar esta sesión: ${it.localizedMessage ?: "error de red"}")) }
    }

    private fun scheduleHeartbeat() {
        if (!heartbeatScheduled) {
            heartbeatScheduled = true
            handler.postDelayed(heartbeat, 60_000L)
        }
    }

    private fun stopPresence() {
        handler.removeCallbacks(heartbeat)
        heartbeatScheduled = false
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (uid != null) sessionsListener?.let { database().getReference("users/$uid/devices").removeEventListener(it) }
        sessionsListener = null
    }

    /** Serializa un proyecto para el siguiente paso de sincronización. */
    fun projectPayload(project: Project): String {
        val root = JSONObject(ProjectStore.encode(project))
        val images = JSONObject()
        val required = (project.templates.map { it.template } + listOfNotNull(project.template)).distinctBy { it.file }
        required.forEach { template ->
            val file = File(appContext.filesDir, template.file)
            require(file.isFile && file.length() <= 12_000_000L) { "Falta o es demasiado grande la captura ${template.file}" }
            val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            require(encoded.length <= 16_000_000) { "El proyecto ocupa demasiado para la nube" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            require(options.outWidth == template.width && options.outHeight == template.height) { "La captura ${template.file} no coincide con sus medidas" }
            images.put(template.file, encoded)
        }
        root.put("imagesBase64", images)
        return root.toString()
    }

    fun refreshCloudProjects() {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: return
        database().getReference("users/$uid/projects").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(value: DataSnapshot) {
                cloudProjects = value.children.mapNotNull { child ->
                    CloudProject(child.key.orEmpty(), child.child("name").getValue(String::class.java) ?: "Proyecto", child.child("updatedAtMs").getValue(Long::class.java) ?: 0L, child.child("deviceId").getValue(String::class.java).orEmpty())
                }.sortedByDescending { it.updatedAtMs }
            }
            override fun onCancelled(error: DatabaseError) { save(snapshot.copy(phase = SyncPhase.ERROR, message = "No se pudieron leer los proyectos: ${error.message}")) }
        })
    }

    fun uploadProject(project: Project, onResult: (String?) -> Unit) {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (uid == null) { onResult("Conecta una cuenta antes de subir proyectos"); return }
        val result = runCatching { projectPayload(project) }
        if (result.isFailure) { onResult(result.exceptionOrNull()?.message ?: "No se pudo preparar el proyecto"); return }
        database().getReference("users/$uid/projects/${project.id}").setValue(mapOf("name" to project.name, "updatedAtMs" to System.currentTimeMillis(), "deviceId" to deviceId, "payload" to result.getOrThrow())).addOnCompleteListener { task ->
            if (task.isSuccessful) { refreshCloudProjects(); onResult(null) } else onResult(task.exception?.localizedMessage ?: "No se pudo subir el proyecto")
        }
    }

    fun downloadProject(id: String, onResult: (String?, String?) -> Unit) {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (uid == null) { onResult(null, "Conecta una cuenta antes de descargar proyectos"); return }
        database().getReference("users/$uid/projects/$id/payload").get().addOnCompleteListener { task ->
            if (task.isSuccessful) onResult(task.result.getValue(String::class.java), null) else onResult(null, task.exception?.localizedMessage ?: "No se pudo descargar el proyecto")
        }
    }

    private fun database(): FirebaseDatabase = FirebaseDatabase.getInstance("https://prison-ed653.firebaseio.com")

    fun clearLocalSetup() {
        stopPresence()
        save(SyncSnapshot())
    }

    private fun validEmail(value: String): String {
        val normalized = value.trim().lowercase()
        require(normalized.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) { "Correo no válido" }
        return normalized
    }

    private fun read(): SyncSnapshot = runCatching {
        val root = JSONObject(prefs.getString("snapshot", "{}")!!)
        SyncSnapshot(
            email = root.optString("email"),
            phase = runCatching { SyncPhase.valueOf(root.optString("phase")) }.getOrDefault(SyncPhase.NOT_CONFIGURED),
            lastSyncAtMs = root.optLong("lastSyncAtMs"),
            message = root.optString("message", "Aún no hay una cuenta conectada")
        )
    }.getOrDefault(SyncSnapshot())

    private fun save(value: SyncSnapshot) {
        snapshot = value
        val root = JSONObject().put("email", value.email).put("phase", value.phase.name).put("lastSyncAtMs", value.lastSyncAtMs).put("message", value.message)
        prefs.edit().putString("snapshot", root.toString()).apply()
    }
}
