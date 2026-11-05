package local.hyprwatch

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Agents on Wi-Fi: pairing, agent tokens and the LAN listener's rules, Android-free so host tests check them.
 * AgentLan.kt holds the Android side (TLS, the socket, Wi-Fi, mDNS). The PC side is plugin/server.
 */

/** The pairing code and the proofs both sides derive from it; the vectors in tests/agent-pairing-vectors.json pin them. */
object Pairing {
    /** Crockford base32: no I, L, O or U, so a misread letter maps back (see [normalise]). */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val WINDOW_MS = 120_000L
    const val ATTEMPTS = 5
    private const val ITERATIONS = 200_000
    private val SALT = "hypr-watch pair v1".toByteArray(Charsets.US_ASCII)

    fun newCode(random: SecureRandom): String = String(CharArray(8) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** `K7Q2M9XA` as the phone shows it: `K7Q2-M9XA`. */
    fun display(code: String): String = code.substring(0, 4) + "-" + code.substring(4)

    /** The code as typed, as both sides use it; null unless it is 8 symbols of [ALPHABET]. */
    fun normalise(typed: String): String? {
        val code = typed.uppercase().replace(" ", "").replace("-", "").replace('O', '0').replace('I', '1').replace('L', '1')
        return code.takeIf { it.length == 8 && it.all { c -> c in ALPHABET } }
    }

    /**
     * K binds the code to [spki], the SHA-256 of the phone certificate's SubjectPublicKeyInfo: a proof made
     * over another certificate (a man in the middle's) never checks out. 200 000 iterations make each guess
     * at a captured proof cost enough that 2^40 codes can't be tried within the window.
     */
    fun key(code: String, spki: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), SALT + spki, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun clientProof(k: ByteArray, spki: ByteArray, nonce: ByteArray): ByteArray = hmac(k, "client".toByteArray(Charsets.US_ASCII) + spki + nonce)

    fun phoneProof(k: ByteArray, spki: ByteArray, nonce: ByteArray, token: String): ByteArray =
        hmac(k, "phone".toByteArray(Charsets.US_ASCII) + spki + nonce + token.toByteArray(Charsets.US_ASCII))

    private fun hmac(k: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(k, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Base64url without padding, as the client sends it; null for anything else. */
    fun unb64(text: String): ByteArray? =
        if (!text.matches(Regex("[A-Za-z0-9_-]*"))) null else try {
            Base64.getUrlDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * The one pairing window "Connect an agent" opens: a code for [Pairing.WINDOW_MS] and [Pairing.ATTEMPTS]
 * tries in all, from any address. It closes on success, on its last wrong try, on expiry or from the phone.
 * [onChange] runs after it opens or closes (mDNS shows `pair=1` only while it is open), outside its lock.
 * [now] is a monotonic clock, so a wall clock change can't stretch a window; [wall] only dates what the phone shows.
 */
class PairingWindow(private val now: () -> Long, private val wall: () -> Long, private val random: SecureRandom, private val onChange: () -> Unit) {
    private class Open(val code: String, val expiresAt: Long, var attemptsLeft: Int)

    private var open: Open? = null

    /** How the last window ended, for the phone's sheet: `{result, name?, at}`. */
    private var ended: JSONObject? = null

    /** One pairing request at a time: each costs a PBKDF2 run. */
    private val busy = AtomicBoolean()

    /** `{code, expires_at, attempts_left}` for a new window; replaces one already open. */
    fun open(): JSONObject {
        val json = synchronized(this) {
            open = Open(Pairing.newCode(random), now() + Pairing.WINDOW_MS, Pairing.ATTEMPTS)
            ended = null
            json()!!
        }
        onChange()
        return json
    }

    /** Closes the window; [result] says why, for the sheet. */
    fun close(result: String = "closed", name: String? = null) {
        val changed = synchronized(this) { closeLocked(result, name) }
        if (changed) onChange()
    }

    /** Closes the window once its time is up; the listener calls this at the expiry. */
    fun expire() {
        val changed = synchronized(this) { expireLocked() }
        if (changed) onChange()
    }

    fun isOpen(): Boolean = synchronized(this) { open != null && now() < open!!.expiresAt }

    /** The open window for the phone, or null. Read only on the phone's loopback API. */
    @Synchronized
    fun json(): JSONObject? {
        val w = open?.takeIf { now() < it.expiresAt } ?: return null
        return JSONObject().put("code", Pairing.display(w.code)).put("expires_at", wall() + (w.expiresAt - now())).put("attempts_left", w.attemptsLeft)
    }

    @Synchronized
    fun ended(): JSONObject? = ended

    /**
     * `POST /pair`: checks the client's proof against [spki], the phone's own, and on success [create]s the
     * agent (its name; returns agent id and token). Returns the status and the JSON answer. Every request
     * processed uses up a try, a malformed one too; one arriving while another is checked gets 409.
     */
    fun pair(body: ByteArray, spki: ByteArray, phone: JSONObject, create: (name: String) -> Pair<String, String>): Pair<Int, JSONObject> {
        var expired = false
        val window = synchronized(this) {
            expired = expireLocked()
            val w = open
            when {
                w == null -> null
                !busy.compareAndSet(false, true) -> return busyAnswer()
                else -> w.also { it.attemptsLeft-- }
            }
        }
        if (expired) onChange()
        if (window == null) return notOpen()
        try {
            val request = parse(body)
            val k = request?.let { Pairing.key(window.code, spki) }
            val matched = k != null && MessageDigest.isEqual(Pairing.clientProof(k, spki, request.nonce), request.proof)
            var answer: Pair<Int, JSONObject>? = null
            val closed = synchronized(this) {
                // Closed from the phone or expired while the key was computed: nothing pairs.
                if (open !== window || now() >= window.expiresAt) {
                    answer = notOpen()
                    expireLocked()
                } else if (matched) {
                    val (id, token) = create(request!!.name)
                    answer = 200 to JSONObject(phone.toString()).put("v", 1).put("agent_id", id).put("token", token)
                        .put("proof", Pairing.b64(Pairing.phoneProof(k!!, spki, request.nonce, token)))
                    closeLocked("paired", request.name)
                } else {
                    val left = window.attemptsLeft
                    val why = if (request == null) "That pairing request wasn't valid." else "That code didn't match."
                    val tries = when (left) {
                        0 -> "No tries left. Get a new code on the phone."
                        1 -> "1 try left."
                        else -> "$left tries left."
                    }
                    answer = (if (request == null) 400 else 403) to error("$why $tries")
                    left == 0 && closeLocked("no_tries", null)
                }
            }
            if (closed) onChange()
            return answer!!
        } finally {
            busy.set(false)
        }
    }

    private class Request(val name: String, val nonce: ByteArray, val proof: ByteArray)

    private fun parse(body: ByteArray): Request? =
        try {
            val json = JSONObject(String(body, Charsets.UTF_8))
            val name = json.optString("name").trim()
            val nonce = Pairing.unb64(json.optString("nonce"))
            val proof = Pairing.unb64(json.optString("proof"))
            if (json.opt("v") != 1 || name.isEmpty() || name.codePointCount(0, name.length) > 64 || nonce?.size != 16 || proof?.size != 32) null
            else Request(name, nonce, proof)
        } catch (e: JSONException) {
            null
        }

    private fun busyAnswer() = 409 to error("Another pairing attempt is in progress. Try again in a moment.")

    private fun notOpen() = 404 to error("No pairing code is open on the phone. Tap Connect an agent in Hypr Watch.")

    private fun error(text: String) = JSONObject().put("error", text)

    private fun expireLocked(): Boolean = open?.let { now() >= it.expiresAt && closeLocked("expired", null) } ?: false

    private fun closeLocked(result: String, name: String?): Boolean {
        if (open == null) return false
        open = null
        ended = JSONObject().put("result", result).put("at", wall()).also { if (name != null) it.put("name", name) }
        return true
    }
}

/**
 * Paired agents in [file] (`files/agents.json`, outside backups), and whether the LAN listener is on.
 * Tokens are kept only as SHA-256; a revoked agent's next request gets 401.
 */
class AgentStore(private val file: File, private val now: () -> Long, private val random: SecureRandom, log: (String) -> Unit = {}) {
    private class Agent(val id: String, val name: String, val createdAt: Long, var lastSeenAt: Long, var lastIp: String, val tokenHash: ByteArray)

    private var enabled = false
    private val agents = ArrayList<Agent>()

    /** Random per install, so a reinstalled phone is a new phone to the PC (its TLS key is new too). */
    val phoneId: String

    init {
        var id: String? = null
        if (file.exists()) {
            try {
                val json = JSONObject(String(Files.readAllBytes(file.toPath()), Charsets.UTF_8))
                val list = json.optJSONArray("agents") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val a = list.getJSONObject(i)
                    agents.add(Agent(a.getString("id"), a.getString("name"), a.getLong("created_at"), a.optLong("last_seen_at"), a.optString("last_ip"), unhex(a.getString("token_sha256_hex"))))
                }
                enabled = json.optBoolean("enabled")
                id = json.optString("phone_id").takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                // The service, and the watch with it, must still start: kept for a look, and agents pair again.
                agents.clear()
                Files.move(file.toPath(), File(file.path + ".bad").toPath(), StandardCopyOption.REPLACE_EXISTING)
                log("agents.json was unreadable (${e.javaClass.simpleName}); moved to agents.json.bad, Wi-Fi access is off")
            }
        }
        phoneId = id ?: Pairing.b64(bytes(16))
        if (id == null) save()
    }

    @Synchronized
    fun enabled(): Boolean = enabled

    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        save()
    }

    /** A new agent named [name]: its id and its token, which only the client keeps. */
    @Synchronized
    fun create(name: String): Pair<String, String> {
        val id = Pairing.b64(bytes(16))
        val token = TOKEN_PREFIX + Pairing.b64(bytes(32))
        agents.add(Agent(id, name, now(), 0, "", Pairing.sha256(token.toByteArray(Charsets.US_ASCII))))
        save()
        return id to token
    }

    /** The agent [authorization] (`Bearer hwa1_…`) belongs to, or null; notes when and from where it was last seen. */
    @Synchronized
    fun authenticate(authorization: String?, ip: String): String? {
        if (authorization == null || !authorization.startsWith("Bearer $TOKEN_PREFIX")) return null
        val hash = Pairing.sha256(authorization.substring(7).toByteArray(Charsets.UTF_8))
        var found: Agent? = null
        for (a in agents) if (MessageDigest.isEqual(a.tokenHash, hash)) found = a
        val agent = found ?: return null
        val t = now()
        // A write per request would wear the flash for nothing; a minute is the precision the phone shows.
        if (t - agent.lastSeenAt >= 60_000 || agent.lastIp != ip) {
            agent.lastSeenAt = t
            agent.lastIp = ip
            save()
        }
        return agent.id
    }

    @Synchronized
    fun revoke(id: String): Boolean {
        if (!agents.removeIf { it.id == id }) return false
        save()
        return true
    }

    @Synchronized
    fun list(): JSONArray {
        val out = JSONArray()
        for (a in agents) {
            out.put(
                JSONObject().put("id", a.id).put("name", a.name).put("created_at", a.createdAt)
                    .put("last_seen_at", if (a.lastSeenAt > 0) a.lastSeenAt else JSONObject.NULL)
                    .put("last_ip", a.lastIp.ifEmpty { JSONObject.NULL }),
            )
        }
        return out
    }

    private fun bytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** Written beside the file, synced, then renamed over it, so a crash leaves the old or the new list. */
    private fun save() {
        val json = JSONObject().put("enabled", enabled).put("phone_id", phoneId)
        val list = JSONArray()
        for (a in agents) {
            list.put(
                JSONObject().put("id", a.id).put("name", a.name).put("created_at", a.createdAt).put("last_seen_at", a.lastSeenAt)
                    .put("last_ip", a.lastIp).put("token_sha256_hex", Pairing.hex(a.tokenHash)),
            )
        }
        json.put("agents", list)
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val TOKEN_PREFIX = "hwa1_"

        private fun unhex(s: String): ByteArray {
            require(s.length == 64) { "Token hash is not 32 bytes" }
            return ByteArray(32) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        /** For the boot receiver: whether the listener was on, without loading the agents. */
        fun enabled(file: File): Boolean =
            try {
                file.exists() && JSONObject(String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)).optBoolean("enabled")
            } catch (e: Exception) {
                false
            }
    }
}

/** Failed sign-ins per remote address; past [LIMIT] in a minute its sockets are closed unanswered until the minute ends. */
class FailedAuth(private val now: () -> Long) {
    private val counts = HashMap<String, LongArray>()

    @Synchronized
    fun blocked(ip: String): Boolean {
        val c = counts[ip] ?: return false
        return now() - c[0] < 60_000 && c[1] >= LIMIT
    }

    @Synchronized
    fun failed(ip: String) {
        val t = now()
        counts.values.removeIf { t - it[0] >= 60_000 }
        val c = counts.getOrPut(ip) { longArrayOf(t, 0) }
        c[1]++
    }

    companion object {
        const val LIMIT = 20L
    }
}

/** Open agent sockets per remote address, at most [limit] each, so one host can't hold every worker. */
class PerAddress(private val limit: Int) {
    private val open = HashMap<String, Int>()

    @Synchronized
    fun acquire(ip: String): Boolean {
        val n = open[ip] ?: 0
        if (n >= limit) return false
        open[ip] = n + 1
        return true
    }

    @Synchronized
    fun release(ip: String) {
        val n = (open[ip] ?: return) - 1
        if (n > 0) open[ip] = n else open.remove(ip)
    }
}

/** Whether a peer is on the Wi-Fi link itself: inside one of its prefixes, or IPv6 link-local. Routed peers are refused. */
object OnLink {
    fun peer(remote: InetAddress, prefixes: List<Pair<InetAddress, Int>>): Boolean =
        (remote is Inet6Address && remote.isLinkLocalAddress) || prefixes.any { (address, length) -> within(remote, address, length) }

    fun within(remote: InetAddress, network: InetAddress, length: Int): Boolean {
        val a = remote.address
        val b = network.address
        if (a.size != b.size || length < 0 || length > a.size * 8) return false
        for (bit in 0 until length) {
            val mask = 0x80 ushr (bit % 8)
            if ((a[bit / 8].toInt() and mask) != (b[bit / 8].toInt() and mask)) return false
        }
        return true
    }
}

/**
 * Requests on the LAN listener. Only paired agents' tokens, never the master token, and only the routes the
 * MCP tools call ([ROUTES]): no raw health files, no export, no probe, no Android permission screens, and none
 * of the agent management routes, so an agent can't pair others or revoke anyone. Browsers are refused outright.
 */
class AgentApi(private val store: AgentStore, private val window: PairingWindow, private val failures: FailedAuth, private val phoneName: String) {
    /**
     * Answers one request from [ip] over TLS with the phone's [spki]; [route] serves allowed routes as the
     * loopback API does, after [authenticated] runs. Throws IOException to close the socket unanswered,
     * also for a body nested deeply enough to overflow the JSON parser's stack.
     */
    fun handle(
        head: Http.Head,
        input: InputStream,
        out: OutputStream,
        ip: String,
        spki: ByteArray,
        authenticated: () -> Unit = {},
        route: (method: String, path: String, body: JSONObject, raw: ByteArray) -> JSONObject,
    ) {
        try {
            // Any web page could make a browser on this network send requests here; agents never send Origin.
            if (head.headers.containsKey("origin")) return Http.json(out, 403, "{\"error\":\"Browsers can't use this connection\"}")
            if (head.path == "/pair" && head.method == "POST") {
                if (!window.isOpen()) return Http.json(out, 404, JSONObject().put("error", "No pairing code is open on the phone. Tap Connect an agent in Hypr Watch.").toString())
                // Unauthenticated: a real request is about 200 bytes, so nothing larger reaches the parser.
                val declared = head.headers["content-length"]?.toLongOrNull()
                if (declared != null && declared > MAX_PAIR_BODY) throw Http.Failure(413, "Request body is larger than $MAX_PAIR_BODY bytes")
                val (status, answer) = window.pair(Http.body(head, input), spki, JSONObject().put("phone_id", store.phoneId).put("phone_name", phoneName)) { name -> store.create(name) }
                return Http.json(out, status, answer.toString())
            }
            if (failures.blocked(ip)) throw IOException("Too many failed sign-ins from $ip")
            if (store.authenticate(head.headers["authorization"], ip) == null) {
                failures.failed(ip)
                return Http.json(out, 401, "{\"error\":\"This computer isn't paired with the phone, or was revoked. Pair it again.\"}")
            }
            if (!allowed(head.method, head.path)) return Http.json(out, 404, "{\"error\":\"Not available over Wi-Fi\"}")
            authenticated()
            Http.answer(head, input, out) { body, raw -> route(head.method, head.path, body, raw) }
        } catch (e: StackOverflowError) {
            throw IOException("Request body nested too deeply")
        }
    }

    companion object {
        const val MAX_PAIR_BODY = 1024L

        /** `METHOD path`; `*` is one path segment. The query is checked by the route itself. */
        val ROUTES = listOf(
            "GET /api/status",
            "GET /api/notes", "POST /api/notes", "PATCH /api/notes/*", "DELETE /api/notes/*",
            "POST /api/watch/connect", "POST /api/watch/disconnect", "GET /api/watch/reminders", "POST /api/watch/sync",
            "GET /api/device", "POST /api/device/find-watch", "POST /api/device/find-phone/stop", "POST /api/device/time-sync",
            "GET /api/weather", "PUT /api/weather/settings", "POST /api/weather/refresh",
            "GET /api/music", "PUT /api/music/settings",
            "GET /api/alarms", "POST /api/alarms", "POST /api/alarms/refresh", "PUT /api/alarms/*", "DELETE /api/alarms/*", "GET /api/alarms/sleep-mode",
            "GET /api/watch-settings", "PUT /api/watch-settings",
            "GET /api/heart-rate/live", "POST /api/heart-rate/live",
            "GET /api/watch-faces", "POST /api/watch-faces/*/activate", "DELETE /api/watch-faces/*", "POST /api/watch-faces/upload",
            "GET /api/contacts", "PUT /api/contacts", "GET /api/quick-replies", "PUT /api/quick-replies",
            "POST /api/health/sync", "GET /api/health/days", "GET /api/health/series", "GET /api/health/sleep", "GET /api/health/samples",
            "GET /api/health/workouts", "GET /api/health/profile", "PUT /api/health/profile",
            "GET /api/notifications", "PUT /api/notifications",
        ).map { it.split(" ").let { (m, p) -> m to p.split("/") } }

        fun allowed(method: String, target: String): Boolean {
            val parts = target.substringBefore('?').split("/")
            return ROUTES.any { (m, p) -> m == method && p.size == parts.size && p.indices.all { p[it] == parts[it] || (p[it] == "*" && parts[it].isNotEmpty()) } }
        }
    }
}
