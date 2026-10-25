package local.hyprwatch

import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One watch capability: the watch commands it handles, its connect hooks, HTTP routes, status and
 * settings. It reaches the watch and the phone only through [FeatureHost], so host tests run it
 * against FeatureRig. NotesService lists the features; [Features] runs them.
 */
interface WatchFeature {
    /** Its key in /api/status and its settings namespace: lowercase letters, digits and underscores. */
    val name: String

    /** Called once, before anything else. [FeatureHost.on], [FeatureHost.route] and the other registrations work only here. */
    fun install(host: FeatureHost)

    /**
     * After each authentication, in registration order, on the reader thread under the session lock.
     * Must not block: send, or [FeatureHost.submit] work that needs [FeatureHost.request].
     */
    fun onConnected() {}

    /**
     * After every disconnect or watch session restart, in registration order, on whichever thread saw
     * it end. Also runs after a connect that failed before [onConnected], so make it idempotent.
     * Must not block.
     */
    fun onDisconnected() {}

    /** Its object in /api/status, or null for none. Runs on an HTTP thread; must be cheap, no watch requests. */
    fun status(): JSONObject? = null

    /** The service is stopping, after the last [onDisconnected]. */
    fun close() {}
}

/**
 * What a [WatchFeature] can reach. Handlers, hooks, chunk/ack handlers and timer tasks run on the
 * link's threads, where [request] throws instead of blocking them. Routes and [submit]ted tasks may block.
 */
interface FeatureHost {
    /** True between authentication and the next disconnect. */
    val connected: Boolean

    val settings: FeatureSettings

    val phone: Phone

    /** Handles the watch-initiated command [type]/[sub] on the reader thread. One feature per command; type 1 is the session's. */
    fun on(type: Int, sub: Int, handler: (Map<Int, List<Any>>) -> Unit)

    /**
     * Serves [method] [path] after NotesService's token and Host/Origin checks. [path] starts with
     * `/api/`; `{name}` matches one non-empty segment and a final `{name*}` the rest of the path. At
     * the first segment where two matching routes differ, a literal beats `{name}`, which beats
     * `{name*}`; two routes of the same shape are refused. The handler's IllegalArgumentException
     * answers 400 with its message, [Http.Conflict] 409, any other exception 503.
     */
    fun route(method: String, path: String, handler: (ApiRequest) -> JSONObject)

    /**
     * Runs [block], a route's operation that waits on the watch or the network, as this feature's only
     * one and one of at most two across all features, so the other HTTP threads stay free for ordinary
     * requests. Throws [Http.Conflict] (409) at once when either limit is reached.
     */
    fun <T> operation(block: () -> T): T

    /** Decrypted channel-5 activity data, on the reader thread. */
    fun onChunk(handler: (ByteArray) -> Unit)

    /** On the reader thread while connected, after an ack freed room in the send window, so a refused send can go again. */
    fun onAcked(handler: () -> Unit)

    /**
     * Fire-and-forget on channel 1: waits only for room in the send window, never for a reply. False
     * when nothing was sent (not connected, window full), logged as "Dropped a [what]". Never throws.
     */
    fun send(cmd: ByteArray, what: String? = null): Boolean

    /**
     * As [send], but also false, without a log line, while as many frames await an ack as the watch
     * takes at once (its tx window). For bulk sends, which go on from [onAcked] and so never crowd
     * out other features' commands.
     */
    fun sendPaced(cmd: ByteArray, what: String? = null): Boolean

    /** The link's epoch, which every link reset (connect, disconnect, session restart) ends. Only [DataUpload] uses this and the next two. */
    fun linkEpoch(): Long

    /**
     * One upload chunk of link [epoch], plaintext on channel 2, in the same send window as
     * commands. Never blocks. False, sending nothing, while the window holds as many frames as the
     * watch takes at once: send the chunk again from [onAcked]. Commands are never held back for
     * upload data. Throws IOException when [epoch] has ended, when not connected or when the link
     * refuses the frame (too large, window full, writer closed); the upload cannot go on.
     */
    fun sendData(epoch: Long, chunk: ByteArray): Boolean

    /** Upload chunks of [epoch] the watch has not link-acked yet. Throws IOException once [epoch] has ended: its chunks were dropped, not acked. */
    fun dataInFlight(epoch: Long): Int

