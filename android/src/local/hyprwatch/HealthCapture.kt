package local.hyprwatch

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeParseException
import java.util.zip.CRC32
import kotlin.math.roundToInt

/*
 * The 8/1 -> 8/2 -> 8/3 fetch sequence, the channel-5 chunk layout, the CRC-32 check and the
 * user info message are ported from Gadgetbridge's XiaomiHealthService and
 * XiaomiActivityFileFetcher (Copyright (C) 2023-2024 José Rebelo, Yoran Vulker), AGPL-3.0-or-later.
 */

/**
 * Captures raw activity files, one requested at a time. Driven by the Bluetooth reader thread
 * (lists, chunks, link acks) and the timer thread; every entry point is short and holds this
 * object's lock, and no thread waits for a file. A file is acked (8/5) only after its raw bytes are
 * stored: the watch sends nothing more until the previous file is acked, and drops acked files. So a
 * past id already stored complete is acked again when listed, and a damaged file is fetched once
 * more, then stored as an invalid version and acked. A file over [MAX_FILE_BYTES] stays unacked, so
 * the watch sends nothing after it: the run ends there. File ids stay opaque 7-byte arrays.
 *
 * Acks and requests (8/3) are paced sends: the link takes them while fewer frames than the watch's
 * tx window await an ack, so a burst of acks never fills the send window for other features. One
 * held back holds the run (no ack or request goes past it) until a link ack. Owed acks go out only
 * between files: before the next request, after a file that arrived late, or while the requested
 * file has not started.
 *
 * A stored version is complete when it was fetched while the watch listed its id as past (8/2).
 * One fetched from today's list (8/1) may still grow, so a past id without a complete version is
 * fetched once more.
 */
