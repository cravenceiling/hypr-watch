package local.hyprwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.BindException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * The listener paired agents reach over Wi-Fi: TLS with a keystore key on port 8767 (any free port when that
 * one is taken), open only while "Allow agents on Wi-Fi" is on. It binds every interface so a new DHCP
 * address needs no rebind, and refuses each socket that did not arrive on an address of the current Wi-Fi
 * network (not mobile data, a VPN or a hotspot). mDNS advertises the real port. [serve] answers allowed
 * routes as the loopback API does; [AgentApi] decides which.
 */
class AgentLan(private val context: Context, private val serve: (method: String, path: String, body: JSONObject, raw: ByteArray) -> JSONObject) {
    private val now = { System.currentTimeMillis() }
    private val store = AgentStore(File(context.filesDir, "agents.json"), now, SecureRandom()) { Log.w("HyprWatch", it) }

    /**
     * Starts, stops, mDNS, the pairing expiry and socket deadlines run here, one at a time. Timers still
     * pending at shutdown are dropped, so a stopping service doesn't wait out a pairing window.
     */
    private val control = ScheduledThreadPoolExecutor(1).apply { executeExistingDelayedTasksAfterShutdownPolicy = false }

    // Queued, not awaited: stop() closes the window on the control thread itself.
    private val window = PairingWindow(SystemClock::elapsedRealtime, now, SecureRandom()) {
        try {
            control.execute { advertise() }
        } catch (ignored: RejectedExecutionException) {
        }
    }
    private val failures = FailedAuth(now)
    private val api = AgentApi(store, window, failures, Build.MODEL)
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** At most [MAX_SOCKETS] agent sockets; one more is closed at accept. Apart from the loopback API's threads. */
    private val workers = ThreadPoolExecutor(0, MAX_SOCKETS, 30, TimeUnit.SECONDS, SynchronousQueue())
    private val sockets = Collections.synchronizedSet(HashSet<Socket>())
    private val perAddress = PerAddress(MAX_PER_ADDRESS)

    @Volatile private var server: SSLServerSocket? = null

    /** Addresses and prefixes of each current Wi-Fi network, from its LinkProperties. */
    private val wifi = ConcurrentHashMap<Network, List<LinkAddress>>()
    private var watching: ConnectivityManager.NetworkCallback? = null
    private var registration: NsdManager.RegistrationListener? = null

    /** Whether the service must stay up for agents even with the watch released. */
    fun enabled(): Boolean = store.enabled()

    /** At service start: opens the listener when the switch was left on. */
    fun resume() {
        control.execute {
            try {
                start()
            } catch (e: Exception) {
                Log.i("HyprWatch", "Agent listener failed to start: ${e.javaClass.simpleName}: ${e.message}")
                stop()
            }
        }
    }

