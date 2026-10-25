package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Health files (8/1, 8/2, 8/3 and channel 5), the user profile (8/0) and the /api/health routes.
 * [reads] serves HealthApi's query routes; [ingest] is HealthIngest's status; [connect], when set,
 * adds the Health Connect export's routes and status.
 */
class HealthFeature(
    private val store: Store,
    private val reads: (path: String, query: Map<String, String>) -> JSONObject?,
    private val ingest: () -> JSONObject,
    private val connect: HealthConnectExport? = null,
    private val today: () -> LocalDate = LocalDate::now,
) : WatchFeature {
    interface Store : HealthCapture.Files {
        fun list(): JSONArray

        fun count(): Long

        fun file(idHex: String, crc: Long?): JSONObject?

        /** As validated by [HealthCapture.profile]; null until the user enters one. */
        fun profile(): JSONObject?

        fun profile(p: JSONObject)
    }

    override val name = "health"

    lateinit var capture: HealthCapture
        private set
    private lateinit var host: FeatureHost
    private val profileLock = Any()

    override fun install(host: FeatureHost) {
        this.host = host
        capture = HealthCapture(
            store,
            { cmd, paced -> if (paced) host.sendPaced(cmd, "health command") else host.send(cmd, "health command") },
            WatchTimer(host::after),
            host::log,
        )
        // 8/1 and 8/2 answer fire-and-forget sends, so they arrive as watch-initiated commands.
        for (sub in 1..2) {
            host.on(8, sub) { cmd -> capture.onList(sub, Proto.blob(Proto.message(cmd, 10), 2)) }
        }
        host.onChunk { capture.onChunk(it) }
        host.onAcked { capture.onAcked() }
        host.route("POST", "/api/health/sync") {
            require(host.connected) { "Connect the watch first" }
            val started = capture.start()
            JSONObject().put("started", started).put("health", status())
        }
        host.route("GET", "/api/health/files") { JSONObject().put("files", store.list()) }
        host.route("GET", "/api/health/files/{file*}") { r ->
            val m = Regex("([0-9a-f]{14})(?:/([0-9a-f]{8,12}))?").matchEntire(r.params.getValue("file")) ?: throw IllegalArgumentException("Invalid health file id")
            val crc = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong(16)
            store.file(m.groupValues[1], crc) ?: throw IllegalArgumentException("Health file not found")
        }
        host.route("GET", "/api/health/profile") {
            val p = store.profile()
            JSONObject().put("configured", p != null).put("profile", p ?: JSONObject.NULL)
        }
        host.route("PUT", "/api/health/profile") { r ->
            val p = HealthCapture.profile(r.body, today())
            JSONObject().put("configured", true).put("profile", p).put("sent_to_watch", saveProfile(p))
        }
        connect?.let { c ->
            host.route("GET", "/api/health/connect") { c.status() }
            host.route("PUT", "/api/health/connect") { r ->
                val on = r.body.opt("enabled") as? Boolean ?: throw IllegalArgumentException("'enabled' must be true or false")
                c.setEnabled(on)
            }
            host.route("POST", "/api/health/connect/export") { r ->
                val all = r.body.opt("all") ?: false
                require(all is Boolean) { "'all' must be true or false" }
                JSONObject().put("queued", true).put("health_connect", c.exportNow(all))
            }
            host.route("POST", "/api/health/connect/permission") { JSONObject().put("requested", c.requestPermissions()) }
        }
        for (path in READS) {
            host.route("GET", path) { r -> reads(path, r.query) ?: throw IllegalArgumentException("Unknown API operation") }
        }
    }

    /** The stored profile if the user entered one, then a health sync. */
    override fun onConnected() {
        synchronized(profileLock) {
            store.profile()?.let { host.send(HealthCapture.userInfo(it, today()), "profile") }
        }
        capture.start()
    }

    override fun onDisconnected() {
        capture.stop("Watch disconnected during health sync")
    }

    override fun status(): JSONObject {
        val s = capture.status().put("files_stored", store.count()).put("ingest", ingest())
        return if (connect == null) s else s.put("health_connect", connect.status())
    }

    /** Stores [profile] and sends it; false when it waits for the next connect. */
    fun saveProfile(profile: JSONObject): Boolean =
        // One lock with [onConnected], so the watch always ends up with the profile stored last.
        synchronized(profileLock) {
            store.profile(profile)
            host.connected && host.send(HealthCapture.userInfo(profile, today()), "profile")
        }

    /** Rate-limited sync for automatic triggers such as screen unlock. */
    fun syncIfDue(intervalMs: Long) {
        if (host.connected) capture.startIfDue(intervalMs)
    }

    private companion object {
        val READS = listOf("days", "series", "sleep", "samples", "workouts", "parse-report").map { "/api/health/$it" }
    }
}