    /** [DataUpload]'s: true while an upload is reserved, requested or sent, so the link traces what the watch sends meanwhile (SppLink.uploadPhase). */
    fun traceUpload(on: Boolean)

    /**
     * Sends [cmd] and returns the watch's reply with the same type and subtype. Runs on the commands
     * thread, one at a time with reminder sync, within WatchSession.CALL_TIMEOUT_MS; a reply timeout
     * drops the link. Throws IOException when not connected, on a restart or when the watch rejects
     * it, and IllegalStateException on a link thread.
     */
    fun request(cmd: ByteArray): Map<Int, List<Any>>

    /** Runs [task] later on the commands thread, where it may [request]; dropped if this session ends first. Keep it to a few requests. */
    fun submit(task: () -> Unit)

    /** Runs [task] after [ms] on the timer thread; returns a cancel function. [task] must not block. */
    fun after(ms: Long, task: () -> Unit): () -> Unit

    /** Logs to logcat. Never pass notification titles or bodies. */
    fun log(message: String)
}

/**
 * A routed HTTP request. [query] is parsed on first use; a malformed or repeated parameter throws
 * IllegalArgumentException (400). [raw] is the body's bytes for a route with a large body
 * ([Http.bodyLimit]), whose [body] is left `{}` so the route can read the bytes without a String copy.
 */
class ApiRequest(val params: Map<String, String>, private val rawQuery: String, val body: JSONObject, val raw: ByteArray = ByteArray(0)) {
    val query: Map<String, String> by lazy { Http.parseQuery(rawQuery) }
}

/** Durable string settings: HealthStore's settings table on the phone, a map in tests. */
interface SettingsStore {
    fun setting(key: String): String?

    fun setting(key: String, value: String)
}

/** One feature's small JSON settings, stored under its own key prefix. Writes are durable when [put] returns. */
class FeatureSettings(private val store: SettingsStore, private val prefix: String) {
    fun get(key: String): JSONObject? = store.setting(prefix + checked(key))?.let(::JSONObject)

    fun put(key: String, value: JSONObject) = store.setting(prefix + checked(key), value.toString())

    private fun checked(key: String): String = key.also { require(it.matches(NAME)) { "Invalid setting key" } }
}

/** The link side of [Features]: WatchSession on a real link, FakeWatch in host tests. */
interface WatchPort {
    val connected: Boolean

    fun on(type: Int, sub: Int, handler: (Map<Int, List<Any>>) -> Unit)

    fun onChunk(handler: (ByteArray) -> Unit)

    fun onAcked(handler: () -> Unit)

    fun send(what: String, cmd: ByteArray): Boolean

    /** See [FeatureHost.sendPaced]. */
    fun sendPaced(what: String, cmd: ByteArray): Boolean

    fun linkEpoch(): Long

    /** See [FeatureHost.sendData]. */
    fun sendData(epoch: Long, chunk: ByteArray): Boolean

    fun dataInFlight(epoch: Long): Int

    fun traceUpload(on: Boolean)

    fun request(cmd: ByteArray): Map<Int, List<Any>>

    /** Runs [task] on the commands thread. */
    fun submit(task: () -> Unit)

    fun after(ms: Long, task: () -> Unit): () -> Unit
}

private val NAME = Regex("[a-z][a-z0-9_]{0,31}")

/**
 * Installs [features] on a [WatchPort], then fans out its events, routes HTTP requests and builds
 * their status. Rejects a bad or reused name, a command registered twice, two routes of the same
 * shape, and registration after install. Every handler, hook and status is guarded: a failure is
 * logged and the others still run.
 */