class HealthCapture(
    private val files: Files,
    private val port: Port,
    private val timer: WatchTimer,
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Files {
        /** Hex ids among [ids] with a complete version, each marked as listed at [at], in one transaction. */
        fun seen(ids: List<ByteArray>, at: Long): Set<String>

        /**
         * Stores [data], whose CRC-32 is [crc], as a version of [id] unless [id] has a version with
         * exactly these bytes; true if it was new. Either way that version holds [data] when this
         * returns, was seen at [at], and becomes complete if [complete].
         */
        fun save(id: ByteArray, crc: Long, data: ByteArray, complete: Boolean, at: Long): Boolean
    }

    /** Queues a fire-and-forget command, [paced] or not (FeatureHost.sendPaced); false when the link did not take it. Must neither block nor throw. */
    fun interface Port {
        fun send(cmd: ByteArray, paced: Boolean): Boolean
    }

    /** List replies this run accepts: 8/1 once, then any 8/2 after it was requested. */
    private enum class Lists { AWAIT_TODAY, AWAIT_PAST, COMPLETE }

    /**
     * The requested file. [received] counts contiguous chunks; 0 until chunk 1 of [count] arrives.
     * An [oversize] file's remaining chunks are counted but not kept, so the run ends after its last.
     */
    private class Transfer(val id: ByteArray, val deadline: Long) {
        val buffer = ByteArrayOutputStream()
        var count = 0
        var received = 0
        var oversize = false

        fun restart() {
            buffer.reset()
            count = 0
            received = 0
            oversize = false
        }
    }

    private class Run {
        var lists = Lists.AWAIT_TODAY
        var listReplies = 0

        /** Id hex -> whether it came from the past list, so a fetched version is complete. */
        val listed = HashMap<String, Boolean>()
        val queue = ArrayDeque<ByteArray>()
        var transfer: Transfer? = null

        /** Hex ids fetched again after a damaged copy. */
        val retried = HashSet<String>()

        /** The link held back an ack or the next request: [onAcked] goes on. Logged once per run. */
        var held = false
        var heldLogged = false
    }

    /** Null when idle. */
    private var run: Run? = null
    private var cancelTimer: (() -> Unit)? = null
    private var timerGen = 0

    /** Stored ids whose 8/5 the link has not taken yet, oldest first; a disconnect forgets them, and the next lists name them again. */
    private val unacked = ArrayDeque<ByteArray>()

    // Results of the latest run; id hex -> stored.
    private val results = HashMap<String, Boolean>()
    private var total = 0
    private var stored = 0
    private var known = 0
    private var deferred = 0
    private var runErrors = 0
    private var startedAt = 0L
    private var lastError = ""
    private var lastErrorAt = 0L
    private var lastSuccessAt = 0L

    @Synchronized
    fun start(): Boolean {
        if (run != null) return false
        run = Run()
        results.clear()
        total = 0
        stored = 0
        known = 0
        deferred = 0
        runErrors = 0
        startedAt = clock()
        port.send(fetchToday(), false)
        arm(LIST_TIMEOUT_MS)
        return true
    }

    /** Rate-limited start for automatic triggers such as screen unlock. */
    @Synchronized
    fun startIfDue(intervalMs: Long): Boolean = if (startedAt != 0L && clock() - startedAt < intervalMs) false else start()

    /** 8/1 (today) or 8/2 (past) reply: N concatenated 7-byte file ids. */
    @Synchronized
    fun onList(sub: Int, ids: ByteArray) {
        val r = run
        val requested = r != null && if (sub == 1) r.lists == Lists.AWAIT_TODAY else r.lists != Lists.AWAIT_TODAY
        if (r == null || !requested) {
            log("Ignored health file list 8/$sub that this sync did not request")
            return
        }
        if (++r.listReplies > MAX_LISTS_PER_RUN) return abort(r, "Watch sent more than $MAX_LISTS_PER_RUN health file lists")
        if (ids.size % 7 != 0) {
            error("Watch sent a malformed health file list of ${ids.size} bytes")
        } else {
            val fresh = ArrayList<ByteArray>()
            for (i in ids.indices step 7) {
                val id = ids.copyOfRange(i, i + 7)
                // GB skips ids with no timestamp and no version.
                if ((id[0].toInt() or id[1].toInt() or id[2].toInt() or id[3].toInt() or id[5].toInt()) == 0) continue
                if (r.listed.putIfAbsent(hex(id), sub == 2) != null) continue
                if (r.listed.size > MAX_LISTED_PER_RUN) return abort(r, "Watch listed more than $MAX_LISTED_PER_RUN health files")
                fresh.add(id)
            }
            val complete = if (sub == 2 && fresh.isNotEmpty()) files.seen(fresh, clock()) else emptySet()
            for (id in fresh) {
                when {
                    hex(id) in complete -> {
                        known++
                        // Listed again, so its ack was lost or never sent. Sent between files.
                        unacked.addLast(id)
                    }
                    total >= MAX_FETCHES_PER_RUN -> deferred++
                    else -> {
                        r.queue.addLast(id)
                        total++
                    }
                }
            }
        }
        if (sub == 1) {
            r.lists = Lists.AWAIT_PAST
            port.send(fetchPast(), false)
        } else {
            r.lists = Lists.COMPLETE
        }
        if (r.transfer == null) next(r)
    }

    /** A link ack freed room in the send window: a held run goes on; otherwise owed acks go, unless a file is streaming. */
    @Synchronized
    fun onAcked() {
        val r = run
        if (r != null && r.held) return next(r)
        val t = r?.transfer
        if (t == null || t.received == 0) flushAcks()
    }

    /** Decrypted channel-5 body: u16 LE chunk count, u16 LE 1-based index, file bytes. */
    @Synchronized
    fun onChunk(chunk: ByteArray) {
        val r = run
        val t = r?.transfer
        if (r == null || t == null) {
            log("Ignored activity chunk with no file requested")
            return
        }
        if (chunk.size <= 4) return log("Ignored an activity chunk with no data")
        val count = Frames.u16(chunk, 0)
        val index = Frames.u16(chunk, 2)
        val problem = when {
            count == 0 || index == 0 || index > count -> "chunk $index of $count"
            index == t.received && count == t.count -> "repeated chunk $index of $count"
            index == 1 -> null
            t.received == 0 -> "chunk $index of $count before chunk 1"
            count != t.count -> "chunk $index of $count during a ${t.count}-chunk file"
            index != t.received + 1 -> "chunk $index of $count after chunk ${t.received}"
            else -> null
        }
        // Not this file's data (a late file's tail, a resend): the chunk timer decides if it is lost.
        if (problem != null) return log("Ignored activity $problem")
        if (index == 1) t.restart()
        if (!t.oversize && t.buffer.size() + chunk.size - 4 > MAX_FILE_BYTES) {
            error("Health file ${hex(t.id)} exceeds $MAX_FILE_BYTES bytes; it stays on the watch, which sends no other file until it is acknowledged")
            if (results[hex(t.id)] != true) results[hex(t.id)] = false
            t.oversize = true
            t.buffer.reset()
        }
        if (!t.oversize) t.buffer.write(chunk, 4, chunk.size - 4)
        t.count = count
        t.received = index
        if (index != count) return armTransfer(t)
        if (t.oversize) return blocked(r)
        val data = t.buffer.toByteArray()
        val n = data.size
        val id = data.copyOf(7)
        if (n >= 7 && !id.contentEquals(t.id)) {
            other(r, id, data)
            t.restart()
            return armTransfer(t)
        }
        if (n < 13) return damaged(r, data, "is too short ($n bytes)")
        val crc = crc32(data, n - 4)
        if (crc != u32le(data, n - 4)) return damaged(r, data, "failed its CRC check")
        val failure = store(r, id, crc, data)
        if (failure != null) return skip(r, failure)
        r.transfer = null
        next(r)
    }

    /**
     * The requested file arrived too short or with a bad CRC: fetched once more, then stored as an
     * invalid version (keyed by the CRC-32 of all its bytes; ingest reports it) and acked, since the
     * watch sends nothing else until it is.
     */
    private fun damaged(r: Run, data: ByteArray, why: String) {
        val t = r.transfer!!
        val key = hex(t.id)
        r.transfer = null
        if (r.retried.add(key)) {
            log("Health file $key $why; fetching it again")
            r.queue.addFirst(t.id)
            return next(r)
        }
        error("Health file $key $why twice; stored as an invalid version")
        val failure = store(r, t.id, crc32(data, data.size), data)
        if (failure != null) {
            error(failure)
            results[key] = false
        }
        next(r)
    }

    /** A file other than the requested one; one that timed out earlier may still arrive and is kept. */
    private fun other(r: Run, id: ByteArray, data: ByteArray) {
        val n = data.size
        if (hex(id) !in r.listed) return error("Watch sent file ${hex(id)} when asked for ${hex(r.transfer!!.id)}")
        val crc = if (n >= 13) crc32(data, n - 4) else null
        if (crc == null || crc != u32le(data, n - 4)) return log("Discarded a damaged copy of ${hex(id)} ($n bytes) that arrived late")
        r.queue.removeAll { it.contentEquals(id) }
        val failure = store(r, id, crc, data)
        if (failure != null) return error(failure)
        log("Received ${hex(id)} late while waiting for ${hex(r.transfer!!.id)}")
        // The watch waits for this ack before it sends the requested file.
        flushAcks()
    }

    /** The oversize file in flight has ended: the watch sends nothing more until it is acked, so the run ends and the queue waits for the next sync. */
    private fun blocked(r: Run) {
        r.transfer = null
        deferred += r.queue.size
        r.queue.clear()
        flushAcks()
        finish()
    }

    /** Disconnect: drop the run; files not stored or not acked are listed again next sync. */
    @Synchronized
    fun stop(reason: String) {
        unacked.clear()
        if (run == null) return
        error(reason)
        stopTimer()
        run = null
    }

    @Synchronized
    fun status(): JSONObject {
        val t = run?.transfer
        val fetched = results.values.count { it }
        val failed = results.size - fetched
        val (state, message) = when {
            run == null -> "idle" to "Idle"
            t != null -> "fetching" to "Fetching ${results.size + 1} of $total"
            unacked.isNotEmpty() -> "acking" to "Acknowledging ${unacked.size} stored files"
            run!!.held -> "waiting" to "Waiting for room in the send window"
            else -> "listing" to "Listing health files"
        }
        val j = JSONObject()
            .put("state", state)
            .put("message", message)
            .put("total", total)
            .put("fetched", fetched)
            .put("new_versions", stored)
            .put("failed", failed)
            .put("already_stored", known)
            .put("deferred", deferred)
            .put("last_started_at", startedAt)
            .put("last_success_at", lastSuccessAt)
            .put("last_error", lastError)
            .put("last_error_at", lastErrorAt)
        t?.let { j.put("current", hex(it.id)) }
        return j
    }

    /** Between files: the owed acks, then the next request, or the end of the run. */
    private fun next(r: Run) {
        r.held = false
        // Requests wait while an ack does: the watch would not answer them.
        if (!flushAcks()) return hold(r)
        val id = r.queue.firstOrNull()
        if (id != null) {
            if (!port.send(requestFile(id), true)) return hold(r)
            r.queue.removeFirst()
            val t = Transfer(id, clock() + FILE_DEADLINE_MS)
            r.transfer = t
            armTransfer(t)
        } else if (r.lists != Lists.COMPLETE) {
            arm(LIST_TIMEOUT_MS)
        } else {
            finish()
        }
    }

    /** The link held back an ack or request: no timer runs, the next link ack resumes, and a dropped link ends the run. */
    private fun hold(r: Run) {
        r.held = true
        stopTimer()
        if (r.heldLogged) return
        r.heldLogged = true
        log("Health sync waits for room in the send window")
    }

    private fun finish() {
        stopTimer()
        run = null
        if (runErrors == 0) lastSuccessAt = clock()
        log("Health sync finished: ${results.values.count { it }} fetched ($stored new), ${results.values.count { !it }} failed, $known already stored, $deferred left for the next sync")
    }

    /** Null when stored or already stored, else why not. */
    private fun store(r: Run, id: ByteArray, crc: Long, data: ByteArray): String? {
        try {
            if (files.save(id, crc, data, r.listed[hex(id)] == true, clock())) stored++
        } catch (e: Exception) {
            return "Could not store health file ${hex(id)}: ${e.message}"
        }
        results[hex(id)] = true
        log("Received health file ${hex(id)} (${data.size} bytes)")
        unacked.addLast(id)
        return null
    }

    /** Sends the owed acks in order, paced; false when the link held one back, which stays first. */
    private fun flushAcks(): Boolean {
        while (unacked.isNotEmpty()) {
            if (!port.send(ackFile(unacked.first()), true)) return false
            unacked.removeFirst()
        }
        return true
    }

    /** Gives up on the requested file and moves on. */
    private fun skip(r: Run, message: String) {
        error(message)
        val key = hex(r.transfer!!.id)
        if (results[key] != true) results[key] = false
        r.transfer = null
        next(r)
    }

    private fun abort(r: Run, message: String) {
        error(message)
        r.transfer?.let { t -> if (results[hex(t.id)] != true) results[hex(t.id)] = false }
        stopTimer()
        run = null
    }

    private fun error(message: String) {
        log(message)
        runErrors++
        lastError = message
        lastErrorAt = clock()
    }

    /** Chunk timeout, cut short by the file's deadline so a trickle cannot hold the capture. */
    private fun armTransfer(t: Transfer) = arm(minOf(CHUNK_TIMEOUT_MS, maxOf(0L, t.deadline - clock())))

    private fun arm(ms: Long) {
        stopTimer()
        val gen = timerGen
        cancelTimer = timer.after(ms) { onTimeout(gen) }
    }

    private fun stopTimer() {
        cancelTimer?.invoke()
        cancelTimer = null
        timerGen++
    }

    @Synchronized
    private fun onTimeout(gen: Int) {
        if (gen != timerGen) return
        cancelTimer = null
        val r = run ?: return
        val t = r.transfer
        if (t != null && t.oversize) return blocked(r)
        if (t != null) {
            val late = clock() >= t.deadline
            skip(r, if (late) "Health file ${hex(t.id)} did not finish within ${FILE_DEADLINE_MS / 1000} s" else "Timed out waiting for health file ${hex(t.id)}")
            return
        }
        // The list timer only runs with an empty queue, so the run ends here.
        error(if (r.lists == Lists.AWAIT_TODAY) "Watch did not list health files" else "Watch did not send its past health file list")
        finish()
    }

    companion object {
        const val CHUNK_TIMEOUT_MS = 5_000L
        const val LIST_TIMEOUT_MS = 10_000L
        const val FILE_DEADLINE_MS = 120_000L
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
        /** Ids fetched in one sync; the rest stay unstored and are fetched by the next one. */
        const val MAX_FETCHES_PER_RUN = 2_000

        /** Bounds memory for distinct listed ids, stored or not. */
        const val MAX_LISTED_PER_RUN = 20_000
        const val MAX_LISTS_PER_RUN = 32

        private val PROFILE_KEYS = setOf(
            "height_cm", "weight_kg", "birthday", "gender", "goal_calories", "goal_steps", "goal_standing_hours", "goal_moving_minutes",
        )

        fun fetchToday(): ByteArray = Proto.command(8, 1, Proto.bytes(10, Proto.bytes(5, Proto.num(1, 0))))

        fun fetchPast(): ByteArray = Proto.command(8, 2)

        fun requestFile(id: ByteArray): ByteArray = Proto.command(8, 3, Proto.bytes(10, Proto.bytes(2, id)))

        fun ackFile(id: ByteArray): ByteArray = Proto.command(8, 5, Proto.bytes(10, Proto.bytes(3, id)))

        /** Validates an API profile; the result is what gets stored and sent. Throws IllegalArgumentException. */
        fun profile(input: JSONObject, today: LocalDate): JSONObject {
            for (k in input.keys()) {
                require(k in PROFILE_KEYS) { "Unknown profile field $k" }
            }
            fun whole(key: String, range: IntRange): Int {
                val d = (input.opt(key) as? Number)?.toDouble()
                require(d != null && d == Math.floor(d) && d >= range.first && d <= range.last) {
                    "$key must be a whole number from ${range.first} to ${range.last}"
                }
                return d.toInt()
            }
            val weight = (input.opt("weight_kg") as? Number)?.toDouble()
            require(weight != null && weight >= 20.0 && weight <= 300.0) { "weight_kg must be a number from 20 to 300" }
            val birthday = (input.opt("birthday") as? String)?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: DateTimeParseException) {
                    null
                }
            }
            require(birthday != null && birthday.year >= 1900 && !birthday.isAfter(today)) { "birthday must be a past date as YYYY-MM-DD" }
            val gender = input.opt("gender")
            require(gender == "male" || gender == "female") { "gender must be male or female" }
            return JSONObject()
                .put("height_cm", whole("height_cm", 50..250))
                .put("weight_kg", weight)
                .put("birthday", birthday.toString())
                .put("gender", gender)
                .put("goal_calories", whole("goal_calories", 10..5000))
                .put("goal_steps", whole("goal_steps", 100..100_000))
                .put("goal_standing_hours", whole("goal_standing_hours", 1..24))
                .put("goal_moving_minutes", whole("goal_moving_minutes", 1..1440))
        }

        /** 8/0 user info from a validated [profile]; the maximum heart rate uses the completed years on [today], as GB derives it. */
        fun userInfo(profile: JSONObject, today: LocalDate): ByteArray {
            val born = LocalDate.parse(profile.getString("birthday"))
            val age = Period.between(born, today).years
            var maxHr = (if (age <= 40) 220.0 - age else 207 - 0.7 * age).roundToInt()
            if (maxHr < 100 || maxHr > 220) maxHr = 175
            val info = Proto.join(
                Proto.u32(1, profile.getInt("height_cm")),
                Proto.float(2, profile.getDouble("weight_kg").toFloat()),
                Proto.u32(3, born.year * 10000 + born.monthValue * 100 + born.dayOfMonth),
                Proto.u32(4, if (profile.getString("gender") == "female") 2 else 1),
                Proto.u32(5, maxHr),
                Proto.u32(6, profile.getInt("goal_calories")),
                Proto.u32(7, profile.getInt("goal_steps")),
                Proto.u32(9, profile.getInt("goal_standing_hours")),
                Proto.u32(11, profile.getInt("goal_moving_minutes")),
            )
            return Proto.command(8, 0, Proto.bytes(10, Proto.bytes(1, info)))
        }

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 255) }

        fun u32le(b: ByteArray, i: Int): Long = Frames.u16(b, i).toLong() or (Frames.u16(b, i + 2).toLong() shl 16)

        private fun crc32(b: ByteArray, len: Int): Long {
            val c = CRC32()
            c.update(b, 0, len)
            return c.value
        }
    }
}
