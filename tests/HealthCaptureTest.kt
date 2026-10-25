package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.zip.CRC32

/** Versions per id hex, keyed as HealthTables keys them (CRC-32, plus n * 2^32 for the n-th other bytes with it), and which are complete. */
class MemFiles : HealthCapture.Files {
    val stored = LinkedHashMap<String, LinkedHashMap<Long, String>>()
    val complete = HashSet<String>()
    val lastSeen = HashMap<String, Long>()
    val seenBatches = ArrayList<List<String>>()

    fun put(data: ByteArray, complete: Boolean = true) {
        save(data.copyOf(7), HealthCapture.u32le(data, data.size - 4), data, complete, 0)
    }

    fun latest(id: String): String? = stored[id]?.values?.last()

    fun isComplete(data: ByteArray): Boolean = "${HealthCapture.hex(data.copyOf(7))}/${HealthCapture.u32le(data, data.size - 4)}" in complete

    @Synchronized
    override fun seen(ids: List<ByteArray>, at: Long): Set<String> {
        val hexes = ids.map { HealthCapture.hex(it) }
        seenBatches.add(hexes)
        val found = hexes.filter { h -> stored[h]?.keys?.any { "$h/$it" in complete } == true }.toSet()
        for (h in found) lastSeen[h] = at
        return found
    }

    @Synchronized
    override fun save(id: ByteArray, crc: Long, data: ByteArray, complete: Boolean, at: Long): Boolean {
        val h = HealthCapture.hex(id)
        lastSeen[h] = at
        val versions = stored.getOrPut(h) { LinkedHashMap() }
        val bytes = HealthCapture.hex(data)
        val sameCrc = versions.keys.filter { (it and 0xffffffffL) == crc }
        val key = sameCrc.firstOrNull { versions[it] == bytes } ?: (crc + (sameCrc.size.toLong() shl 32))
        if (complete) this.complete.add("$h/$key")
        return versions.putIfAbsent(key, bytes) == null
    }
}

/** HealthStore for HealthFeature: [files] plus a profile whose reads and writes a test can hook. */
class MemHealthStore(val files: MemFiles = MemFiles()) : HealthFeature.Store, HealthCapture.Files by files {
    @Volatile var saved: JSONObject? = null
    @Volatile var read: () -> JSONObject? = { saved }
    @Volatile var write: (JSONObject) -> Unit = { saved = it }

    override fun profile(): JSONObject? = read()

    override fun profile(p: JSONObject) = write(p)

    override fun list(): JSONArray = JSONArray(files.stored.keys)

    override fun count(): Long = files.stored.size.toLong()

    override fun file(idHex: String, crc: Long?): JSONObject? = files.latest(idHex)?.let { JSONObject().put("id", idHex) }
}

object HealthCaptureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun bytes(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun hex(b: ByteArray): String = HealthCapture.hex(b)

    private class Rig {
        val files = MemFiles()
        val sent = ArrayList<String>()
        val logs = ArrayList<String>()
        val timer = ManualTimer()
        var now = 1_000_000L

        /** Sends the link takes before refusing, as a full window does. */
        var room = Int.MAX_VALUE

        /** Frames awaiting a link ack, and how many may before a paced send is held back (the watch's tx window). */
        var inFlight = 0
        var window = Int.MAX_VALUE
        val capture = HealthCapture(
            files,
            { cmd, paced ->
                if (room <= 0 || paced && inFlight >= window) {
                    false
                } else {
                    sent.add(hex(cmd))
                    room--
                    inFlight++
                    true
                }
            },
            timer,
            { logs.add(it) },
            { now },
        )

        /** The watch link-acks every frame in flight. */
        fun linkAck() {
            inFlight = 0
            capture.onAcked()
        }

        fun status(key: String): Any = capture.status().get(key)

        fun requests(): List<String> = sent.filter { it.startsWith("08081003") }.map { it.substring(it.length - 14) }

        fun acks(): List<String> = sent.filter { it.startsWith("08081005") }.map { it.substring(it.length - 14) }
    }

    /** id + 0x00 pad + body + CRC-32 LE, as the watch sends a complete file. */
    fun file(id: String, body: String = "000102030405060708090a0b0c0d0e0f"): ByteArray {
        val b = bytes(id + "00" + body)
        val c = CRC32()
        c.update(b)
        val v = c.value
        return b + byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    }

    fun chunk(count: Int, index: Int, data: ByteArray): ByteArray =
        byteArrayOf(count.toByte(), (count ushr 8).toByte(), index.toByte(), (index ushr 8).toByte()) + data

    fun profileInput(): JSONObject =
        JSONObject()
            .put("height_cm", 175)
            .put("weight_kg", 70)
            .put("birthday", "1990-01-01")
            .put("gender", "male")
            .put("goal_calories", 350)
            .put("goal_steps", 8000)
            .put("goal_standing_hours", 12)
            .put("goal_moving_minutes", 60)

    private fun profileError(change: (JSONObject) -> Unit): String? {
        val input = profileInput()
        change(input)
        return try {
            HealthCapture.profile(input, LocalDate.of(2026, 10, 24))
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }

    const val TODAY = "0808100152042a020800"
    private const val PAST = "08081002"
    const val USER_INFO = "08081000521e0a1c08af011500008c4218c5cdbe09200128b80130de0238c03e480c583c"
    val DAY: LocalDate = LocalDate.of(2026, 10, 24)

    @JvmStatic
    fun main(args: Array<String>) {
        check(hex(file("01020304000301")) == "0102030400030100000102030405060708090a0b0c0d0e0f804c828a", "Fixture file layout and CRC")
        check(hex(HealthCapture.fetchToday()) == TODAY, "8/1 today request")
        check(hex(HealthCapture.fetchPast()) == PAST, "8/2 past request has no health field")
        check(hex(HealthCapture.requestFile(bytes("01020304000301"))) == "080810035209120701020304000301", "8/3 file request")

        profile()
        mainFlow()
        listGuards()
        lateAndStrayFiles()
        chunkValidation()
        limits()
        acks()
        pacing()
        invalidVersions()

        println("Health capture lists, versions, reassembly, CRC, timeouts, limits, profile: $assertions assertions passed")
    }

    private fun profile() {
        val p = HealthCapture.profile(profileInput(), LocalDate.of(2026, 10, 24))
        check(
            p.length() == 8 && p.getInt("height_cm") == 175 && p.getDouble("weight_kg") == 70.0 && p.getString("birthday") == "1990-01-01" &&
                p.getString("gender") == "male" && p.getInt("goal_calories") == 350 && p.getInt("goal_steps") == 8000 &&
                p.getInt("goal_standing_hours") == 12 && p.getInt("goal_moving_minutes") == 60,
            "Valid profile is kept as entered: $p",
        )
        check(hex(HealthCapture.userInfo(p, DAY)) == USER_INFO, "8/0 user info: float weight, YYYYMMDD birthday, max HR 184 at age 36")
        check(
            hex(HealthCapture.userInfo(JSONObject(p.toString()).put("gender", "female"), DAY)) == USER_INFO.replace("be09200128", "be09200228"),
            "Female is gender 2 in 8/0",
        )
        // Completed years, as GB's Period.between: the formula changes after age 40.
        fun maxHr(birthday: String): Long {
            val info = HealthCapture.userInfo(JSONObject(p.toString()).put("birthday", birthday), DAY)
            return Proto.number(Proto.message(Proto.message(Proto.decode(info), 10), 1), 5, -1)
        }
        check(maxHr("1986-10-25") == 181L, "Day before the 40th birthday: age 39, 220 - 39")
        check(maxHr("1986-10-24") == 180L, "40th birthday: 220 - 40")
        check(maxHr("1985-10-25") == 180L, "Day before the 41st birthday: still 40")
        check(maxHr("1985-10-24") == 178L, "41st birthday: 207 - 0.7 * 41")
        check(profileError { it.put("nickname", "x") } == "Unknown profile field nickname", "Unknown field rejected")
        check(profileError { it.put("height_cm", 175.5) } == "height_cm must be a whole number from 50 to 250", "Fractional height rejected")
        check(profileError { it.put("height_cm", "175") } == "height_cm must be a whole number from 50 to 250", "Height as text rejected")
        check(profileError { it.remove("goal_steps") } == "goal_steps must be a whole number from 100 to 100000", "Missing goal rejected")
        check(profileError { it.put("weight_kg", 400) } == "weight_kg must be a number from 20 to 300", "Weight range")
        check(profileError { it.put("birthday", "1990-13-01") } == "birthday must be a past date as YYYY-MM-DD", "Invalid date rejected")
        check(profileError { it.put("birthday", "2026-10-25") } == "birthday must be a past date as YYYY-MM-DD", "Future birthday rejected")
        check(profileError { it.put("birthday", 19900101) } == "birthday must be a past date as YYYY-MM-DD", "Numeric birthday rejected")
        check(profileError { it.put("gender", 1) } == "gender must be male or female", "Gender code rejected")
    }

    private fun mainFlow() {
        val a = "01020304000301"
        val b = "0a0b0c0d000401"
        val c = "11121314000301"
        val d = "21222324000301"
        val e = "31323334000301"
        val r = Rig()
        r.files.put(file(b), complete = false)
        r.files.put(file(e))
        check(r.capture.start() && r.sent == listOf(TODAY), "Start lists today's files")
        check(!r.capture.start() && r.status("state") == "listing", "A second start while running is refused")

        r.capture.onList(1, bytes(a + b + "00000000000000" + a + c))
        check(r.sent == listOf(TODAY, PAST, "080810035209120701020304000301"), "Today's list asks for the past list and the first file")
        check(r.status("total") == 3 && r.status("current") == a && r.status("message") == "Fetching 1 of 3", "Stored today id is fetched again; empty and duplicate ids skipped")
        check(r.timer.delays() == listOf(5_000L), "Per-chunk timeout armed at request")

        val fa = file(a)
        r.capture.onChunk(chunk(2, 1, fa.copyOfRange(0, 10)))
        check(r.timer.delays() == listOf(5_000L) && r.files.stored[a] == null, "Partial file re-arms the timeout")
        r.capture.onChunk(chunk(2, 2, fa.copyOfRange(10, fa.size)))
        check(r.files.latest(a) == "0102030400030100000102030405060708090a0b0c0d0e0f804c828a", "Reassembled CRC-valid file stored raw")
        check(r.requests() == listOf(a, b), "Next file requested after a store")

        r.now += 1_000
        r.capture.onChunk(chunk(1, 1, file(b)))
        check(r.files.stored[b]!!.size == 1 && r.files.lastSeen[b] == 1_001_000L, "Unchanged today file keeps one version, seen again")
        check(r.status("fetched") == 2 && r.status("new_versions") == 1, "Unchanged file counts as fetched, not new")

        // A damaged file is fetched once more; damaged again, its bytes are kept as an invalid version and acked.
        val bad = file(c)
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
        r.capture.onChunk(chunk(1, 1, bad))
        check(r.requests() == listOf(a, b, c, c) && r.logs.last() == "Health file 11121314000301 failed its CRC check; fetching it again", "CRC failure fetches the file again")
        check(r.files.stored[c] == null && r.acks() == listOf(a, b) && r.status("last_error") == "", "Not stored, acked or an error yet")
        r.capture.onChunk(chunk(1, 1, bad))
        check(r.files.stored[c] == linkedMapOf(1_447_292_810L to "1112131400030100000102030405060708090a0b0c0d0e0f5ced8817"), "Stored raw under the CRC-32 of all its bytes: ${r.files.stored[c]}")
        check(r.acks() == listOf(a, b, c) && r.status("last_error") == "Health file 11121314000301 failed its CRC check twice; stored as an invalid version", "and acked")
        check(r.status("state") == "listing" && r.timer.delays() == listOf(10_000L), "Empty queue waits for the past list")

        r.capture.onList(2, bytes(a + d + e))
        check(r.requests() == listOf(a, b, c, c, d), "Past list: stored id skipped, id listed today ignored, new id fetched")
        check(r.status("already_stored") == 1 && r.files.lastSeen[e] == 1_001_000L, "Stored past id is marked as listed")
        check(r.acks() == listOf(a, b, c, e), "and acked again: the watch still holds it, so its ack never arrived")
        check(r.files.seenBatches == listOf(listOf(d, e)), "One batched lookup per past list, without ids already listed today")
        r.timer.fire()
        check(r.status("last_error") == "Timed out waiting for health file 21222324000301", "Chunk timeout skips the file")
        check(r.status("state") == "idle" && r.status("last_success_at") == 0L && r.status("failed") == 1, "Run with errors is not a success")
        r.capture.onChunk(chunk(1, 1, file(d)))
        check(r.logs.lastOrNull() == "Ignored activity chunk with no file requested" && r.files.stored[d] == null, "Chunk after the run ignored")

        // Next sync: today's file b grew, so its new content is a second version.
        r.now = 1_060_000L
        check(r.capture.start(), "Second sync starts")
        r.capture.onList(1, bytes(b))
        r.capture.onChunk(chunk(1, 1, file(b, "000102030405060708090a0b0c0d0e0f10")))
        check(r.files.stored[b]!!.size == 2 && r.files.latest(b) == hex(file(b, "000102030405060708090a0b0c0d0e0f10")), "Changed today file stored as a new version")
        check(!r.files.isComplete(file(b, "000102030405060708090a0b0c0d0e0f10")), "A version from today's list may still grow")
        r.capture.onList(2, bytes(a + e))
        check(r.requests() == listOf(a, b, c, c, d, b, a), "Past id stored only from a today list is fetched once more")
        r.capture.onChunk(chunk(1, 1, file(a)))
        check(r.files.stored[a]!!.size == 1 && r.files.isComplete(file(a)), "Unchanged content keeps its version, now complete")
        check(r.status("state") == "idle" && r.status("last_success_at") == 1_060_000L && r.status("new_versions") == 1, "Clean run records success")

        // Next day: b, captured partway through yesterday, is listed as past and fetched in full.
        check(r.capture.start(), "Third sync starts")
        r.capture.onList(1, ByteArray(0))
        r.capture.onList(2, bytes(a + b + e))
        check(r.requests() == listOf(a, b, c, c, d, b, a, b) && r.status("already_stored") == 2, "Only b lacks a complete version")
        r.capture.onChunk(chunk(1, 1, file(b, "000102030405060708090a0b0c0d0e0f1011")))
        check(r.files.stored[b]!!.size == 3 && r.files.isComplete(file(b, "000102030405060708090a0b0c0d0e0f1011")), "End of the day stored as a complete version")
        check(r.capture.start(), "Fourth sync starts")
        r.capture.onList(1, ByteArray(0))
        r.capture.onList(2, bytes(a + b + e))
        check(r.requests().size == 8 && r.status("already_stored") == 3 && r.status("state") == "idle", "Complete past files are not fetched again")
        check(r.sent.filter { it.startsWith("08081005") }.first() == "0808100552091a07$a", "8/5 carries the id")
        check(r.acks() == listOf(a, b, c, e, b, e, a, a, e, b, a, b, e), "Each stored file acked after its store, each listed complete one again: ${r.acks()}")
        check(d !in r.acks(), "A file that was never stored is not acked")

        r.now += 60_000
        check(!r.capture.startIfDue(15 * 60_000L), "Unlock trigger is rate-limited")
        r.now += 15 * 60_000
        check(r.capture.startIfDue(15 * 60_000L), "Unlock trigger runs after the interval")
        r.timer.fire()
        check(r.status("state") == "idle" && r.status("last_error") == "Watch did not list health files", "List timeout ends the sync")

        // Disconnect mid-file: nothing waits on the transfer, and stop leaves no timer or state behind.
        val r4 = Rig()
        r4.capture.start()
        r4.capture.onList(1, bytes(a))
        r4.capture.onChunk(chunk(2, 1, file(a).copyOfRange(0, 10)))
        r4.capture.stop("Watch disconnected during health sync")
        check(r4.status("state") == "idle" && r4.timer.delays().isEmpty(), "Disconnect ends the transfer and stops timers")
        check(r4.status("last_error") == "Watch disconnected during health sync" && !r4.status().has("current"), "Disconnect is reported")
        r4.capture.onChunk(chunk(2, 2, file(a).copyOfRange(10, file(a).size)))
        check(r4.files.stored.isEmpty() && r4.logs.lastOrNull() == "Ignored activity chunk with no file requested", "Chunks after a disconnect are ignored")
        check(r4.capture.start(), "A new sync can start after a disconnect")
    }

    private fun Rig.status(): JSONObject = capture.status()

    private fun listGuards() {
        val a = "01020304000301"
        val d = "21222324000301"
        val r = Rig()
        r.capture.start()
        r.capture.onList(2, bytes(d))
        check(r.logs.lastOrNull() == "Ignored health file list 8/2 that this sync did not request", "Stale 8/2 before today's list is ignored")
        check(r.status("state") == "listing" && r.requests().isEmpty() && r.timer.delays() == listOf(10_000L), "Stale 8/2 does not end the sync")
        r.capture.onList(1, bytes(a))
        r.capture.onList(1, bytes(d))
        check(r.logs.lastOrNull() == "Ignored health file list 8/1 that this sync did not request" && r.status("total") == 1, "Second 8/1 ignored")
        r.capture.onList(2, bytes(d))
        r.capture.onList(2, ByteArray(0))
        check(r.status("total") == 2 && r.status("state") == "fetching", "Every 8/2 after the request is accepted")

        val r2 = Rig()
        r2.capture.onList(1, bytes(a))
        check(r2.logs.lastOrNull() == "Ignored health file list 8/1 that this sync did not request" && r2.sent.isEmpty(), "List outside a sync ignored")
        r2.capture.start()
        r2.capture.onList(1, bytes("0102"))
        check(r2.status("last_error") == "Watch sent a malformed health file list of 2 bytes" && r2.sent.last() == PAST, "Malformed list still asks for the past")
    }

    private fun lateAndStrayFiles() {
        val a = "01020304000301"
        val b = "0a0b0c0d000401"
        val c = "11121314000301"
        val x = "41424344000301"
        val r = Rig()
        r.capture.start()
        r.capture.onList(1, bytes(a + b + c))
        r.timer.fire()
        check(r.requests() == listOf(a, b) && r.status("failed") == 1, "File a timed out; b requested")
        // a arrives after all, while b is the file in flight.
        r.capture.onChunk(chunk(1, 1, file(a)))
        check(r.files.latest(a) == hex(file(a)) && r.logs.lastOrNull() == "Received 01020304000301 late while waiting for 0a0b0c0d000401", "Late file stored under its own id")
        check(r.status("current") == b && r.requests() == listOf(a, b) && r.timer.delays() == listOf(5_000L), "Still waiting for b, timer re-armed")
        check(r.status("failed") == 0 && r.status("fetched") == 1, "Late file no longer counts as failed")
        // c is still queued; if it arrives unrequested it is kept and not requested again.
        r.capture.onChunk(chunk(1, 1, file(c)))
        r.capture.onChunk(chunk(1, 1, file(b)))
        check(r.files.latest(b) == hex(file(b)) && r.files.latest(c) == hex(file(c)), "b completes after the late files")
        check(r.requests() == listOf(a, b) && r.status("state") == "listing" && r.status("fetched") == 3, "Queued file that arrived early is not requested")

        // A damaged late copy of a is dropped; b, in flight, is unaffected.
        val r3 = Rig()
        r3.capture.start()
        r3.capture.onList(1, bytes(a + b))
        r3.timer.fire()
        val bad = file(a)
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
        r3.capture.onChunk(chunk(1, 1, bad))
        check(r3.logs.lastOrNull() == "Discarded a damaged copy of 01020304000301 (28 bytes) that arrived late", "Late file with a bad CRC discarded")
        r3.capture.onChunk(chunk(1, 1, file(a).copyOf(10)))
        check(r3.logs.lastOrNull() == "Discarded a damaged copy of 01020304000301 (10 bytes) that arrived late", "Short late file discarded")
        check(r3.status("current") == b && r3.timer.delays() == listOf(5_000L) && r3.status("failed") == 1, "Still waiting for b, timer re-armed")
        r3.capture.onChunk(chunk(1, 1, file(b)))
        check(r3.files.latest(b) == hex(file(b)) && r3.files.stored[a] == null && r3.status("fetched") == 1, "b completes")

        val r2 = Rig()
        r2.capture.start()
        r2.capture.onList(1, bytes(b))
        r2.capture.onChunk(chunk(1, 1, file(x)))
        check(r2.status("last_error") == "Watch sent file 41424344000301 when asked for 0a0b0c0d000401" && r2.files.stored.isEmpty(), "Unlisted file rejected")
        check(r2.status("current") == b && r2.status("state") == "fetching", "Unlisted file does not end the wait for b")
        r2.capture.onChunk(chunk(1, 1, file(b)))
        check(r2.files.latest(b) == hex(file(b)), "b still arrives")
    }

    private fun chunkValidation() {
        val a = "01020304000301"
        val fa = file(a)
        val parts = listOf(fa.copyOfRange(0, 10), fa.copyOfRange(10, 20), fa.copyOfRange(20, fa.size))
        val r = Rig()
        r.capture.start()
        r.capture.onList(1, bytes(a))
        r.capture.onChunk(chunk(3, 2, parts[1]))
        check(r.logs.lastOrNull() == "Ignored activity chunk 2 of 3 before chunk 1", "Transfer must start at chunk 1")
        r.capture.onChunk(chunk(0, 0, parts[0]))
        check(r.logs.lastOrNull() == "Ignored activity chunk 0 of 0", "Zero count rejected")
        r.capture.onChunk(chunk(3, 4, parts[0]))
        check(r.logs.lastOrNull() == "Ignored activity chunk 4 of 3", "Index past the count rejected")
        r.capture.onChunk(chunk(3, 1, ByteArray(0)))
        check(r.logs.lastOrNull() == "Ignored an activity chunk with no data", "Empty chunk rejected")
        r.capture.onChunk(chunk(3, 1, parts[0]))
        r.capture.onChunk(chunk(3, 1, parts[0]))
        check(r.logs.lastOrNull() == "Ignored activity repeated chunk 1 of 3", "Duplicate chunk 1 ignored")
        r.capture.onChunk(chunk(3, 3, parts[2]))
        check(r.logs.lastOrNull() == "Ignored activity chunk 3 of 3 after chunk 1", "Out-of-order chunk ignored")
        r.capture.onChunk(chunk(4, 2, parts[1]))
        check(r.logs.lastOrNull() == "Ignored activity chunk 2 of 4 during a 3-chunk file", "Changed count ignored")
        r.capture.onChunk(chunk(3, 2, parts[1]))
        r.capture.onChunk(chunk(3, 2, parts[1]))
        check(r.logs.lastOrNull() == "Ignored activity repeated chunk 2 of 3", "Duplicate chunk 2 ignored")
        r.capture.onChunk(chunk(3, 3, parts[2]))
        check(r.files.latest(a) == hex(fa) && r.status("failed") == 0 && r.status("last_error") == "", "Only the valid chunks are reassembled")

        // Chunk 1 again restarts the file, as a resend from the start would.
        val r2 = Rig()
        r2.capture.start()
        r2.capture.onList(1, bytes(a))
        r2.capture.onChunk(chunk(3, 1, parts[0]))
        r2.capture.onChunk(chunk(3, 2, bytes("ffff")))
        r2.capture.onChunk(chunk(3, 1, parts[0]))
        r2.capture.onChunk(chunk(3, 2, parts[1]))
        r2.capture.onChunk(chunk(3, 3, parts[2]))
        check(r2.files.latest(a) == hex(fa), "Restart at chunk 1 discards the partial file")
    }

    private fun acks() {
        val a = "01020304000301"
        val b = "0a0b0c0d000401"
        val x = "41424344000301"
        val y = "51525354000301"
        val z = "61626364000301"

        // A refused ack holds the next request, which the watch would not answer, until the link has room.
        val r = Rig()
        r.capture.start()
        r.capture.onList(1, bytes(a + b))
        r.room = 0
        r.capture.onChunk(chunk(1, 1, file(a)))
        check(r.files.latest(a) == hex(file(a)) && r.acks().isEmpty() && r.requests() == listOf(a), "Stored, ack refused, b not requested")
        check(r.status("state") == "acking" && r.status("message") == "Acknowledging 1 stored files" && r.timer.delays().isEmpty(), "Waiting on the link, no timer")
        r.room = Int.MAX_VALUE
        r.capture.onAcked()
        check(r.sent.takeLast(2) == listOf("0808100552091a07$a", "0808100352091207$b"), "On the link's ack: a's ack, then b's request")
        r.capture.onAcked()
        check(r.acks() == listOf(a) && r.requests() == listOf(a, b), "Nothing owed: another link ack sends nothing")

        // Stored complete ids the past list names again are acked in order, paced by what the link takes.
        val p = Rig()
        listOf(x, y, z).forEach { p.files.put(file(it)) }
        p.capture.start()
        p.capture.onList(1, ByteArray(0))
        p.room = 1
        p.capture.onList(2, bytes(x + y + a + z))
        check(p.acks() == listOf(x) && p.requests().isEmpty() && p.status("message") == "Acknowledging 2 stored files", "One ack fit; the rest and the request wait")
        p.room = 1
        p.capture.onAcked()
        check(p.acks() == listOf(x, y) && p.requests().isEmpty(), "The next link ack takes one more")
        p.room = Int.MAX_VALUE
        p.capture.onAcked()
        check(p.acks() == listOf(x, y, z) && p.requests() == listOf(a) && p.timer.delays() == listOf(5_000L), "Then the last, then the new file's request")
        p.capture.onChunk(chunk(1, 1, file(a)))
        check(p.acks() == listOf(x, y, z, a) && p.status("state") == "idle", "The run ends")

        // A disconnect forgets owed acks: the next session's lists name those files again.
        val d = Rig()
        d.capture.start()
        d.capture.onList(1, bytes(a))
        d.room = 0
        d.capture.onChunk(chunk(1, 1, file(a)))
        d.capture.stop("Watch disconnected during health sync")
        d.room = Int.MAX_VALUE
        d.capture.onAcked()
        check(d.acks().isEmpty(), "No ack after the session ended")

        // Never acked before its bytes are stored: a failed save leaves the file unacked.
        val sent = ArrayList<String>()
        val logs = ArrayList<String>()
        val failing = object : HealthCapture.Files {
            override fun seen(ids: List<ByteArray>, at: Long): Set<String> = emptySet()

            override fun save(id: ByteArray, crc: Long, data: ByteArray, complete: Boolean, at: Long): Boolean = throw java.io.IOException("disk full")
        }
        val f = HealthCapture(failing, { cmd, _ -> sent.add(hex(cmd)) }, ManualTimer(), { logs.add(it) }, { 1L })
        f.start()
        f.onList(1, bytes(a + b))
        f.onChunk(chunk(1, 1, file(a)))
        check(sent.none { it.startsWith("08081005") } && f.status().getString("last_error") == "Could not store health file 01020304000301: disk full", "Save failed: no ack")
        check(sent.last() == "0808100352091207$b" && f.status().getInt("failed") == 1, "and the next file is requested")
        // Nor a damaged one whose invalid version could not be stored.
        f.onChunk(chunk(1, 1, file(b).copyOf(10)))
        f.onChunk(chunk(1, 1, file(b).copyOf(10)))
        check(sent.none { it.startsWith("08081005") } && f.status().getString("last_error") == "Could not store health file 0a0b0c0d000401: disk full", "Invalid version not stored: no ack")

        // A file too short to hold its CRC is fetched again, then kept as an invalid version.
        val s = Rig()
        s.capture.start()
        s.capture.onList(1, bytes(a))
        s.capture.onChunk(chunk(1, 1, file(a).copyOf(10)))
        check(s.requests() == listOf(a, a) && s.logs.last() == "Health file 01020304000301 is too short (10 bytes); fetching it again", "Short file fetched again")
        s.capture.onChunk(chunk(1, 1, file(a).copyOf(10)))
        check(s.files.stored[a] == linkedMapOf(3_496_289_565L to "01020304000301000001") && s.acks() == listOf(a), "then stored as invalid and acked")
        check(s.status("last_error") == "Health file 01020304000301 is too short (10 bytes) twice; stored as an invalid version" && s.status("state") == "listing", "and reported")
    }

    private fun limits() {
        val a = "01020304000301"
        val b = "0a0b0c0d000401"
        val r = Rig()
        r.capture.start()
        r.capture.onList(1, bytes(a + b))
        val oversize = "Health file 01020304000301 exceeds 4194304 bytes; it stays on the watch, which sends no other file until it is acknowledged"
        r.capture.onChunk(chunk(3, 1, ByteArray(HealthCapture.MAX_FILE_BYTES + 1)))
        check(r.status("last_error") == oversize && r.status("failed") == 1, "4 MB cap")
        check(r.status("current") == a && r.requests() == listOf(a), "The rest of it is still coming: the next request waits")
        r.capture.onList(2, bytes("0a0b0c0d000501"))
        r.capture.onChunk(chunk(3, 2, ByteArray(100)))
        r.capture.onChunk(chunk(3, 3, ByteArray(100)))
        // Unacked, it blocks the watch's queue (8/5): the run ends there instead of timing out on every later file.
        check(r.requests() == listOf(a) && r.acks().isEmpty() && r.files.stored.isEmpty(), "Its end requests nothing more; it is neither acked nor stored")
        check(r.status("state") == "idle" && r.status("deferred") == 2 && r.timer.delays().isEmpty(), "The run ends, both files left for the next sync")
        check(r.logs.last() == "Health sync finished: 0 fetched (0 new), 1 failed, 0 already stored, 2 left for the next sync" && r.status("last_success_at") == 0L, "and reports them")
        // One that stops partway ends the run at the chunk timer, without a second error.
        val r7 = Rig()
        r7.capture.start()
        r7.capture.onList(1, bytes(a + b))
        r7.capture.onChunk(chunk(3, 1, ByteArray(HealthCapture.MAX_FILE_BYTES + 1)))
        r7.timer.fire()
        check(r7.requests() == listOf(a) && r7.status("last_error") == oversize && r7.status("deferred") == 1 && r7.status("state") == "idle", "Timeout ends the run quietly")

        // A trickle of chunks cannot outlast the file deadline.
        val r2 = Rig()
        r2.capture.start()
        r2.capture.onList(1, bytes(a))
        r2.now += HealthCapture.FILE_DEADLINE_MS - 2_000
        r2.capture.onChunk(chunk(3, 1, file(a).copyOfRange(0, 10)))
        check(r2.timer.delays() == listOf(2_000L), "Chunk timer is cut short by the file deadline")
        r2.now += 2_000
        r2.timer.fire()
        check(r2.status("last_error") == "Health file 01020304000301 did not finish within 120 s" && r2.status("state") == "listing", "Deadline ends the transfer")

        fun ids(from: Int, to: Int): List<String> = (from..to).map { "%08x".format(Integer.reverseBytes(it)) + "000301" }
        val r3 = Rig()
        r3.capture.start()
        r3.capture.onList(1, bytes(ids(1, 2_001).joinToString("")))
        check(r3.status("total") == 2_000 && r3.status("deferred") == 1 && r3.status("state") == "fetching", "2000 fetches per sync; the rest wait for the next")
        check(r3.status("last_error") == "" && r3.requests() == listOf(ids(1, 1)[0]), "Deferral is not an error")

        // Stored past files do not count toward the fetch limit.
        val r5 = Rig()
        val stored = ids(1, 2_500)
        stored.forEach { r5.files.put(file(it)) }
        r5.capture.start()
        r5.capture.onList(1, ByteArray(0))
        r5.capture.onList(2, bytes((stored + ids(2_501, 2_501)).joinToString("")))
        check(r5.status("already_stored") == 2_500 && r5.status("total") == 1 && r5.requests() == ids(2_501, 2_501), "2500 stored past ids, one new fetched")
        check(r5.files.seenBatches.size == 1 && r5.files.seenBatches[0].size == 2_501, "One batched lookup for the whole list")

        val r6 = Rig()
        r6.capture.start()
        r6.capture.onList(1, bytes(ids(1, HealthCapture.MAX_LISTED_PER_RUN + 1).joinToString("")))
        check(r6.status("last_error") == "Watch listed more than 20000 health files" && r6.status("state") == "idle", "Listed-id cap aborts the run")
        check(r6.sent == listOf(TODAY) && r6.timer.delays().isEmpty(), "Aborted run requests nothing more")

        val r4 = Rig()
        r4.capture.start()
        r4.capture.onList(1, bytes(a))
        repeat(HealthCapture.MAX_LISTS_PER_RUN - 1) { r4.capture.onList(2, ByteArray(0)) }
        check(r4.status("state") == "fetching", "32 lists are accepted")
        r4.capture.onList(2, ByteArray(0))
        check(r4.status("last_error") == "Watch sent more than 32 health file lists" && r4.status("state") == "idle", "List cap aborts the run")
        check(r4.timer.delays().isEmpty() && r4.status("failed") == 1, "Abort stops the timer and fails the file in flight")
    }

    /** Acks and requests are paced by the link's window, held ones resume on link acks, and owed acks wait for a gap between files. */
    private fun pacing() {
        val a = "01020304000301"
        val b = "0a0b0c0d000401"
        val stored = (1..8).map { "%02x".format(it) + "222324000301" }

        // Eight stored past files listed again: re-acks go three at a time, refilled by link acks, then the request.
        val r = Rig()
        stored.forEach { r.files.put(file(it)) }
        r.window = 3
        r.capture.start()
        r.capture.onList(1, ByteArray(0))
        r.linkAck()
        r.capture.onList(2, bytes(stored.joinToString("") + a))
        check(r.acks() == stored.take(3) && r.requests().isEmpty(), "Three acks in flight, the rest wait: ${r.acks()}")
        check(r.status("state") == "acking" && r.status("message") == "Acknowledging 5 stored files" && r.timer.delays().isEmpty(), "Held, no timer")
        check(r.logs.count { it == "Health sync waits for room in the send window" } == 1, "Logged once")
        r.linkAck()
        check(r.acks() == stored.take(6) && r.requests().isEmpty(), "A link ack refills the window")
        r.linkAck()
        check(r.acks() == stored && r.requests() == listOf(a), "The last two acks, then a's request")
        check(r.logs.count { it == "Health sync waits for room in the send window" } == 1, "Still logged once per run")

        // A held request: the link takes no 8/3 until an ack frees room, and no file timer runs meanwhile.
        val q = Rig()
        q.capture.start()
        q.window = 1
        q.capture.onList(1, bytes(a + b))
        check(q.requests().isEmpty() && q.status("state") == "waiting" && q.timer.delays().isEmpty(), "The request is held: ${q.sent}")
        q.linkAck()
        check(q.requests() == listOf(a) && q.status("current") == a && q.timer.delays() == listOf(5_000L), "and sent on the link ack")
        q.capture.onChunk(chunk(1, 1, file(a)))
        check(q.acks().isEmpty() && q.requests() == listOf(a), "a stored; its ack waits for room too")
        q.linkAck()
        check(q.acks() == listOf(a) && q.requests() == listOf(a), "Its ack, alone in the window")
        q.linkAck()
        check(q.requests() == listOf(a, b), "Then b's request")

        // A past list arriving while a file streams: its re-acks wait for the end of that file.
        val m = Rig()
        m.files.put(file(stored[0]))
        m.capture.start()
        m.capture.onList(1, bytes(a))
        val fa = file(a)
        m.capture.onChunk(chunk(2, 1, fa.copyOfRange(0, 10)))
        m.capture.onList(2, bytes(stored[0]))
        m.linkAck()
        check(m.acks().isEmpty() && m.status("current") == a, "No ack while a streams")
        m.capture.onChunk(chunk(2, 2, fa.copyOfRange(10, fa.size)))
        check(m.acks() == listOf(stored[0], a) && m.status("state") == "idle", "Between files: the re-ack, then a's")

        // A late file's ack goes at once even with a request out: the watch waits for it before the requested file.
        val l = Rig()
        l.capture.start()
        l.capture.onList(1, bytes(a + b))
        l.timer.fire()
        l.window = 1
        l.capture.onChunk(chunk(1, 1, file(a)))
        check(l.acks().isEmpty() && l.status("current") == b, "a arrived late; its ack is held by the window")
        l.linkAck()
        check(l.acks() == listOf(a), "and goes on the link ack while b has not started")
    }

    /** Two damaged copies of one id whose CRC-32 of all bytes match are two versions: each acked only once its own bytes are stored. */
    private fun invalidVersions() {
        val c = "11121314000301"
        fun damaged(body: String): ByteArray = file(c, body).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        fun whole(b: ByteArray): Long = CRC32().also { it.update(b) }.value
        val one = damaged("000102030405060708090a0b0c0d0e0f")
        val two = damaged("0f0e0d0c")
        check(whole(one) == 1_447_292_810L && whole(two) == 1_447_292_810L, "Same flipped bit at the end: same CRC-32 of all bytes")
        val r = Rig()
        fun sync(data: ByteArray) {
            check(r.capture.start(), "Sync starts")
            r.capture.onList(1, bytes(c))
            r.capture.onChunk(chunk(1, 1, data))
            r.capture.onChunk(chunk(1, 1, data))
            r.capture.onList(2, ByteArray(0))
        }
        sync(one)
        sync(two)
        check(r.files.stored[c] == linkedMapOf(1_447_292_810L to hex(one), 5_742_260_106L to hex(two)), "The second is stored under the next key: ${r.files.stored[c]}")
        check(r.acks() == listOf(c, c) && r.status("new_versions") == 1, "and acked")
        sync(one)
        check(r.files.stored[c]!!.size == 2 && r.status("new_versions") == 0 && r.acks() == listOf(c, c, c), "The first bytes again: no new version, acked")
    }
}
