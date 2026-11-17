package es.macrostudio.update

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Encuentra solo el ordenador que tiene abierto «bash scripts/serve-updates.sh», aunque
 * haya cambiado de IP. Prueba, por este orden:
 *  1. la dirección guardada y la del mismo ordenador (BlueStacks: 10.0.2.2);
 *  2. el anuncio de la red (Bonjour/NSD «_prisonmacro._tcp»), si llega;
 *  3. un barrido rápido de las redes de casa más típicas (192.168.1.x, 10.0.0.x…),
 *     preguntando a cada equipo «/hello». Solo responde PrisonMacro.
 * La dirección encontrada se guarda para la próxima vez.
 */
object Finder {
    private const val PREFS = "studio"
    private const val KEY = "updateAddress"
    private const val SUBNETS = "knownSubnets"
    private val worker = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val commonSubnets = listOf(
        "192.168.1", "192.168.0", "10.0.0", "192.168.18", "192.168.100", "192.168.2", "192.168.10",
        "10.0.1", "192.168.31", "192.168.50", "192.168.8", "192.168.68", "192.168.88", "192.168.178", "172.16.0"
    )

    fun saved(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString(KEY, Updater.SAME_COMPUTER) ?: Updater.SAME_COMPUTER

    /** ¿Responde ahí un PrisonMacro? */
    fun isServer(address: String, timeoutMs: Int = 450): Boolean = runCatching {
        val c = (URL("http://$address/hello").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs * 2; instanceFollowRedirects = false
        }
        try { c.responseCode == 200 && c.inputStream.bufferedReader().use { it.readText().take(200) }.contains("PrisonMacro") }
        finally { c.disconnect() }
    }.getOrDefault(false)

    /** Usa la dirección guardada si responde; si no, busca el ordenador. Responde en el hilo principal. */
    fun ensure(context: Context, progress: (String) -> Unit, done: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val saved = Updater.normalizeAddress(saved(app))
            val result = if (saved != null && isServer(saved, 1500)) saved else find(app) { text -> main.post { progress(text) } }
            if (result != null) remember(app, result)
            main.post { done(result) }
        }
    }

    /** Solo la dirección guardada y la del mismo ordenador (rápido, para el arranque). */
    fun quick(context: Context, done: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = listOfNotNull(Updater.normalizeAddress(saved(app)), Updater.SAME_COMPUTER).distinct().firstOrNull { isServer(it, 1200) }
            main.post { done(result) }
        }
    }

    /** Busca siempre (botón «🔎 Buscar el ordenador»). Responde en el hilo principal. */
    fun search(context: Context, progress: (String) -> Unit, done: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = find(app) { text -> main.post { progress(text) } }
            if (result != null) remember(app, result)
            main.post { done(result) }
        }
    }

    private fun remember(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val subnet = address.substringBefore(':').substringBeforeLast('.')
        val known = (listOf(subnet) + prefs.getString(SUBNETS, "").orEmpty().split(',').filter { it.isNotBlank() }).distinct().take(6)
        prefs.edit().putString(KEY, address).putString(SUBNETS, known.joinToString(",")).apply()
    }

    private fun find(context: Context, progress: (String) -> Unit): String? {
        val port = Updater.DEFAULT_PORT
        progress("Buscando el ordenador…")
        val first = listOfNotNull(Updater.normalizeAddress(saved(context)), Updater.SAME_COMPUTER).distinct()
        first.firstOrNull { isServer(it, 800) }?.let { return it }

        progress("Escuchando la red…")
        nsd(context)?.let { if (isServer(it, 1500)) return it }

        val prefs = context.getSharedPreferences(PREFS, 0)
        val subnets = LinkedHashSet<String>()
        localSubnets().forEach { subnets += it }
        prefs.getString(SUBNETS, "").orEmpty().split(',').filter { it.isNotBlank() }.forEach { subnets += it }
        Updater.normalizeAddress(saved(context))?.substringBefore(':')?.substringBeforeLast('.')?.let { subnets += it }
        subnets += commonSubnets
        subnets.remove("10.0.2")   // red interna de BlueStacks / emulador

        val pool = Executors.newFixedThreadPool(64)
        try {
            for ((index, subnet) in subnets.withIndex()) {
                progress("Buscando en $subnet.x (${index + 1}/${subnets.size})…")
                val hit = AtomicReference<String?>(null)
                val tasks = (1..254).map { host ->
                    pool.submit {
                        if (hit.get() == null) { val address = "$subnet.$host:$port"; if (isServer(address)) hit.compareAndSet(null, address) }
                    }
                }
                tasks.forEach { runCatching { it.get(3, TimeUnit.SECONDS) } }
                hit.get()?.let { return it }
            }
        } finally { pool.shutdownNow() }
        return null
    }

    /** Las redes /24 de este aparato (vacío dentro de BlueStacks, que usa una red interna). */
    private fun localSubnets(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty().substringBeforeLast('.') }
            .filter { it.isNotBlank() && Updater.isLocalHost("$it.1") }
    }.getOrDefault(emptyList())

    /** Anuncio Bonjour del ordenador («_prisonmacro._tcp»). En BlueStacks no suele llegar. */
    private fun nsd(context: Context): String? {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = (resolved.host as? Inet4Address)?.hostAddress
                        if (host != null && result.compareAndSet(null, "$host:${resolved.port}")) latch.countDown()
                    }
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                })
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        return try {
            manager.discoverServices("_prisonmacro._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(2500, TimeUnit.MILLISECONDS)
            result.get()
        } catch (_: Exception) { null }
        finally { runCatching { manager.stopServiceDiscovery(listener) } }
    }
}
