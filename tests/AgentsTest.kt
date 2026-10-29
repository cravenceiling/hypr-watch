package local.hyprwatch

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom

/** Pairing math against the PC's vectors, the pairing window, agent tokens and the LAN listener's rules. */
object AgentsTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private var clock = 1_700_000_000_000L
    private val dir: File = Files.createTempDirectory("agents-test").toFile()

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            vectors(File(args[0]))
            codes()
            store()
            corrupt()
            window()
            lan()
            limits()
        } finally {
            dir.deleteRecursively()
        }
        println("Agents: $assertions assertions passed")
    }

    /** Both sides must derive the same K and proofs, or no PC ever pairs. */
    private fun vectors(file: File) {
        val cases = JSONObject(file.readText()).getJSONArray("cases")
        check(cases.length() > 0, "Vectors present")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val code = Pairing.normalise(c.getString("code"))
            check(code == c.getString("normalised_code"), "Vector $i normalises to ${c.getString("normalised_code")}: got $code")
            val spki = unhex(c.getString("spki_hex"))
            val nonce = unhex(c.getString("nonce_hex"))
            val k = Pairing.key(code!!, spki)
            check(Pairing.hex(k) == c.getString("k_hex"), "Vector $i K")
            check(Pairing.b64(Pairing.clientProof(k, spki, nonce)) == c.getString("client_proof"), "Vector $i client proof")
            check(Pairing.b64(Pairing.phoneProof(k, spki, nonce, c.getString("token"))) == c.getString("phone_proof"), "Vector $i phone proof")
        }
    }

    private fun codes() {
        val random = SecureRandom()
        repeat(200) {
            val code = Pairing.newCode(random)
            check(code.length == 8 && Pairing.normalise(code) == code && Pairing.normalise(Pairing.display(code).lowercase()) == code, "New code $code round-trips")
        }
        for (bad in listOf("", "K7Q2-M9X", "K7Q2-M9XAB", "K7Q2-M9XU", "K7Q2_M9XA", "K7Q2\tM9XA")) check(Pairing.normalise(bad) == null, "Refuses '$bad'")
        check(Pairing.unb64("a+b/") == null && Pairing.unb64("AAA=") == null && Pairing.unb64("AAAA")?.size == 3, "Only unpadded base64url decodes")
    }

    private fun store() {
        val file = File(dir, "store.json")
        val store = AgentStore(file, { clock }, SecureRandom())
        check(!store.enabled() && !AgentStore.enabled(file) && store.list().length() == 0, "A new store is off and empty")
        val phoneId = store.phoneId
        check(Pairing.unb64(phoneId)?.size == 16, "Phone id is 16 bytes")
        store.setEnabled(true)
        check(AgentStore.enabled(file), "Enabled is on disk")
        val (id, token) = store.create("laptop")
        check(token.startsWith("hwa1_") && Pairing.unb64(token.substring(5))?.size == 32, "Token shape")
        check(!file.readText().contains(token) && file.readText().contains(Pairing.hex(Pairing.sha256(token.toByteArray()))), "Only the token's hash is stored")
        check(store.authenticate("Bearer $token", "10.0.0.5") == id, "The token authenticates")
        for (bad in listOf(null, "", token, "Bearer ", "Bearer hwa1_", "Bearer ${token}x", "Bearer " + token.dropLast(1), "bearer $token")) {
            check(store.authenticate(bad, "10.0.0.5") == null, "Refuses '$bad'")
        }
        val seen = store.list().getJSONObject(0)
        check(seen.getLong("last_seen_at") == clock && seen.getString("last_ip") == "10.0.0.5" && seen.getString("name") == "laptop", "Last seen and address")

        // Written at most once a minute from the same address; another address is written at once.
        val first = clock
        clock += 30_000
        store.authenticate("Bearer $token", "10.0.0.5")
        check(AgentStore(file, { clock }, SecureRandom()).list().getJSONObject(0).getLong("last_seen_at") == first, "Not rewritten within a minute")
        store.authenticate("Bearer $token", "10.0.0.6")
        check(AgentStore(file, { clock }, SecureRandom()).list().getJSONObject(0).getString("last_ip") == "10.0.0.6", "A new address is written")
        clock += 60_000
        store.authenticate("Bearer $token", "10.0.0.6")
        check(AgentStore(file, { clock }, SecureRandom()).list().getJSONObject(0).getLong("last_seen_at") == clock, "Rewritten after a minute")

        val (other, otherToken) = store.create("desktop")
        val reloaded = AgentStore(file, { clock }, SecureRandom())
        check(reloaded.phoneId == phoneId && reloaded.enabled() && reloaded.list().length() == 2, "Reload keeps the phone id, the switch and the agents")
        check(reloaded.authenticate("Bearer $otherToken", "10.0.0.7") == other, "A reloaded store authenticates")
        check(reloaded.revoke(id) && !reloaded.revoke(id), "Revoke once")
        check(reloaded.authenticate("Bearer $token", "10.0.0.5") == null && reloaded.authenticate("Bearer $otherToken", "10.0.0.7") == other, "Revoked token refused; the other still works")
        check(AgentStore(file, { clock }, SecureRandom()).list().length() == 1, "Revoke is on disk")
        check(!File(file.path + ".tmp").exists(), "No temporary file left")
    }

    /** A damaged agents.json is set aside; the store starts empty, off, and writable. */
    private fun corrupt() {
        for ((i, text) in listOf("{not json", "{\"enabled\":true,\"phone_id\":\"P\",\"agents\":[{\"id\":\"a\"}]}", "{\"enabled\":true,\"agents\":[{\"id\":\"a\",\"name\":\"n\",\"created_at\":1,\"token_sha256_hex\":\"ab\"}]}").withIndex()) {
            val file = File(dir, "corrupt$i.json")
            file.writeText(text)
            val logged = ArrayList<String>()
            val store = AgentStore(file, { clock }, SecureRandom()) { logged.add(it) }
            check(!store.enabled() && store.list().length() == 0 && logged.size == 1, "Damaged file $i: off and empty, logged once")
            check(File(file.path + ".bad").readText() == text && AgentStore(file, { clock }, SecureRandom()).phoneId == store.phoneId, "Damaged file $i: kept aside, a fresh file written")
            check(!AgentStore.enabled(file), "Damaged file $i: the boot receiver sees it off")
        }
    }

    private fun limits() {
        val per = PerAddress(2)
        check(per.acquire("a") && per.acquire("a") && !per.acquire("a") && per.acquire("b"), "Two sockets per address")
        per.release("a")
        check(per.acquire("a") && !per.acquire("a"), "A closed socket frees its place")
        per.release("a"); per.release("a"); per.release("a")
        check(per.acquire("a") && per.acquire("a"), "Releasing more than acquired never goes negative")

        fun ip(s: String) = java.net.InetAddress.getByName(s)
        val wifi = listOf(ip("192.168.1.23") to 24, ip("2001:db8:1:2::17") to 64)
        check(OnLink.peer(ip("192.168.1.40"), wifi) && OnLink.peer(ip("192.168.1.255"), wifi), "Same /24")
        check(!OnLink.peer(ip("192.168.2.40"), wifi) && !OnLink.peer(ip("10.0.0.1"), wifi) && !OnLink.peer(ip("8.8.8.8"), wifi), "Routed IPv4 refused")
        check(OnLink.peer(ip("2001:db8:1:2:abcd::1"), wifi) && !OnLink.peer(ip("2001:db8:1:3::1"), wifi), "IPv6 prefix")
        check(OnLink.peer(ip("fe80::1"), emptyList()), "IPv6 link-local is on-link")
        check(!OnLink.peer(ip("192.168.1.40"), emptyList()) && !OnLink.peer(ip("::ffff:10.0.0.1"), wifi), "No prefixes, nothing on-link")
        check(OnLink.within(ip("172.16.5.9"), ip("172.16.0.1"), 12) && !OnLink.within(ip("172.32.0.1"), ip("172.16.0.1"), 12), "Prefix not on a byte boundary")
        check(OnLink.within(ip("1.2.3.4"), ip("9.9.9.9"), 0) && !OnLink.within(ip("1.2.3.4"), ip("1.2.3.4"), 33), "Edge lengths")
    }

    private class Client(val nonce: ByteArray, val proof: String, val body: ByteArray)

    private fun client(code: String, spki: ByteArray, name: String = "laptop"): Client {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val k = Pairing.key(Pairing.normalise(code)!!, spki)
        val proof = Pairing.b64(Pairing.clientProof(k, spki, nonce))
        val body = JSONObject().put("v", 1).put("name", name).put("nonce", Pairing.b64(nonce)).put("proof", proof).toString().toByteArray()
        return Client(nonce, proof, body)
    }

    private fun window() {
        val spki = Pairing.sha256("phone certificate".toByteArray())
        val phone = JSONObject().put("phone_id", "P").put("phone_name", "Pixel")
        val store = AgentStore(File(dir, "window.json"), { clock }, SecureRandom())
        var changes = 0
        val window = PairingWindow({ clock }, { clock }, SecureRandom()) { changes++ }
        val create = { name: String -> store.create(name) }

        check(window.pair(client("K7Q2-M9XA", spki).body, spki, phone, create).first == 404 && changes == 0, "No window: 404")
        val opened = window.open()
        check(changes == 1 && opened.getInt("attempts_left") == 5 && opened.getLong("expires_at") == clock + 120_000, "Open: 5 tries for 2 minutes")
        val code = opened.getString("code")
        check(code.matches(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}")), "Shown as XXXX-XXXX")

        // A proof over another certificate (a man in the middle's) fails like a wrong code.
        val mitm = client(code, Pairing.sha256("attacker".toByteArray()))
        val wrong = window.pair(mitm.body, spki, phone, create)
        check(wrong.first == 403 && wrong.second.getString("error") == "That code didn't match. 4 tries left.", "Other certificate: ${wrong.second}")
        val malformed = window.pair("{\"v\":1}".toByteArray(), spki, phone, create)
        check(malformed.first == 400 && malformed.second.getString("error").endsWith("3 tries left."), "Malformed uses a try: ${malformed.second}")
        check(window.pair("not json".toByteArray(), spki, phone, create).first == 400, "Not JSON uses a try")
        check(window.json()!!.getInt("attempts_left") == 2, "Two left")

        // While one request is checked, another gets 409 and uses no try.
        var nested: Pair<Int, JSONObject>? = null
        val good = client(code.lowercase().replace("-", " "), spki, "  laptop  ")
        val ok = window.pair(good.body, spki, phone) { name ->
            nested = window.pair(good.body, spki, phone, create)
            store.create(name)
        }
        check(nested!!.first == 409, "Concurrent request: 409")
        check(ok.first == 200 && changes == 2 && !window.isOpen() && window.json() == null, "Paired; the window closes: ${ok.second}")
        val answer = ok.second
        val token = answer.getString("token")
        check(answer.getInt("v") == 1 && answer.getString("phone_id") == "P" && answer.getString("phone_name") == "Pixel", "Phone fields")
        check(store.authenticate("Bearer $token", "10.0.0.9") == answer.getString("agent_id"), "The returned token is the stored agent's")
        val k = Pairing.key(Pairing.normalise(code)!!, spki)
        check(answer.getString("proof") == Pairing.b64(Pairing.phoneProof(k, spki, good.nonce, token)), "Phone proof the client can check")
        check(window.ended()!!.getString("result") == "paired" && window.ended()!!.getString("name") == "laptop", "Ended: paired with the trimmed name")
        check(window.pair(good.body, spki, phone, create).first == 404, "A replay after pairing: 404")

        // Five wrong tries close the window.
        val second = window.open().getString("code")
        check(changes == 3 && window.ended() == null, "Reopened")
        val other = client(if (second == "00000000") "11111111" else "0000-0000", spki)
        for (left in 4 downTo 1) check(window.pair(other.body, spki, phone, create).second.getString("error").endsWith(if (left == 1) "1 try left." else "$left tries left."), "Wrong: $left left")
        val last = window.pair(other.body, spki, phone, create)
        check(last.first == 403 && last.second.getString("error") == "That code didn't match. No tries left. Get a new code on the phone.", "Last try: ${last.second}")
        check(!window.isOpen() && changes == 4 && window.ended()!!.getString("result") == "no_tries", "Closed after the fifth")
        check(window.pair(client(second, spki).body, spki, phone, create).first == 404, "The right code after that: 404")

        // Expiry: 404 from the deadline on, and the change is reported once.
        val third = window.open().getString("code")
        clock += 119_999
        check(window.isOpen(), "Open until the deadline")
        clock += 1
        check(!window.isOpen() && window.json() == null, "Expired at 2:00")
        check(window.pair(client(third, spki).body, spki, phone, create).first == 404 && changes == 6 && window.ended()!!.getString("result") == "expired", "Expired: 404")
        window.expire()
        check(changes == 6, "Expiry is reported once")

        // The window runs on the monotonic clock; a wall clock change moves only what the phone shows.
        var monotonic = 5_000L
        val wallWindow = PairingWindow({ monotonic }, { clock }, SecureRandom()) {}
        wallWindow.open()
        clock -= 3_600_000
        monotonic += 30_000
        check(wallWindow.isOpen() && wallWindow.json()!!.getLong("expires_at") == clock + 90_000, "Expiry follows the monotonic clock, shown in wall time")
        monotonic += 90_000
        check(!wallWindow.isOpen(), "Expires after 2 minutes of monotonic time")
        clock += 3_600_000

        window.open()
        window.close()
        check(changes == 8 && window.ended()!!.getString("result") == "closed", "Closed from the phone")
        window.close()
        check(changes == 8, "Closing a closed window changes nothing")
        check(store.list().length() == 1, "Only the one success made an agent")
    }

    private fun lan() {
        val spki = Pairing.sha256("phone certificate".toByteArray())
        val store = AgentStore(File(dir, "lan.json"), { clock }, SecureRandom())
        val window = PairingWindow({ clock }, { clock }, SecureRandom()) {}
        val failures = FailedAuth { clock }
        val api = AgentApi(store, window, failures, "Pixel")
        val (_, token) = store.create("laptop")
        val master = Pairing.b64(ByteArray(32) { 7 })
        val routed = ArrayList<String>()

        fun exchange(request: String, ip: String = "10.0.0.5"): String {
            val input = ByteArrayInputStream(request.toByteArray(Charsets.ISO_8859_1))
            val out = ByteArrayOutputStream()
            Http.serve(input, out) { head ->
                api.handle(head, input, out, ip, spki) { method, path, _, _ ->
                    routed.add("$method $path")
                    JSONObject().put("ok", true)
                }
            }
            val response = out.toString(Charsets.UTF_8.name())
            return response.substringBefore("\r\n") + " " + response.substringAfter("\r\n\r\n")
        }

        fun get(method: String, path: String, auth: String? = "Bearer $token", extra: String = "") =
            exchange("$method $path HTTP/1.1\r\n" + (if (auth != null) "Authorization: $auth\r\n" else "") + extra + "Content-Length: 0\r\n\r\n")

        for (route in listOf(
            "GET /api/status", "GET /api/notes", "PATCH /api/notes/0b7c7d3e-1d1a-4a8e-9a43-3c0e1c9d2f10", "POST /api/watch/connect",
            "GET /api/health/days?start=2026-10-01&end=2026-10-29", "PUT /api/alarms/3", "PUT /api/alarms/sleep-mode", "POST /api/watch-faces/abc/activate",
            "GET /api/watch-faces?refresh=true", "POST /api/watch-faces/upload", "PUT /api/notifications", "PUT /api/health/profile",
        )) {
            val (m, p) = route.split(" ")
            check(get(m, p) == "HTTP/1.1 200 OK {\"ok\":true}" && routed.last() == "$m $p", "Allowed: $route")
        }
        val count = routed.size
        for (route in listOf(
            "GET /", "GET /app.js", "GET /api/export", "GET /api/health/files", "GET /api/health/files/12", "GET /api/health/files/12/34",
            "GET /api/health/parse-report", "POST /api/watch/probe-silent", "GET /api/health/connect", "PUT /api/health/connect",
            "POST /api/health/connect/export", "POST /api/health/connect/permission", "POST /api/notifications/access", "POST /api/weather/permission",
            "GET /api/agents", "PUT /api/agents/lan", "POST /api/agents/pairing", "DELETE /api/agents/abc", "GET /api/notes/0b7c7d3e-1d1a-4a8e-9a43-3c0e1c9d2f10",
            "DELETE /api/notes/", "PUT /api/alarms/3/x", "GET /api/status/", "POST /api/status", "GET /api/backup",
        )) {
            val (m, p) = route.split(" ")
            check(get(m, p) == "HTTP/1.1 404 Error {\"error\":\"Not available over Wi-Fi\"}", "Refused: $route")
        }
        check(routed.size == count, "No refused route reached the handler")

        // Browsers are refused even with a valid token; the master token is never an agent token.
        check(get("GET", "/api/status", extra = "Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403 "), "Origin: 403")
        check(get("POST", "/pair", null, "Origin: null\r\n").startsWith("HTTP/1.1 403 "), "Origin on /pair: 403")
        check(get("GET", "/api/status", "Bearer $master").startsWith("HTTP/1.1 401 "), "Master token: 401")
        check(get("GET", "/api/status", null).startsWith("HTTP/1.1 401 "), "No token: 401")
        check(routed.size == count, "Nothing refused was routed")

        // /pair exists only while a window is open.
        check(get("POST", "/pair", null).startsWith("HTTP/1.1 404 "), "/pair without a window: 404")
        val code = window.open().getString("code")
        val c = client(code, spki).body.toString(Charsets.UTF_8)
        val paired = exchange("POST /pair HTTP/1.1\r\nContent-Length: ${c.length}\r\n\r\n$c")
        check(paired.startsWith("HTTP/1.1 200 ") && JSONObject(paired.substringAfter(" ").substringAfter(" ").substringAfter(" ")).getString("token").startsWith("hwa1_"), "Pairs over the listener: $paired")
        check(get("GET", "/pair", null).startsWith("HTTP/1.1 401 "), "GET /pair is not a pairing route")

        // Unauthenticated bodies stay small: an oversized /pair is refused before it is read, using no try.
        window.open()
        val big = "{\"v\":1,\"name\":\"" + "x".repeat(2000) + "\"}"
        check(exchange("POST /pair HTTP/1.1\r\nContent-Length: ${big.length}\r\n\r\n$big") == "HTTP/1.1 413 Error {\"error\":\"Request body is larger than 1024 bytes\"}", "Oversized /pair: 413")
        check(window.json()!!.getInt("attempts_left") == 5, "An oversized body uses no try")
        val nested = "{\"a\":".repeat(150) + "1" + "}".repeat(150)
        check(exchange("POST /pair HTTP/1.1\r\nContent-Length: ${nested.length}\r\n\r\n$nested").startsWith("HTTP/1.1 400 "), "Nested /pair body: 400")
        check(window.json()!!.getInt("attempts_left") == 4, "A malformed body uses a try")
        window.close()

        // A body nested deeply enough to overflow a parser closes that request; no Error escapes the handler.
        // The host's org.json refuses deep nesting itself (400); Android's older fork recurses, so the overflow is forced too.
        val deep = "{\"a\":".repeat(16_000) + "1" + "}".repeat(16_000)
        var escaped: Throwable? = null
        var answer = ""
        val small = Thread(null, {
            try {
                answer = exchange("PUT /api/notifications HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: ${deep.length}\r\n\r\n$deep")
            } catch (t: Throwable) {
                escaped = t
            }
        }, "deep", 128 * 1024)
        small.start()
        small.join()
        check(answer.startsWith("HTTP/1.1 400 ") || escaped is IOException, "Deep JSON: answered 400 or closed, got $answer $escaped")
        var overflow: Throwable? = null
        try {
            val input = ByteArrayInputStream("PUT /api/notifications HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
            Http.serve(input, ByteArrayOutputStream()) { head -> api.handle(head, input, ByteArrayOutputStream(), "10.0.0.8", spki) { _, _, _, _ -> throw StackOverflowError() } }
        } catch (t: Throwable) {
            overflow = t
        }
        check(overflow is IOException && overflow!!.message == "Request body nested too deeply", "A stack overflow closes the request as IOException: $overflow")

        // The deadline is lifted only for an authenticated, allowed request.
        var lifted = 0
        fun lift(request: String) {
            val input = ByteArrayInputStream(request.toByteArray(Charsets.ISO_8859_1))
            Http.serve(input, ByteArrayOutputStream()) { head -> api.handle(head, input, ByteArrayOutputStream(), "10.0.0.8", spki, { lifted++ }) { _, _, _, _ -> JSONObject() } }
        }
        lift("GET /api/status HTTP/1.1\r\nAuthorization: Bearer hwa1_wrong\r\n\r\n")
        lift("GET /api/export HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n")
        lift("GET /api/status HTTP/1.1\r\nOrigin: http://x\r\nAuthorization: Bearer $token\r\n\r\n")
        check(lifted == 0, "No lift for a bad token, a refused route or a browser")
        lift("GET /api/status HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n")
        check(lifted == 1, "Lifted for an authenticated, allowed request")

        // Past 20 failed sign-ins a minute from one address, its requests are closed unanswered; others are unaffected.
        clock += 60_000
        repeat(20) { check(get("GET", "/api/status", "Bearer hwa1_wrong", ).startsWith("HTTP/1.1 401 "), "Failure ${it + 1}: 401") }
        var closed = false
        try {
            get("GET", "/api/status")
        } catch (e: IOException) {
            closed = true
        }
        check(closed && failures.blocked("10.0.0.5"), "The 21st request is closed, even with a valid token")
        check(exchange("GET /api/status HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n", "10.0.0.6").startsWith("HTTP/1.1 200 "), "Another address still works")
        clock += 60_000
        check(!failures.blocked("10.0.0.5") && get("GET", "/api/status").startsWith("HTTP/1.1 200 "), "Unblocked after a minute")
    }
}
