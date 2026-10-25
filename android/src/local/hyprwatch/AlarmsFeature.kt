/*  Copyright (C) 2023-2024 José Rebelo
    Copyright (C) 2026 Gadgetbridge contributors

    Ported from Gadgetbridge's XiaomiScheduleService.java (alarm and sleep-mode commands,
    onSetAlarms, handleAlarms, alarmsEqual, handleSleepModeConfig, setSleepModeConfig) and
    wear_clock.proto. Android code removed; rewritten around the watch's alarm ids.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Watch alarms (17/0 list, 17/1 create, 17/2 edit, 17/4 delete) and the sleep-mode schedule
 * (17/8 read, 17/9 write). Reminder sync owns 17/14, 17/15, 17/17 and 17/18; this feature never
 * touches them. Every write is checked by reading the list again. Labels are not part of the
 * watch's alarm, so they live in settings, keyed by the watch's id, with the alarm's time and
 * repeat. An edit sends back the fields and repeat encoding the watch gave, except those it changes.
 *
 * The list is also read again when the watch sends one of these commands on its own (Xiaomi's
 * protocol names 17/1-17/5 add, update, remove, remove many and enable or disable), and two
 * minutes after a one-time alarm rings, since the watch turns it off then (10 and 20 minutes
 * after too while it stays on, as when snoozed). The Redmi Watch 5 Active sends none of them
 * for a change on the wrist (2026-10-25: enable, add and delete sent nothing), so the list is
 * also read every [POLL_MS] while connected and the phone is awake. A write may carry
 * `expect`, the alarm as the caller last saw it; one that changed on the watch answers 409.
 *
 * On the Redmi Watch 5 Active (firmware 2.2.105) list, edit on 17/2 and delete are verified. Create
 * stores the alarm enabled even when asked for a disabled one, so a create is matched on time,
 * repeat and smart, then disabled with an edit.
 *
 * Replies carry no request id. The watch answers in order, so per command the n-th reply of a
 * session answers the n-th send; a reply that times out stays owed and, arriving late, only updates
 * the cache. A route's whole operation belongs to the session it began in.
 */