class Features(
    private val features: List<WatchFeature>,
    private val store: SettingsStore,
    private val phone: Phone,
    private val log: (String) -> Unit,
) {
    private class Route(val method: String, val path: String, val handler: (ApiRequest) -> JSONObject) {
        val pattern = path.split('/')

        /** Per segment: 0 literal, 1 `{name}`, 2 `{name*}`. */
        val ranks = pattern.map { if (!it.startsWith("{")) 0 else if (it.endsWith("*}")) 2 else 1 }

        /** Equal for routes that match exactly the same paths. */
        val shape = pattern.zip(ranks) { p, r -> if (r == 0) p else "{$r}" }
    }

    private val routes = ArrayList<Route>()
    private val commands = HashSet<Pair<Int, Int>>()
    private val chunkHandlers = ArrayList<(ByteArray) -> Unit>()
    private val ackHandlers = ArrayList<() -> Unit>()
    private val slowOperations = Semaphore(2)
    private var port: WatchPort? = null
    private var installing = false

    // Bumped by each connect and disconnect, so a submitted task never runs in a later session.
    @Volatile private var session = 0L
    private val onLinkThread = ThreadLocal.withInitial { false }

    init {
        val names = HashSet<String>()
        for (f in features) {
            require(f.name.matches(NAME) && f.name !in CORE_STATUS && names.add(f.name)) { "Feature name '${f.name}' is invalid or taken" }
        }
    }

    fun install(port: WatchPort) {
        check(this.port == null) { "Features are installed once" }
        this.port = port
        installing = true
        try {
            for (f in features) f.install(Host(f, port))
        } finally {
            installing = false
        }
        routes.sortWith(SPECIFIC_FIRST)
        if (chunkHandlers.isNotEmpty()) port.onChunk { c -> for (h in chunkHandlers) linkThread("activity chunk") { h(c) } }
        if (ackHandlers.isNotEmpty()) port.onAcked { if (port.connected) for (h in ackHandlers) linkThread("ack handler") { h() } }
    }

    /** After authentication; the caller holds the session lock. */
    fun connected() {
        session++
        for (f in features) linkThread("${f.name} connect hook") { f.onConnected() }
    }

    fun disconnected() {
        session++
        for (f in features) linkThread("${f.name} disconnect hook") { f.onDisconnected() }
    }

    /** The most specific feature route matching [method] and [target] (path and query), or null. [raw]: see [ApiRequest]. */
    fun route(method: String, target: String, body: JSONObject, raw: ByteArray = ByteArray(0)): JSONObject? {
        val q = target.indexOf('?')
        val path = (if (q < 0) target else target.substring(0, q)).split('/')
        for (r in routes) {
            if (r.method != method) continue
            val params = match(r.pattern, path) ?: continue
            return r.handler(ApiRequest(params, if (q < 0) "" else target.substring(q + 1), body, raw))
        }
        return null
    }

    /** Puts each feature's status under its name; one that throws gets `{error}` instead. */
    fun status(into: JSONObject) {
        for (f in features) {
            val s = try {
                f.status()
            } catch (e: Exception) {
                // The class only: a message can quote stored settings.
                val error = "Status unavailable (${e.javaClass.simpleName})"
                log("${f.name} status failed: $error")
                JSONObject().put("error", error)
            }
            s?.let { into.put(f.name, it) }
        }
    }

    fun close() {
        for (f in features) guard("${f.name} close") { f.close() }
    }

    private fun match(pattern: List<String>, path: List<String>): Map<String, String>? {
        val params = HashMap<String, String>()
        for ((i, p) in pattern.withIndex()) {
            if (p.startsWith("{") && p.endsWith("*}")) {
                if (path.size <= i) return null
                params[p.substring(1, p.length - 2)] = path.subList(i, path.size).joinToString("/")
                return params
            }
            val s = path.getOrNull(i) ?: return null
            if (p.startsWith("{")) {
                if (s.isEmpty()) return null
                params[p.substring(1, p.length - 1)] = s
            } else if (p != s) {
                return null
            }
        }
        return if (path.size == pattern.size) params else null
    }

    private inline fun linkThread(what: String, block: () -> Unit) {
        val outer = onLinkThread.get()
        onLinkThread.set(true)
        try {
            guard(what, block)
        } finally {
            onLinkThread.set(outer)
        }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("$what failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private inner class Host(private val f: WatchFeature, private val port: WatchPort) : FeatureHost {
        override val connected: Boolean get() = port.connected
        override val settings = FeatureSettings(store, "feature.${f.name}.")
        override val phone: Phone get() = this@Features.phone

        override fun on(type: Int, sub: Int, handler: (Map<Int, List<Any>>) -> Unit) {
            registering()
            require(type != 1) { "Watch command type 1 belongs to the session" }
            require(commands.add(type to sub)) { "Watch command $type/$sub already has a handler" }
            port.on(type, sub) { cmd -> linkThread("${f.name} command $type/$sub") { handler(cmd) } }
        }

        override fun route(method: String, path: String, handler: (ApiRequest) -> JSONObject) {
            registering()
            require(method in METHODS && path.startsWith("/api/")) { "Invalid route $method $path" }
            val route = Route(method, path, handler)
            require(route.ranks.dropLast(1).none { it == 2 }) { "Only the last segment of $path may match the rest" }
            routes.firstOrNull { it.method == method && it.shape == route.shape }?.let { throw IllegalArgumentException("Route $method $path is taken by ${it.path}") }
            routes.add(route)
        }

        private val busy = AtomicBoolean(false)

        override fun <T> operation(block: () -> T): T {
            if (!busy.compareAndSet(false, true)) throw Http.Conflict(BUSY)
            try {
                if (!slowOperations.tryAcquire()) throw Http.Conflict(BUSY)
                try {
                    return block()
                } finally {
                    slowOperations.release()
                }
            } finally {
                busy.set(false)
            }
        }

        override fun onChunk(handler: (ByteArray) -> Unit) {
            registering()
            chunkHandlers.add(handler)
        }

        override fun onAcked(handler: () -> Unit) {
            registering()
            ackHandlers.add(handler)
        }

        override fun send(cmd: ByteArray, what: String?): Boolean = port.send(what ?: "command for ${f.name}", cmd)

        override fun sendPaced(cmd: ByteArray, what: String?): Boolean = port.sendPaced(what ?: "command for ${f.name}", cmd)

        override fun linkEpoch(): Long = port.linkEpoch()

        override fun sendData(epoch: Long, chunk: ByteArray): Boolean = port.sendData(epoch, chunk)

        override fun dataInFlight(epoch: Long): Int = port.dataInFlight(epoch)

        override fun traceUpload(on: Boolean) = port.traceUpload(on)

        override fun request(cmd: ByteArray): Map<Int, List<Any>> {
            check(onLinkThread.get() != true) { "request() would block the watch link; use submit()" }
            return port.request(cmd)
        }

        override fun submit(task: () -> Unit) {
            val s = session
            port.submit {
                if (s != session || !port.connected) {
                    log("Dropped a ${f.name} task: the watch session ended")
                } else {
                    val outer = onLinkThread.get()
                    onLinkThread.set(false)
                    try {
                        guard("${f.name} task", task)
                    } finally {
                        onLinkThread.set(outer)
                    }
                }
            }
        }

        override fun after(ms: Long, task: () -> Unit): () -> Unit = port.after(ms) { linkThread("${f.name} timer") { task() } }

        override fun log(message: String) = this@Features.log(message)

        private fun registering() = check(installing) { "${f.name}: register commands and routes in install()" }
    }

    private companion object {
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        const val BUSY = "Another watch operation is in progress"

        /** Segment kinds compared left to right: of two routes matching one path, the first difference decides. */
        val SPECIFIC_FIRST = Comparator<Route> { a, b ->
            a.ranks.zip(b.ranks).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: a.ranks.size.compareTo(b.ranks.size)
        }

        /** /api/status keys NotesService writes itself. */
        val CORE_STATUS = setOf("watch", "sync", "notes_count", "watch_title_limit", "silent_notes_supported", "silent_notes_status", "connection")
    }
}

/**
 * The app's features in connect-hook order: the device first so the watch clock is set before
 * anything else, then health (profile, then file sync), its settings, live heart rate, data upload
 * (ahead of its two users, so a disconnect ends an upload before they hear of it) and
 * notifications, then the rest. NotesService installs this list and FeaturesTest installs it on the
 * host, so a name, command or route collision fails the tests.
 */
fun appFeatures(health: HealthFeature, notifications: NotificationsFeature.ListenerSettings, requestLocation: () -> Boolean): List<WatchFeature> {
    val uploads = DataUpload()
    return listOf(
        DeviceFeature(),
        health,
        HealthSettingsFeature(),
        LiveHeartRateFeature(),
        uploads,
        NotificationsFeature(notifications, uploads = uploads),
        WeatherFeature(requestLocation),
        MusicFeature(),
        WatchFacesFeature(uploads = uploads),
        AlarmsFeature(),
        ContactsFeature(),
        QuickRepliesFeature(),
    )
}