    fun shutdown() {
        control.execute { stop() }
        control.shutdown()
        try {
            control.awaitTermination(2, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
        }
        workers.shutdownNow()
    }

    /** The loopback-only management routes the phone's Settings use; null for any other path. */
    fun route(method: String, path: String, body: JSONObject): JSONObject? {
        when {
            method == "GET" && path == "/api/agents" -> return status()
            method == "PUT" && path == "/api/agents/lan" -> {
                val on = body.opt("enabled") as? Boolean ?: throw IllegalArgumentException("enabled must be true or false")
                onControl {
                    store.setEnabled(on)
                    if (!on) return@onControl stop()
                    try {
                        start()
                    } catch (e: Exception) {
                        store.setEnabled(false)
                        stop()
                        throw IOException("Couldn't start listening on Wi-Fi: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                return status()
            }
            method == "POST" && path == "/api/agents/pairing" -> {
                if (server == null) throw IllegalArgumentException("Turn on Allow agents on Wi-Fi first")
                val pairing = window.open()
                control.schedule({ window.expire() }, Pairing.WINDOW_MS + 100, TimeUnit.MILLISECONDS)
                return pairing
            }
            method == "DELETE" && path == "/api/agents/pairing" -> {
                window.close()
                return JSONObject().put("closed", true)
            }
            method == "DELETE" && path.startsWith("/api/agents/") -> {
                if (!store.revoke(path.substring(12))) throw IllegalArgumentException("That computer isn't paired")
                return JSONObject().put("deleted", true)
            }
        }
        return null
    }

    private fun status(): JSONObject {
        val listening = server?.let { JSONObject().put("host", wifiHost() ?: JSONObject.NULL).put("port", it.localPort) }
        return JSONObject()
            .put("enabled", store.enabled())
            .put("listening", listening ?: JSONObject.NULL)
            .put("wifi", wifi.isNotEmpty())
            .put("pairing", window.json() ?: JSONObject.NULL)
            .put("last_pairing", window.ended() ?: JSONObject.NULL)
            .put("agents", store.list())
            .put("battery_exempt", power.isIgnoringBatteryOptimizations(context.packageName))
    }

    /** Runs [task] on the control thread and waits for it; its failure is the caller's. */
    private fun onControl(task: () -> Unit) {
        try {
            control.submit(Callable { task() }).get(15, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause as? Exception ?: e
        } catch (e: RejectedExecutionException) {
            // The service is stopping; its shutdown closes everything anyway.
        }
    }

    /** On the control thread. */
    private fun start() {
        if (server != null || !store.enabled()) return
        val (key, cert) = identity()
        val spki = Pairing.sha256(cert.publicKey.encoded)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(arrayOf(KeyManager(key, cert)), null, SecureRandom())
        val socket = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
        // Set first, so a failure from here on leaves something for stop() to close.
        server = socket
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(PORT), 16)
        } catch (e: BindException) {
            socket.bind(InetSocketAddress(0), 16)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                wifi[network] = link.linkAddresses
            }

            override fun onLost(network: Network) {
                wifi.remove(network)
            }
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        watching = callback
        val accept = Thread({ accept(socket, spki) }, "agents-lan")
        accept.isDaemon = true
        accept.start()
        advertise()
        Log.i("HyprWatch", "Agent listener on port ${socket.localPort}")
    }

    /** On the control thread: closes the listener and every agent socket; pairings stay. */
    private fun stop() {
        window.close()
        val socket = server ?: return
        server = null
        try {
            socket.close()
        } catch (ignored: IOException) {
        }
        for (s in synchronized(sockets) { sockets.toList() }) close(s)
        watching?.let { connectivity.unregisterNetworkCallback(it) }
        watching = null
        wifi.clear()
        advertise()
    }

    private fun accept(socket: SSLServerSocket, spki: ByteArray) {
        while (!socket.isClosed) {
            val s = try {
                socket.accept() as SSLSocket
            } catch (e: IOException) {
                break
            }
            val ip = s.inetAddress?.hostAddress ?: ""
            // Checked per socket rather than by binding the Wi-Fi address, so an address change needs no rebind.
            // The peer must be on the link too: a routed or forwarded connection to the Wi-Fi address is refused.
            val links = wifi.values.flatten()
            val onWifi = links.any { it.address == s.localAddress } && OnLink.peer(s.inetAddress, links.map { it.address to it.prefixLength })
            if (!onWifi || failures.blocked(ip) || !perAddress.acquire(ip)) {
                close(s)
                continue
            }
            // A peer that hasn't sent an authenticated request within the deadline is closed, however slowly it trickles bytes.
            val authenticated = AtomicBoolean()
            val deadline = try {
                control.schedule({ if (!authenticated.get()) close(s) }, DEADLINE_S, TimeUnit.SECONDS)
            } catch (e: RejectedExecutionException) {
                null
            }
            try {
                workers.execute { handle(s, ip, spki, authenticated, deadline) }
            } catch (e: RejectedExecutionException) {
                deadline?.cancel(false)
                perAddress.release(ip)
                close(s)
            }
        }
    }

    private fun handle(s: SSLSocket, ip: String, spki: ByteArray, authenticated: AtomicBoolean, deadline: ScheduledFuture<*>?) {
        sockets.add(s)
        var wake: PowerManager.WakeLock? = null
        try {
            if (server == null || deadline == null) return
            s.soTimeout = 10_000
            s.startHandshake()
            // An incoming connection wakes the phone, but it may sleep again mid-request; held for this request only,
            // and only once the handshake is done, so a peer that never finishes one holds nothing.
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyprWatch:agent").apply {
                setReferenceCounted(false)
                acquire(45_000)
            }
            s.soTimeout = 40_000
            val input = s.inputStream
            val out = s.outputStream
            Http.serve(input, out) { head -> api.handle(head, input, out, ip, spki, { authenticated.set(true) }, serve) }
        } catch (ignored: Throwable) {
            // Any failure, an Error too, ends this request only; the service and its other sockets carry on.
        } finally {
            deadline?.cancel(false)
            close(s)
            sockets.remove(s)
            perAddress.release(ip)
            try {
                wake?.release()
            } catch (ignored: RuntimeException) {
            }
        }
    }

    private fun close(s: Socket) {
        try {
            s.close()
        } catch (ignored: IOException) {
        }
    }

    private fun wifiHost(): String? =
        wifi.values.flatten().map { it.address }.firstOrNull { it is Inet4Address && !it.isLinkLocalAddress && !it.isLoopbackAddress }?.hostAddress

    /**
     * Registers the service again with the current port and pairing state (`pair=1` while a code is open),
     * or only unregisters while the listener is off. On the control thread. mDNS is just a hint for finding
     * the phone: the PC checks the certificate pin on every connection.
     */
    private fun advertise() {
        registration?.let {
            try {
                nsd.unregisterService(it)
            } catch (ignored: IllegalArgumentException) {
            }
        }
        registration = null
        val port = server?.localPort ?: return
        val info = NsdServiceInfo()
        info.serviceName = "Hypr Watch ${Build.MODEL}"
        info.serviceType = SERVICE_TYPE
        info.port = port
        info.setAttribute("v", "1")
        info.setAttribute("id", store.phoneId)
        info.setAttribute("pair", if (window.isOpen()) "1" else "0")
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}

            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.i("HyprWatch", "mDNS registration failed: $code")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}

            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** The keystore key the TLS stack asks for by key type; only EC suites can use it. */
    private class KeyManager(private val key: PrivateKey, private val cert: X509Certificate) : X509ExtendedKeyManager() {
        private fun alias(keyType: String?): String? = if (keyType == "EC" || keyType == "EC_EC") ALIAS else null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = alias(keyType)

        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = alias(keyType)

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = alias(keyType)?.let { arrayOf(it) }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(cert) else null

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    companion object {
        const val PORT = 8767
        const val SERVICE_TYPE = "_hyprwatch._tcp"
        private const val MAX_SOCKETS = 8
        private const val MAX_PER_ADDRESS = 2
        private const val DEADLINE_S = 15L
        private const val ALIAS = "hypr-watch-agent-tls-v1"

        /**
         * The phone's TLS identity: an EC P-256 key that never leaves the keystore, with the self-signed
         * certificate the keystore makes for it. A reinstall makes a new one, so PCs must pair again.
         * DIGEST_NONE because the TLS stack signs an already hashed handshake with keystore keys.
         */
        private fun identity(): Pair<PrivateKey, X509Certificate> {
            val keys = KeyStore.getInstance("AndroidKeyStore")
            keys.load(null)
            if (!keys.containsAlias(ALIAS)) {
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=Hypr Watch"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(1_577_836_800_000L))
                    .setCertificateNotAfter(Date(4_102_444_799_000L))
                    .build()
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                generator.initialize(spec)
                generator.generateKeyPair()
            }
            val entry = keys.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            return entry.privateKey to entry.certificate as X509Certificate
        }

        /** For the boot receiver: whether the switch is on, without starting anything. */
        fun enabled(context: Context): Boolean = AgentStore.enabled(File(context.filesDir, "agents.json"))
    }
}