class AlarmsFeature(
    private val replyMs: Long = 8_000,
    private val ackMs: Long = 3_000,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) : WatchFeature {
    /** [days]: 0 once, 127 daily, a Mon=1 .. Sun=64 mask for weekly, -1 for a repeat mode this app cannot write. */
    data class Spec(val hour: Int, val minute: Int, val days: Int, val enabled: Boolean, val smart: Boolean)

    /** [raw]: the alarm's details as the watch sent them. */
    class Alarm(val id: Long, val spec: Spec, val mode: Long, val raw: ByteArray)

    class AlarmList(val alarms: List<Alarm>, val max: Int) {
        fun find(id: Long): Alarm? = alarms.firstOrNull { it.id == id }
    }

    data class Sleep(val enabled: Boolean, val start: Int, val end: Int)

    private class Tickets {
        var sent = 0L
        var received = 0L

        /** The send number a route waits on, and its reply once it came. */
        var waiting = 0L
        var reply: Map<Int, List<Any>>? = null
    }

    /** [cmd] null when the watch did not answer in time; [owed] replies to earlier sends were still due when it was sent. */
    private class Answer(val cmd: Map<Int, List<Any>>?, val owed: Long)

    override val name = "alarms"
    private lateinit var host: FeatureHost
    private val lock = Object()

    // Guarded by lock.
    private var list: AlarmList? = null
    private var listReadAt: Long? = null
    private var supported: Boolean? = null
    private var listState = "not_read"
    private var sleep: Sleep? = null
    private var sleepReadAt: Long? = null
    private var sleepSupported: Boolean? = null
    private var sleepState = "not_read"

    /** Sends and replies per command this session, keyed by the subtype sent. */
    private val tickets = HashMap<Int, Tickets>()
    private var session = 0L
    private var cancelTimer: (() -> Unit)? = null

    /** The next poll of the list; guarded by [lock]. */
    private var cancelPoll: (() -> Unit)? = null
    private var cancelRingRead: (() -> Unit)? = null
    private var cancelTimeChanged: (() -> Unit)? = null

    // Settings mirror, written under labelLock, which is taken before lock, never inside it. Per id:
    // {"label", "time", "repeat"}, the alarm's time and day mask; without them until a list shows the alarm.
    @Volatile private var labels = JSONObject()
    private val labelLock = Object()

    /** Set by a restore until the first list: a label whose alarm then has another time or repeat is dropped. Guarded by lock. */
    private var checkLabels = false

    /** Label writes so far, and the write that last set each label; written under labelLock. */
    @Volatile private var labelWrites = 0L
    private val labelSetAt = HashMap<String, Long>()

    override fun install(host: FeatureHost) {
        this.host = host
        labels = JSONObject()
        // Labels saved before they carried their alarm are plain text.
        host.settings.get("labels")?.let { saved -> for (k in saved.keys()) labels.put(k, saved.optJSONObject(k) ?: JSONObject().put("label", saved.optString(k))) }
        synchronized(lock) {
            reset()
            checkLabels = host.settings.get(RESTORED)?.optBoolean("labels") == true
        }
        host.on(TYPE, GET) { cmd ->
            synchronized(lock) {
                received(GET, cmd)
                applyList(cmd)
            }
        }
        host.on(TYPE, SLEEP_GET) { cmd ->
            synchronized(lock) {
                received(SLEEP_GET, cmd)
                applySleep(cmd)
            }
        }
        // GB's proto comment makes 17/3 and 17/5 an edit request and its reply, so they count as edit
        // replies. Beyond the sends, any of these is the watch's own news of a change on the wrist.
        // One that no route waits for is read again too: if the watch never answers a write, its
        // pushes are counted as that write's late replies.
        for ((sub, sent) in listOf(CREATE to CREATE, EDIT to EDIT, 3 to EDIT, 5 to EDIT, DELETE to DELETE, SLEEP_SET to SLEEP_SET)) {
            host.on(TYPE, sub) { cmd ->
                synchronized(lock) {
                    val alarms = sub != SLEEP_SET
                    val pushed = !received(sent, cmd)
                    if (pushed) host.log("Watch sent 17/$sub on its own; reading its ${if (alarms) "alarms" else "sleep schedule"} again")
                    if (pushed || tickets.getValue(sent).waiting == 0L) {
                        if (alarms) readLater(GET, "alarm list read") else readLater(SLEEP_GET, "sleep mode read")
                    }
                }
            }
        }

        host.route("GET", "/api/alarms/sleep-mode") { sleepJson() }
        host.route("PUT", "/api/alarms/sleep-mode") { r -> putSleep(r.body) }
        host.route("POST", "/api/alarms/refresh") {
            writing { s ->
                readList(s)
                listJson()
            }
        }
        host.route("GET", "/api/alarms") { listJson() }
        host.route("POST", "/api/alarms") { r -> create(r.body) }
        host.route("PUT", "/api/alarms/{id}") { r -> edit(id(r), r.body) }
        host.route("DELETE", "/api/alarms/{id}") { r -> delete(id(r), r.body) }
        // A new time zone or clock moves the ring in phone time.
        cancelTimeChanged = host.phone.onTimeChanged { synchronized(lock) { if (host.connected) list?.let(::armRingRead) } }
    }

    override fun onConnected() {
        synchronized(lock) {
            reset()
            listState = "waiting"
            sleepState = "waiting"
            readLater(GET, "alarm list read")
            readLater(SLEEP_GET, "sleep mode read")
            poll()
        }
        cancelTimer = host.after(CONNECT_WAIT_MS) {
            synchronized(lock) {
                if (listState == "waiting") listState = "no_reply"
                if (sleepState == "waiting") sleepState = "no_reply"
            }
        }
    }

    override fun onDisconnected() {
        cancelTimer?.invoke()
        cancelTimer = null
        synchronized(lock) {
            cancelRingRead?.invoke()
            cancelRingRead = null
            cancelPoll?.invoke()
            cancelPoll = null
            reset()
            if (listState == "waiting") listState = "not_read"
            if (sleepState == "waiting") sleepState = "not_read"
        }
    }

    override fun close() {
        cancelTimeChanged?.invoke()
        synchronized(lock) {
            cancelRingRead?.invoke()
            cancelRingRead = null
            cancelPoll?.invoke()
            cancelPoll = null
        }
    }

    /** A new session: replies owed to the last one never answer this one's sends. Under [lock]. */
    private fun reset() {
        session++
        for (sub in listOf(GET, SLEEP_GET, CREATE, EDIT, DELETE, SLEEP_SET)) tickets[sub] = Tickets()
        lock.notifyAll()
    }

    override fun status(): JSONObject =
        synchronized(lock) {
            JSONObject()
                .put("alarms", list?.alarms?.size ?: JSONObject.NULL)
                .put("max_alarms", list?.max ?: JSONObject.NULL)
                .put("last_read", listReadAt ?: JSONObject.NULL)
                .put("supported", supported ?: JSONObject.NULL)
                .put("read_state", listState)
                .put("sleep_mode_supported", sleepSupported ?: JSONObject.NULL)
                .put("sleep_mode_read_state", sleepState)
                .put("edit_method", EDIT_METHOD)
        }

    // ---- Watch replies (reader thread) ----

    /** Counts a reply to [sent]; false for more replies than sends, the watch's own pushes, which answer nothing. Under [lock]. */
    private fun received(sent: Int, cmd: Map<Int, List<Any>>): Boolean {
        val t = tickets.getValue(sent)
        if (t.received == t.sent) return false
        if (++t.received == t.waiting) t.reply = cmd
        lock.notifyAll()
        return true
    }

    /** Reads the list every [POLL_MS] until the link drops. Under [lock]. */
    private fun poll() {
        cancelPoll = host.after(POLL_MS) {
            synchronized(lock) {
                if (cancelPoll != null && host.connected) {
                    readLater(GET, "alarm list poll")
                    poll()
                }
            }
        }
    }

    /** Sends a read nobody waits on, so its reply only updates the cache; none while one is owed. Under [lock]. */
    private fun readLater(sub: Int, what: String) {
        val t = tickets.getValue(sub)
        if (t.sent == t.received && host.send(Proto.command(TYPE, sub), what)) t.sent++
    }

    private fun applyList(cmd: Map<Int, List<Any>>) {
        val decoded = if (status(cmd) == 0L) decodeList(cmd) else null
        supported = decoded != null
        if (decoded == null) {
            listState = "rejected"
            return
        }
        list = decoded
        listReadAt = clock()
        listState = "read"
        armRingRead(decoded)
        // A label whose alarm is gone must not pass to a new alarm that later gets its id, even one
        // a newer list holds by the time the labels follow this list. Neither may a restored label,
        // whose id the watch may have given another alarm since the backup, so the first list after a
        // restore drops a label whose alarm has another time or repeat. Otherwise a label follows its
        // alarm's id, through edits made on the wrist or missed while the link was down.
        // labels is read before labelWrites, as setLabels needs.
        val check = checkLabels
        checkLabels = false
        val specs = decoded.alarms.associate { it.id.toString() to it.spec }
        val current = labels
        val since = labelWrites
        if (check || current.keys().asSequence().any { !matches(current.getJSONObject(it), specs[it]) }) host.submit { followLabels(specs, check, since) }
    }

    /**
     * Reads the list again [RING_READS_MS] after the next enabled one-time alarm rings, by phone
     * time, which the watch's clock follows; the watch turns such an alarm off then. An alarm that
     * rang up to the last of those ago and is still on, as when snoozed, gets the read still due. Under [lock].
     */
    private fun armRingRead(l: AlarmList) {
        cancelRingRead?.invoke()
        cancelRingRead = null
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone())
        val next =
            l.alarms.filter { it.spec.enabled && it.spec.days == 0 }.mapNotNull { a ->
                // Yesterday's ring counts too: an alarm at 23:59 is read again at 00:01.
                (-1L..1L).map { now.plusDays(it).withHour(a.spec.hour).withMinute(a.spec.minute).withSecond(0).withNano(0) }
                    .flatMap { ring -> RING_READS_MS.map { ring.plus(Duration.ofMillis(it)) } }
                    .firstOrNull { it.isAfter(now) }
            }.minOrNull() ?: return
        val s = session
        // A wakeup alarm: a timer's clock stops while the phone sleeps, so it would run hours late overnight.
        cancelRingRead = host.phone.at(next.toInstant().toEpochMilli()) { synchronized(lock) { if (session == s) readLater(GET, "alarm list read after an alarm rang") } }
    }

    private fun applySleep(cmd: Map<Int, List<Any>>) {
        val decoded = if (status(cmd) == 0L) decodeSleep(cmd) else null
        sleepSupported = decoded != null
        if (decoded == null) {
            sleepState = "rejected"
            return
        }
        sleep = decoded
        sleepReadAt = clock()
        sleepState = "read"
    }

    // ---- Sends and waits ----

    /**
     * Sends [cmd] (subtype [sub]) in session [s], under the lock so a session change cannot slip
     * between the check and the send, then waits up to [ms] for the reply that answers this send.
     */
    private fun exchange(sub: Int, s: Long, cmd: ByteArray, what: String, ms: Long): Answer {
        val t: Tickets
        val owed: Long
        synchronized(lock) {
            if (session != s) throw IOException("Watch disconnected")
            if (!host.send(cmd, what)) throw IOException(if (host.connected) "The watch link is busy; try again" else "Watch disconnected")
            t = tickets.getValue(sub)
            owed = t.sent - t.received
            t.waiting = ++t.sent
            t.reply = null
        }
        val deadline = System.nanoTime() + ms * 1_000_000
        synchronized(lock) {
            try {
                while (true) {
                    if (session != s) throw IOException("Watch disconnected")
                    t.reply?.let { return Answer(it, owed) }
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) return Answer(null, owed)
                    lock.wait(left)
                }
            } finally {
                t.waiting = 0
                t.reply = null
            }
        }
    }

    /** A reply to a read of [what], or IOException; a timeout after an earlier unanswered read needs a new session. */
    private fun read(sub: Int, s: Long, what: String): Map<Int, List<Any>> {
        val a = exchange(sub, s, Proto.command(TYPE, sub), "$what read", replyMs)
        return a.cmd ?: throw IOException(if (a.owed > 0) "The watch has not answered an earlier $what request; reconnect the watch" else "The watch did not answer the $what request")
    }

    private fun readList(s: Long): AlarmList {
        val r = read(GET, s, "alarm list")
        val st = status(r)
        if (st != 0L) throw IOException("The watch refused the alarm list (status $st)")
        return decodeList(r) ?: throw IOException("The watch did not return an alarm list")
    }

    private fun readSleep(s: Long): Sleep {
        val r = read(SLEEP_GET, s, "sleep mode")
        val st = status(r)
        if (st != 0L) throw IOException("The watch refused the sleep mode request (status $st)")
        return decodeSleep(r) ?: throw IOException("The watch did not return a sleep mode schedule")
    }

    /** Runs a write route's [block] as this feature's one operation, in the session it starts in. */
    private fun <T> writing(block: (Long) -> T): T {
        require(host.connected) { "Connect the watch first" }
        return host.operation {
            val s = synchronized(lock) {
                if (!host.connected) throw IOException("Watch disconnected")
                session
            }
            block(s)
        }
    }

    // ---- Alarm routes ----

    private fun create(body: JSONObject): JSONObject {
        val spec = parseSpec(body, null)
        val label = parseLabel(body)
        return writing { s ->
            val pre = readList(s)
            require(pre.alarms.size < pre.max) { "The watch holds at most ${pre.max} alarms; delete one first" }
            // The label follows the new id as soon as the watch holds the alarm, even if disabling it then fails.
            val (alarm, post) = createOn(spec, pre, s) { id -> if (label != null) setLabels(id to entry(label, spec)) }
            result(alarm, post).put("method", "create")
        }
    }

    private fun edit(id: Long, body: JSONObject): JSONObject {
        val expect = parseExpect(body.remove("expect"))
        require(body.length() > 0) { "Give at least one alarm field to change" }
        parseSpec(body, PLACEHOLDER)
        val label = parseLabel(body)
        return writing { s ->
            val pre = readList(s)
            checkExpect(id, pre.find(id), expect)
            val cur = pre.find(id) ?: throw IllegalArgumentException("No alarm with id $id on the watch")
            val spec = parseSpec(body, cur.spec)
            // Before the reply is built, so its alarm and list both show the label.
            // A kept label is stored again with the alarm's new time and repeat.
            val relabel = { finalId: Long ->
                val text = if (body.has("label")) label else labelOf(id) as? String
                if (body.has("label") || text != null) setLabels(finalId to text?.let { entry(it, spec) })
            }
            if (spec == cur.spec) {
                relabel(id)
                result(cur, pre).put("method", "unchanged")
            } else {
                editOn(cur, spec, pre, s, relabel)
            }
        }
    }

    private fun delete(id: Long, body: JSONObject): JSONObject {
        val expect = parseExpect(body.opt("expect"))
        return writing { s ->
            val pre = readList(s)
            checkExpect(id, pre.find(id), expect)
            require(pre.find(id) != null) { "No alarm with id $id on the watch" }
            val post = deleteOn(id, s)
            JSONObject().put("deleted", id).put("verified", true).put("alarms", alarmsJson(post))
        }
    }

    /**
     * 17/2, verified by a re-read. Edit is confirmed on the watch, so it is always tried first; only
     * when the watch leaves the alarm unchanged does this one edit fall back to delete + create.
     * [edited] gets the alarm's final id before the reply is built.
     */
    private fun editOn(cur: Alarm, spec: Spec, pre: AlarmList, s: Long, edited: (Long) -> Unit): JSONObject {
        exchange(EDIT, s, editCommand(cur.id, spec, cur), "alarm edit", ackMs)
        val post = readList(s)
        val now = post.find(cur.id)
        if (now?.spec == spec) {
            edited(now.id)
            return result(now, post).put("method", "edit")
        }
        if (now == null || now.spec != cur.spec) throw IOException("Alarm ${cur.id} changed in an unexpected way; read the alarms again")
        // A repeat mode this app cannot write was never tried on 17/1; a replace could lose the alarm.
        if (spec.days < 0) throw IOException("The watch did not apply the edit to alarm ${cur.id}")
        host.log("Alarm edit on 17/2 left the alarm unchanged; replacing it instead")
        return replaceOn(cur, spec, post, s, edited)
    }

    private fun replaceOn(cur: Alarm, spec: Spec, base: AlarmList, s: Long, edited: (Long) -> Unit): JSONObject {
        // Read before the delete: the list without cur.id prunes its label meanwhile.
        val label = labelOf(cur.id)
        val created: Alarm
        val post: AlarmList
        if (base.alarms.size < base.max) {
            created = createOn(spec, base, s, cur).first
            post =
                try {
                    deleteOn(cur.id, s)
                } catch (e: IOException) {
                    throw IOException("Alarm ${cur.id} was copied to new alarm ${created.id}, but the old one was not deleted: ${e.message}")
                }
        } else {
            val mid = deleteOn(cur.id, s)
            val pair =
                try {
                    createOn(spec, mid, s, cur)
                } catch (e: IOException) {
                    throw IOException("Alarm ${cur.id} was deleted, but its replacement was not saved: ${e.message}")
                }
            created = pair.first
            post = pair.second
        }
        (label as? String)?.let { setLabels(cur.id to null, created.id to entry(it, spec)) }
        edited(created.id)
        return result(created, post)
            .put("method", "replace")
            .put("previous_id", cur.id)
            .put("note", "The watch did not apply an in-place edit, so the alarm was deleted and re-created with a new id")
    }

    /**
     * 17/1, verified by a re-read; a new id with the same time and repeat is the new alarm. The
     * watch stores a new alarm enabled whatever 17/1 says (GB encodes `enabled` the same way, field
     * 5), so an enabled or smart flag that differs is then set with an edit on 17/2 and verified.
     * [created] gets the new id once the watch holds the alarm, before that edit. [from], the alarm
     * a replace copies, gives its repeat encoding and unknown fields.
     */
    private fun createOn(spec: Spec, pre: AlarmList, s: Long, from: Alarm? = null, created: (Long) -> Unit = {}): Pair<Alarm, AlarmList> {
        val ack = exchange(CREATE, s, Proto.command(TYPE, CREATE, Proto.bytes(SCHEDULE, Proto.bytes(2, details(spec, from)))), "alarm create", ackMs).cmd
        if (ack != null && status(ack) != 0L) throw IOException("The watch rejected the alarm (status ${status(ack)})")
        val ackId = ack?.let { Proto.number(Proto.message(it, SCHEDULE), 4, -1) }
        val post = readList(s)
        val known = pre.alarms.map { it.id }.toSet()
        val fresh = post.alarms.filter { it.id !in known && it.spec.copy(enabled = spec.enabled, smart = spec.smart) == spec }
        val alarm = fresh.firstOrNull { it.id == ackId } ?: fresh.maxByOrNull { it.id } ?: throw IOException("The watch did not save the alarm")
        created(alarm.id)
        if (alarm.spec == spec) return alarm to post
        val left =
            listOfNotNull(
                if (alarm.spec.enabled != spec.enabled) (if (spec.enabled) "disabled" else "enabled") else null,
                if (alarm.spec.smart != spec.smart) (if (spec.smart) "without smart wake" else "with smart wake") else null,
            ).joinToString(" and ")
        try {
            // As in editOn, the re-read decides; the edit's ack status is not hardware-verified.
            exchange(EDIT, s, editCommand(alarm.id, spec, alarm), "alarm edit", ackMs)
            val fixed = readList(s)
            val now = fixed.find(alarm.id)
            if (now?.spec != spec) throw IOException("the watch did not apply the edit")
            return now to fixed
        } catch (e: IOException) {
            throw IOException("Alarm ${alarm.id} was saved but left $left: ${e.message}")
        }
    }

    private fun deleteOn(id: Long, s: Long): AlarmList {
        val ack = exchange(DELETE, s, Proto.command(TYPE, DELETE, Proto.bytes(SCHEDULE, Proto.bytes(5, Proto.num(1, id)))), "alarm delete", ackMs).cmd
        if (ack != null && status(ack) != 0L) throw IOException("The watch refused to delete alarm $id (status ${status(ack)})")
        val post = readList(s)
        if (post.find(id) != null) throw IOException("The watch did not delete alarm $id")
        return post
    }

    // ---- Sleep mode ----

    private fun putSleep(body: JSONObject): JSONObject {
        for (k in body.keys()) require(k in SLEEP_FIELDS) { "Unknown sleep mode field '$k'" }
        require(body.length() > 0) { "Give enabled, start or end" }
        val enabled = body.opt("enabled")?.let { it as? Boolean ?: throw IllegalArgumentException("enabled must be true or false") }
        val start = body.opt("start")?.let(::parseTime)
        val end = body.opt("end")?.let(::parseTime)
        return writing { s ->
            val base = if (enabled != null && start != null && end != null) null else readSleep(s)
            val want = Sleep(enabled ?: base!!.enabled, start ?: base!!.start, end ?: base!!.end)
            require(want.start != want.end) { "Start and end must differ" }
            val schedule = Proto.join(Proto.bytes(1, hm(want.start)), Proto.bytes(2, hm(want.end)), Proto.num(3, 0))
            val cmd = Proto.command(TYPE, SLEEP_SET, Proto.bytes(SCHEDULE, Proto.bytes(9, Proto.join(Proto.num(1, if (want.enabled) 1 else 0), Proto.bytes(2, schedule)))))
            val ack = exchange(SLEEP_SET, s, cmd, "sleep mode write", ackMs).cmd
            if (ack != null && status(ack) != 0L) throw IOException("The watch rejected the sleep schedule (status ${status(ack)})")
            if (readSleep(s) != want) throw IOException("The watch did not apply the sleep schedule")
            sleepJson().put("verified", true)
        }
    }

    private fun sleepJson(): JSONObject =
        synchronized(lock) {
            val s = sleep
            JSONObject()
                .put("supported", sleepSupported ?: JSONObject.NULL)
                .put("enabled", s?.enabled ?: JSONObject.NULL)
                .put("start", s?.let { clockText(it.start) } ?: JSONObject.NULL)
                .put("end", s?.let { clockText(it.end) } ?: JSONObject.NULL)
                .put("last_read", sleepReadAt ?: JSONObject.NULL)
                .put("read_state", sleepState)
        }

    // ---- JSON ----

    private fun listJson(): JSONObject =
        synchronized(lock) {
            JSONObject()
                .put("alarms", list?.let(::alarmsJson) ?: JSONArray())
                .put("max_alarms", list?.max ?: JSONObject.NULL)
                .put("last_read", listReadAt ?: JSONObject.NULL)
                .put("supported", supported ?: JSONObject.NULL)
                .put("read_state", listState)
                .put("connected", host.connected)
                .put("edit_method", EDIT_METHOD)
        }

    private fun result(alarm: Alarm, post: AlarmList): JSONObject = JSONObject().put("alarm", alarmJson(alarm)).put("verified", true).put("alarms", alarmsJson(post))

    private fun alarmsJson(l: AlarmList): JSONArray = JSONArray().also { a -> l.alarms.forEach { a.put(alarmJson(it)) } }

    private fun alarmJson(a: Alarm): JSONObject {
        val days = JSONArray()
        if (a.spec.days > 0) DAYS.forEachIndexed { i, d -> if (a.spec.days and (1 shl i) != 0) days.put(d) }
        val mode =
            when (a.mode) {
                0L -> "once"
                1L -> "daily"
                4L -> "weekdays"
                5L -> "weekly"
                else -> "mode_${a.mode}"
            }
        return JSONObject()
            .put("id", a.id)
            .put("time", clockText(a.spec.hour * 60 + a.spec.minute))
            .put("repeat", days)
            .put("repeat_mode", mode)
            .put("enabled", a.spec.enabled)
            .put("smart", a.spec.smart)
            .put("label", labelOf(a.id))
    }

    private fun labelOf(id: Long): Any = labels.optJSONObject(id.toString())?.optString("label")?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL

    /** A label as stored, with the time and repeat of the alarm it belongs to. */
    private fun entry(label: String, spec: Spec): JSONObject = JSONObject().put("label", label).put("time", clockText(spec.hour * 60 + spec.minute)).put("repeat", spec.days)

    /** True when [e], a stored label, belongs to an alarm with [spec]. */
    private fun matches(e: JSONObject, spec: Spec?): Boolean =
        spec != null && e.optString("time") == clockText(spec.hour * 60 + spec.minute) && e.has("repeat") && e.getInt("repeat") == spec.days

    /**
     * Sets each id's label [entry], or removes it for null, in one settings write. [labelWrites] moves
     * before [labels], so a list that sees a label also sees the write that set it.
     */
    private fun setLabels(vararg changes: Pair<Long, JSONObject?>) {
        synchronized(labelLock) {
            val next = JSONObject(labels.toString())
            for ((id, label) in changes) {
                val key = id.toString()
                if (label == null) {
                    next.remove(key)
                    labelSetAt.remove(key)
                } else {
                    next.put(key, label)
                    labelSetAt[key] = ++labelWrites
                }
            }
            host.settings.put("labels", next)
            labels = next
        }
    }

    /**
     * Fits the labels to a list ([specs] by id) that arrived after label write [since]; a label set
     * later stays. Drops a label whose alarm the list lacked, or, when [check] (the first list after a
     * restore), has another time or repeat; otherwise stores the alarm's time and repeat with it.
     */
    private fun followLabels(specs: Map<String, Spec>, check: Boolean, since: Long) {
        synchronized(labelLock) {
            val changes = labels.keys().asSequence().filter { (labelSetAt[it] ?: 0) <= since }.mapNotNull { key ->
                val e = labels.getJSONObject(key)
                val spec = specs[key]
                when {
                    matches(e, spec) -> null
                    spec == null || (check && e.has("time")) -> key.toLong() to null
                    else -> key.toLong() to entry(e.getString("label"), spec)
                }
            }.toList()
            if (changes.isNotEmpty()) setLabels(*changes.toTypedArray())
            // After the labels, so a restart in between checks them again.
            if (check) host.settings.put(RESTORED, JSONObject())
        }
    }

    /** 409 when the alarm is gone or differs from [expect], the alarm as the caller last saw it. */
    private fun checkExpect(id: Long, cur: Alarm?, expect: JSONObject?) {
        if (expect == null) return
        if (cur == null) throw Http.Conflict("Alarm $id is no longer on the watch; read the alarms again")
        val now = alarmJson(cur)
        for (k in expect.keys()) {
            val same = if (k == "repeat") parseDays(expect.get(k)) == parseDays(now.get(k)) else expect.get(k) == now.get(k)
            if (!same) throw Http.Conflict("Alarm $id changed on the watch; read the alarms again")
        }
    }

    // ---- Input ----

    private fun id(r: ApiRequest): Long {
        val s = r.params.getValue("id")
        require(s.matches(Regex("0|[1-9][0-9]{0,9}"))) { "Invalid alarm id" }
        return s.toLong()
    }

    /** [base] fills fields the body leaves out; null means create, where only time is required. */
    private fun parseSpec(body: JSONObject, base: Spec?): Spec {
        for (k in body.keys()) require(k in FIELDS) { "Unknown alarm field '$k'" }
        val time = body.opt("time")?.let(::parseTime) ?: base?.let { it.hour * 60 + it.minute } ?: throw IllegalArgumentException("Give the alarm time as HH:MM")
        val days = body.opt("repeat")?.let(::parseDays) ?: base?.days ?: 0
        return Spec(time / 60, time % 60, days, bool(body, "enabled") ?: base?.enabled ?: true, bool(body, "smart") ?: base?.smart ?: false)
    }

    /** `expect`: some of time, repeat, repeat_mode, enabled and smart, as GET returned them. */
    private fun parseExpect(v: Any?): JSONObject? {
        if (v == null) return null
        val e = v as? JSONObject ?: throw IllegalArgumentException("expect must be an object of alarm fields")
        for (k in e.keys()) {
            when (k) {
                "time" -> parseTime(e.get(k))
                "repeat" -> parseDays(e.get(k))
                "repeat_mode" -> require(e.get(k) is String) { "expect.repeat_mode must be text" }
                "enabled", "smart" -> bool(e, k)
                else -> throw IllegalArgumentException("Unknown expect field '$k'")
            }
        }
        return e
    }

    private fun parseLabel(body: JSONObject): String? {
        if (!body.has("label") || body.isNull("label")) return null
        val l = (body.get("label") as? String ?: throw IllegalArgumentException("label must be text")).trim()
        require(l.length <= 40 && l.none(Char::isISOControl)) { "Use a label of at most 40 characters" }
        return l.ifEmpty { null }
    }

    private fun bool(body: JSONObject, key: String): Boolean? = body.opt(key)?.let { it as? Boolean ?: throw IllegalArgumentException("$key must be true or false") }

    private fun parseTime(v: Any): Int {
        val m = (v as? String)?.let(TIME::matchEntire) ?: throw IllegalArgumentException("Use a time like 07:30 (HH:MM, 24-hour)")
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    private fun parseDays(v: Any): Int {
        val a = v as? JSONArray ?: throw IllegalArgumentException("repeat must be a list of days")
        var mask = 0
        for (i in 0 until a.length()) {
            val d = DAYS.indexOf(a.opt(i) as? String)
            require(d >= 0) { "Use repeat days from ${DAYS.joinToString(", ")}" }
            mask = mask or (1 shl d)
        }
        return mask
    }

    private companion object {
        const val TYPE = 17
        const val GET = 0
        const val CREATE = 1
        const val EDIT = 2
        const val DELETE = 4
        const val SLEEP_GET = 8
        const val SLEEP_SET = 9
        const val SCHEDULE = 19
        const val DEFAULT_MAX = 10
        const val CONNECT_WAIT_MS = 10_000L
        const val POLL_MS = 5 * 60_000L

        /** After a one-time alarm's ring time: room for it to ring and be dismissed, then for snoozes. */
        val RING_READS_MS = listOf(120_000L, 600_000L, 1_200_000L)

        /** Edit on 17/2 is verified on the Redmi Watch 5 Active; kept in status and list for API clients. */
        const val EDIT_METHOD = "edit"
        val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val FIELDS = setOf("time", "repeat", "label", "smart", "enabled")
        val SLEEP_FIELDS = setOf("enabled", "start", "end")
        val TIME = Regex("([01][0-9]|2[0-3]):([0-5][0-9])")
        val PLACEHOLDER = Spec(0, 0, 0, enabled = true, smart = false)

        /** Alarm detail fields this app writes: time, repeat mode, day mask, enabled, smart. */
        val KNOWN = setOf(2, 3, 4, 5, 7)

        fun status(cmd: Map<Int, List<Any>>): Long = Proto.number(cmd, 100, 0)

        fun hm(minutes: Int): ByteArray = Proto.join(Proto.num(1, (minutes / 60).toLong()), Proto.num(2, (minutes % 60).toLong()))

        fun clockText(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

        fun minutesOf(m: Map<Int, List<Any>>): Int = (Proto.number(m, 1, 0) * 60 + Proto.number(m, 2, 0)).toInt()

        /**
         * Weekly sends the day mask; daily goes as mode 1 without flags, as GB sends it. With [base],
         * the alarm as the watch holds it, the same days go back in the watch's own encoding (so a
         * mode this app cannot write survives), and fields this app does not know are kept.
         */
        fun details(s: Spec, base: Alarm? = null): ByteArray {
            val kept = base?.let { Proto.fields(it.raw) }.orEmpty()
            fun keep(keepIf: (Int) -> Boolean) = Proto.join(*kept.filter { keepIf(it.first) }.map { it.second }.toTypedArray())
            val repeat =
                if (base != null && base.spec.days == s.days) {
                    keep { it == 3 || it == 4 }
                } else {
                    val mode = if (s.days == 0) 0L else if (s.days == 127) 1L else 5L
                    Proto.join(Proto.num(3, mode), if (mode == 5L) Proto.num(4, s.days.toLong()) else ByteArray(0))
                }
            return Proto.join(
                Proto.bytes(2, hm(s.hour * 60 + s.minute)),
                repeat,
                Proto.num(5, if (s.enabled) 1 else 0),
                Proto.num(7, if (s.smart) 1 else 2),
                keep { it !in KNOWN },
            )
        }

        fun editCommand(id: Long, s: Spec, base: Alarm): ByteArray = Proto.command(TYPE, EDIT, Proto.bytes(SCHEDULE, Proto.bytes(3, Proto.join(Proto.num(1, id), Proto.bytes(2, details(s, base))))))

        fun decodeList(cmd: Map<Int, List<Any>>): AlarmList? {
            val schedule = Proto.message(cmd, SCHEDULE)
            if (!schedule.containsKey(1)) return null
            val a = Proto.message(schedule, 1)
            val alarms =
                a[1].orEmpty().mapNotNull { raw ->
                    val info = Proto.decode(raw as? ByteArray ?: return@mapNotNull null)
                    val d = Proto.message(info, 2)
                    val time = minutesOf(Proto.message(d, 2))
                    val mode = Proto.number(d, 3, 0)
                    val days =
                        when (mode) {
                            0L -> 0
                            1L -> 127
                            4L -> 31
                            5L -> (Proto.number(d, 4, 0) and 127).toInt()
                            else -> -1
                        }
                    Alarm(Proto.number(info, 1, -1), Spec(time / 60, time % 60, days, Proto.number(d, 5, 0) != 0L, Proto.number(d, 7, 2) == 1L), mode, Proto.blob(info, 2))
                }
            return AlarmList(alarms.sortedBy { it.id }, Proto.number(a, 2, 0).toInt().takeIf { it > 0 } ?: DEFAULT_MAX)
        }

        fun decodeSleep(cmd: Map<Int, List<Any>>): Sleep? {
            val schedule = Proto.message(cmd, SCHEDULE)
            if (!schedule.containsKey(9)) return null
            val m = Proto.message(schedule, 9)
            val s = Proto.message(m, 2)
            return Sleep(Proto.number(m, 1, 0) != 0L, minutesOf(Proto.message(s, 1)), minutesOf(Proto.message(s, 2)))
        }
    }
}

/** The alarms feature's restore marker: `{"labels": true}` until its first list, then `{}`. */
private const val RESTORED = "restored"

/**
 * For a restore, on the backup's copy of health.db, through [put], a settings write: a restored label's
 * id may since belong to another alarm, so the labels are checked against the first list read.
 */
fun markAlarmsRestored(put: (key: String, value: String) -> Unit) = put("feature.alarms.$RESTORED", JSONObject().put("labels", true).toString())
