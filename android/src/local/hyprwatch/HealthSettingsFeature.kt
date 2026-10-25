/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker (Gadgetbridge XiaomiHealthService)

    Ported from Gadgetbridge, which is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option) any later version. */
package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The watch's health settings (type 8 config GET/SET): heart rate monitoring and alerts, SpO2,
 * sedentary reminder, stress, goal notifications, vitality score and the secondary goal. Reads every
 * block on connect, keeps the watch's last reply per block, and writes an edit by changing only the
 * edited fields of that reply, then reads the block again to verify it. Until that read answers, the
 * cached block may predate the write, so another edit of the block is refused, and asks for the
 * block again; so does a read-back still missing after [reconcileMs]. Replies carry no request id
 * and the watch answers in order, so per block the n-th reply answers the n-th read: a reply to a
 * read sent before the latest write neither confirms it nor replaces the cache. A read-back still
 * missing at [reconcileMs] counts as lost, and a reply beyond the reads sent answers the latest.
 */
class HealthSettingsFeature(
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long PUT waits for the verifying re-read; 0 returns at once with `verified` null. */
    private val verifyWaitMs: Long = 4_000,
    private val reconcileMs: Long = 10_000,
) : WatchFeature {
    override val name = "watch_settings"
    private lateinit var host: FeatureHost

    private val lock = ReentrantLock()
    private val replied = lock.newCondition()

    // All of this is for the current connection only: a write needs a block read on this connection.
    private val raw = HashMap<Block, Pb>()
    private val state = HashMap<Block, String>()
    private val readAt = HashMap<Block, Long>()
    private val unsent = ArrayDeque<Block>()
    private val expected = HashMap<Block, Map<String, Any?>>()
    private val verified = LinkedHashMap<Block, Boolean?>()

    /** Per block, reads sent and the reads answered on this connection; answered never exceeds sent. */
    private val readsSent = HashMap<Block, Int>()
    private val readsAnswered = HashMap<Block, Int>()

    /** Blocks written whose read-back has not answered yet -> reads sent before the write; only a later reply confirms it. */
    private val unconfirmed = HashMap<Block, Int>()
    private var epoch = 0L

    override fun install(host: FeatureHost) {
        this.host = host
        for (b in Block.entries) {
            host.on(TYPE, b.get) { cmd -> onRead(b, cmd) }
            host.on(TYPE, b.set) { cmd -> onWriteReply(b, cmd) }
        }
        host.onAcked { flush() }
        host.route("GET", "/api/watch-settings") { lock.withLock { snapshot() } }
        host.route("PUT", "/api/watch-settings") { r -> host.operation { write(r.body) } }
    }

    override fun onConnected() {
        lock.withLock {
            reset()
            unsent.addAll(Block.entries)
        }
        flush()
    }

    override fun onDisconnected() = lock.withLock { reset() }

    override fun status(): JSONObject = lock.withLock {
        JSONObject().put("read_at", iso(readAt.values.maxOrNull())).put("supported_count", state.values.count { it == SUPPORTED })
    }

    private fun reset() {
        epoch++
        raw.clear()
        state.clear()
        readAt.clear()
        unsent.clear()
        expected.clear()
        verified.clear()
        readsSent.clear()
        readsAnswered.clear()
        unconfirmed.clear()
        replied.signalAll()
    }

    /** Sends the queued reads in order; a refusal waits for the next ack. */
    private fun flush() = lock.withLock {
        while (unsent.isNotEmpty()) {
            if (!sendRead(unsent.first())) return@withLock
            unsent.removeFirst()
        }
    }

    /** Under [lock]: reads [b] again, or queues the read behind those already waiting for room. */
    private fun reread(b: Block) {
        if (b in unsent) return
        if (unsent.isNotEmpty() || !sendRead(b)) unsent.addLast(b)
    }

    /** Under [lock]. */
    private fun sendRead(b: Block): Boolean = host.send(read(b), "watch settings read").also { if (it) readsSent.merge(b, 1, Int::plus) }

    private fun onRead(b: Block, cmd: Map<Int, List<Any>>) {
        val status = Proto.number(cmd, 100, 0)
        val body = (Proto.first(cmd, 10) as? ByteArray)?.let(Proto::decode)?.let { Proto.first(it, b.field) as? ByteArray }
        lock.withLock {
            val n = minOf((readsAnswered[b] ?: 0) + 1, readsSent[b] ?: 0)
            readsAnswered[b] = n
            // Answers a read sent before the latest write: it shows the block before that write.
            if (n <= (unconfirmed[b] ?: 0)) return host.log("Ignored a ${b.key} settings reply that predates the last write")
            if (status == 0L && body != null) {
                raw[b] = Pb.parse(body)
                state[b] = SUPPORTED
            } else {
                raw.remove(b)
                state[b] = UNSUPPORTED
                host.log("Watch has no ${b.key} settings (status $status)")
            }
            readAt[b] = clock()
            unconfirmed.remove(b)
            expected.remove(b)?.let { want ->
                val got = raw[b]
                verified[b] = got != null && want.all { (k, v) -> FIELDS.getValue(k).read(got) == v }
                replied.signalAll()
            }
        }
    }

    private fun onWriteReply(b: Block, cmd: Map<Int, List<Any>>) {
        val status = Proto.number(cmd, 100, 0)
        if (status == 0L) return
        host.log("Watch rejected the ${b.key} settings with status $status")
        lock.withLock {
            if (expected.remove(b) != null) {
                verified[b] = false
                replied.signalAll()
            }
        }
    }

    private fun write(body: JSONObject): JSONObject {
        val edits = LinkedHashMap<Block, Pb>()
        val wants = HashMap<Block, Map<String, Any?>>()
        val e = lock.withLock {
            require(host.connected) { "Connect the watch first" }
            require(body.length() > 0) { "Send at least one setting" }
            val keys = body.keys().asSequence().toList().sorted()
            for (key in keys) {
                val f = FIELDS[key] ?: throw IllegalArgumentException("Unknown watch setting $key")
                val base = raw[f.block] ?: throw IllegalArgumentException("The watch has not reported its ${f.block.key} settings on this connection")
                f.write(edits.getOrPut(f.block) { base.copy() }, body.get(key))
            }
            edits.keys.firstOrNull { it in unconfirmed.keys }?.let {
                // Its read-back may be lost: ask again, so the block unlocks when the watch answers.
                reread(it)
                throw IllegalArgumentException("The watch has not confirmed the last ${it.key} change; try again shortly")
            }
            for ((b, pb) in edits) {
                b.complete(pb)
                wants[b] = keys.filter { FIELDS.getValue(it).block == b }.associateWith { FIELDS.getValue(it).read(pb) }
            }
            epoch
        }
        // Each block goes out under the lock, so a reconnect lands before or after it, never between the check and the send.
        for ((b, pb) in edits) {
            lock.withLock {
                if (e != epoch) throw IOException("The watch disconnected during the write")
                if (!host.send(Proto.command(TYPE, b.set, Proto.bytes(10, Proto.bytes(b.field, pb.encode()))), "watch settings write")) {
                    throw IOException("Could not send the ${b.key} settings to the watch; try again")
                }
                expected[b] = wants.getValue(b)
                verified[b] = null
                unconfirmed[b] = readsSent[b] ?: 0
                reread(b)
            }
            host.after(reconcileMs) {
                lock.withLock {
                    if (e == epoch && b in unconfirmed.keys) {
                        // Unanswered reads so far are lost: the next reply answers this one.
                        readsAnswered[b] = readsSent[b] ?: 0
                        reread(b)
                    }
                }
            }
        }
        return lock.withLock {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(verifyWaitMs)
            while (e == epoch && edits.keys.any { it in expected }) {
                val left = deadline - System.nanoTime()
                if (left <= 0) break
                replied.awaitNanos(left)
            }
            if (e != epoch) throw IOException("The watch disconnected during the write")
            snapshot()
        }
    }

    private fun snapshot(): JSONObject {
        val settings = JSONObject()
        for ((k, f) in FIELDS) settings.put(k, raw[f.block]?.let { f.read(it) } ?: JSONObject.NULL)
        val supported = JSONObject()
        for (b in Block.entries) supported.put(b.key, state[b] ?: "unknown")
        val options = JSONObject()
            .put("spo2_mode", raw[Block.SPO2]?.let { JSONArray(spo2Modes(it)) } ?: JSONObject.NULL)
            .put("secondary_goal", raw[Block.GOALS]?.let { JSONArray(secondaryGoals(it)) } ?: JSONObject.NULL)
        val v = JSONObject()
        for ((b, ok) in verified) v.put(b.key, ok ?: JSONObject.NULL)
        return JSONObject()
            .put("settings", settings)
            .put("supported", supported)
            .put("options", options)
            .put("verified", v)
            .put("read_at", iso(readAt.values.maxOrNull()))
    }

    private fun iso(ms: Long?): Any = ms?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL

    private enum class Block(val key: String, val get: Int, val set: Int, val field: Int) {
        SPO2("spo2", 8, 9, 7),
        HEART_RATE("heart_rate", 10, 11, 8),
        SEDENTARY("sedentary_reminder", 12, 13, 9),
        STRESS("stress", 14, 15, 10),
        GOAL_NOTIFICATION("goal_notification", 21, 22, 13),
        GOALS("goals", 42, 43, 38),
        VITALITY("vitality_score", 35, 36, 14);

        /** Constants GB always writes, added only when the watch's reply lacked them. */
        fun complete(pb: Pb) {
            when (this) {
                HEART_RATE -> if (pb.num(7) == null) pb.set(7, 1)
                GOAL_NOTIFICATION -> if (pb.num(2) == null) pb.set(2, 1)
                STRESS -> pb.msg(2)?.let { if (it.num(2) == null) pb.set(2, it.set(2, 0)) }
                else -> {}
            }
        }
    }

    private class Field(val block: Block, val read: (Pb) -> Any?, val write: (Pb, Any?) -> Unit)

    /** Protobuf fields in wire order, keeping unknown fields and wire types so a reply re-encodes as it came. */
    private class Pb(private val entries: MutableList<Entry>) {
        class Entry(val field: Int, val wire: Int, val n: Long, val b: ByteArray)

        fun copy() = Pb(ArrayList(entries))

        fun num(f: Int): Long? = entries.firstOrNull { it.field == f && it.wire == 0 }?.n

        fun msg(f: Int): Pb? = entries.firstOrNull { it.field == f && it.wire == 2 }?.let { parse(it.b) }

        fun msgs(f: Int): List<Pb> = entries.filter { it.field == f && it.wire == 2 }.map { parse(it.b) }

        fun set(f: Int, n: Long): Pb = replace(f, listOf(Entry(f, 0, n, EMPTY)))

        fun set(f: Int, m: Pb): Pb = replace(f, listOf(Entry(f, 2, 0, m.encode())))

        fun setAll(f: Int, ms: List<Pb>): Pb = replace(f, ms.map { Entry(f, 2, 0, it.encode()) })

        /** Puts [with] where [f] first appeared, or in field-number order when it is new. */
        private fun replace(f: Int, with: List<Entry>): Pb {
            val first = entries.indexOfFirst { it.field == f }
            entries.removeAll { it.field == f }
            val at = if (first >= 0) first else entries.indexOfFirst { it.field > f }.let { if (it < 0) entries.size else it }
            entries.addAll(at, with)
            return this
        }

        fun encode(): ByteArray = Proto.join(*entries.map {
            when (it.wire) {
                0 -> Proto.num(it.field, it.n)
                2 -> Proto.bytes(it.field, it.b)
                else -> Proto.join(Proto.vint((it.field.toLong() shl 3) or it.wire.toLong()), it.b)
            }
        }.toTypedArray())

        companion object {
            val EMPTY = ByteArray(0)

            fun new() = Pb(ArrayList())

            fun parse(b: ByteArray): Pb {
                require(b.size <= 65536) { "Oversize protobuf" }
                val out = ArrayList<Entry>()
                var p = 0
                fun varint(): Long {
                    var v = 0L
                    var shift = 0
                    while (true) {
                        require(p < b.size && shift < 64) { "Truncated varint" }
                        val x = b[p++].toInt() and 255
                        v = v or ((x and 127).toLong() shl shift)
                        if (x and 128 == 0) return v
                        shift += 7
                    }
                }
                while (p < b.size) {
                    val tag = varint()
                    val f = (tag ushr 3).toInt()
                    val wire = (tag and 7).toInt()
                    require(f >= 1) { "Invalid field" }
                    if (wire == 0) {
                        out.add(Entry(f, 0, varint(), EMPTY))
                        continue
                    }
                    val len = when (wire) {
                        1 -> 8L
                        2 -> varint()
                        5 -> 4L
                        else -> throw IllegalArgumentException("Unsupported wire type")
                    }
                    require(len >= 0 && len <= b.size - p) { "Truncated protobuf" }
                    out.add(Entry(f, wire, 0, b.copyOfRange(p, p + len.toInt())))
                    p += len.toInt()
                }
                return Pb(out)
            }
        }
    }

    private companion object {
        const val TYPE = 8
        const val SUPPORTED = "supported"
        const val UNSUPPORTED = "unsupported"
        val HR_INTERVALS = mapOf("1min" to 1L, "10min" to 10L, "30min" to 30L)
        val GOAL_IDS = mapOf("moving" to 3L, "standing" to 4L)
        val TIME = Regex("([01][0-9]|2[0-3]):([0-5][0-9])")

        fun read(b: Block) = Proto.command(TYPE, b.get)

        fun flag(v: Long?) = v != null && v != 0L

        fun bool(key: String, v: Any?): Long = when (v) {
            true -> 1
            false -> 0
            else -> throw IllegalArgumentException("$key must be true or false")
        }

        fun choice(key: String, v: Any?, allowed: Collection<Int>): Long {
            val n = (v as? Number)?.toDouble()
            require(n != null && n == Math.rint(n) && n.toInt() in allowed) { "$key must be one of ${allowed.joinToString(", ")}" }
            return n.toLong()
        }

        fun word(key: String, v: Any?, allowed: Collection<String>): String {
            require(allowed.isNotEmpty()) { "The watch does not offer $key" }
            require(v is String && v in allowed) { "$key must be one of ${allowed.joinToString(", ")}" }
            return v
        }

        fun spo2Modes(pb: Pb): List<String> = listOfNotNull(
            "off",
            "sleep".takeIf { flag(pb.num(3)) },
            "all_day".takeIf { flag(pb.num(1)) },
        )

        fun goalIds(pb: Pb, f: Int) = pb.msgs(f).mapNotNull { it.num(1) }

        fun secondaryGoals(pb: Pb): List<String> = GOAL_IDS.filterValues { it in goalIds(pb, 2) }.keys.toList()

        /** A sub-message field; [inner] is edited on the watch's copy, or on a new one. */
        fun nested(pb: Pb, f: Int, inner: (Pb) -> Unit) {
            pb.set(f, (pb.msg(f) ?: Pb.new()).also(inner))
        }

        fun boolField(block: Block, f: Int, key: String) = Field(block, { flag(it.num(f)) }) { pb, v -> pb.set(f, bool(key, v)) }

        /** 0 unsupported, 1 on, 2 off: writable only when the watch offers it. */
        fun triField(f: Int, key: String) = Field(Block.HEART_RATE, {
            when (it.num(f)) {
                null, 0L -> null
                1L -> true
                else -> false
            }
        }) { pb, v ->
            require(flag(pb.num(f))) { "The watch does not offer $key" }
            pb.set(f, if (bool(key, v) == 1L) 1 else 2)
        }

        /** An alert threshold where 0 turns the alert off and keeps the watch's last threshold. */
        fun alarm(block: Block, msgField: Int?, on: Int, threshold: Int, key: String, allowed: List<Int>) = Field(block, { pb ->
            val m = if (msgField == null) pb else pb.msg(msgField)
            if (m != null && flag(m.num(on))) (m.num(threshold) ?: 0L).toInt() else 0
        }) { pb, v ->
            val n = choice(key, v, allowed)
            val edit = { m: Pb -> if (n == 0L) m.set(on, 0) else m.set(on, 1).set(threshold, n) }
            if (msgField == null) edit(pb) else nested(pb, msgField) { edit(it) }
        }

        fun timeField(f: Int, key: String) = Field(Block.SEDENTARY, { pb ->
            pb.msg(f)?.let { "%02d:%02d".format(it.num(1) ?: 0L, it.num(2) ?: 0L) }
        }) { pb, v ->
            val m = (v as? String)?.let(TIME::matchEntire) ?: throw IllegalArgumentException("$key must be a time HH:MM")
            nested(pb, f) { it.set(1, m.groupValues[1].toLong()).set(2, m.groupValues[2].toLong()) }
        }

        val FIELDS: Map<String, Field> = linkedMapOf(
            "hr_interval" to Field(Block.HEART_RATE, { pb ->
                val n = pb.num(2) ?: 0L
                if (flag(pb.num(1))) "off" else if (n == 0L) "smart" else "${n}min"
            }) { pb, v ->
                when (val w = word("hr_interval", v, listOf("off", "smart") + HR_INTERVALS.keys)) {
                    // Off keeps the interval, so turning monitoring back on on the watch restores it.
                    "off" -> pb.set(1, 1)
                    "smart" -> pb.set(1, 0).set(2, 0)
                    else -> pb.set(1, 0).set(2, HR_INTERVALS.getValue(w))
                }
            },
            "hr_high_alert" to alarm(Block.HEART_RATE, null, 3, 4, "hr_high_alert", listOf(0, 100, 110, 120, 130, 140, 150)),
            "hr_low_alert" to alarm(Block.HEART_RATE, 8, 1, 2, "hr_low_alert", listOf(0, 40, 45, 50)),
            "sleep_advanced_monitoring" to Field(Block.HEART_RATE, { flag(it.msg(5)?.num(1)) }) { pb, v ->
                val n = bool("sleep_advanced_monitoring", v)
                nested(pb, 5) { it.set(1, n) }
            },
            "sleep_breathing_quality" to triField(9, "sleep_breathing_quality"),
            "irregular_rhythm_alert" to triField(10, "irregular_rhythm_alert"),
            "sleep_respiratory_rate" to triField(11, "sleep_respiratory_rate"),
            "spo2_mode" to Field(Block.SPO2, { pb ->
                when (pb.num(2) ?: 0L) {
                    0L -> "off"
                    1L -> "sleep"
                    2L -> "all_day"
                    else -> null
                }
            }) { pb, v ->
                pb.set(2, listOf("off", "sleep", "all_day").indexOf(word("spo2_mode", v, spo2Modes(pb))).toLong())
            },
            "spo2_low_alert" to alarm(Block.SPO2, 4, 1, 2, "spo2_low_alert", listOf(0, 80, 85, 90)),
            "sedentary_reminder" to boolField(Block.SEDENTARY, 1, "sedentary_reminder"),
            "sedentary_start" to timeField(2, "sedentary_start"),
            "sedentary_end" to timeField(3, "sedentary_end"),
            "sedentary_quiet" to boolField(Block.SEDENTARY, 4, "sedentary_quiet"),
            "sedentary_quiet_start" to timeField(6, "sedentary_quiet_start"),
            "sedentary_quiet_end" to timeField(7, "sedentary_quiet_end"),
            "stress_auto" to boolField(Block.STRESS, 1, "stress_auto"),
            "relax_reminder" to Field(Block.STRESS, { flag(it.msg(2)?.num(1)) }) { pb, v ->
                val n = bool("relax_reminder", v)
                nested(pb, 2) { it.set(1, n) }
            },
            "goal_notifications" to boolField(Block.GOAL_NOTIFICATION, 1, "goal_notifications"),
            "vitality_seven_day" to boolField(Block.VITALITY, 1, "vitality_seven_day"),
            "vitality_daily_progress" to boolField(Block.VITALITY, 2, "vitality_daily_progress"),
            "secondary_goal" to Field(Block.GOALS, { pb ->
                val current = goalIds(pb, 1)
                GOAL_IDS.entries.firstOrNull { it.value in current }?.key
            }) { pb, v ->
                val id = GOAL_IDS.getValue(word("secondary_goal", v, secondaryGoals(pb)))
                val keep = pb.msgs(1).filter { it.num(1) != 3L && it.num(1) != 4L }
                pb.setAll(1, keep + Pb.new().set(1, id))
            },
        )
    }
}
