package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * AlarmsFeature through FeatureRig. Write routes block for the watch's replies, so each runs on its
 * own thread while the test plays the watch: it waits for each command, checks its bytes, answers.
 */
object AlarmsFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private const val LIST = "08111000"
    private const val SLEEP = "08111008"

    // 07:30 Mon-Fri, enabled, normal (the spec's example).
    private const val CREATE_WORK = "081110019a0110120e12040807101e1805201f28013802"

    // 23:59 once, disabled, normal.
    private const val CREATE_ONCE = "081110019a010e120c12040817103b180028003802"

    // Alarm 1 -> 06:15 daily, enabled, smart.
    private const val EDIT_1 = "081110029a01121a100801120c12040806100f180128013801"
    private const val CREATE_DAILY_SMART = "081110019a010e120c12040806100f180128013801"
    private const val DELETE_1 = "081110049a01042a020801"

    // 06:00 once, enabled, smart; then alarm 2 -> the same.
    private const val CREATE_SMART = "081110019a010e120c120408061000180028013801"
    private const val EDIT_2_SMART = "081110029a01121a100802120c120408061000180028013801"
    private const val DELETE_2 = "081110049a01042a020802"

    // The hardware run's request: 06:43 Sundays, disabled, normal. Then alarm 2 -> the same, disabled (17/2).
    private const val CREATE_SUN_OFF = "081110019a0110120e12040806102b1805204028003802"
    private const val EDIT_2_SUN_OFF = "081110029a01141a120802120e12040806102b1805204028003802"

    // Alarm 2 -> 07:30 Mon-Fri, enabled / disabled, normal.
    private const val EDIT_2_WORK = "081110029a01141a120802120e12040807101e1805201f28013802"
    private const val EDIT_2_WORK_OFF = "081110029a01141a120802120e12040807101e1805201f28003802"

    // Sleep mode on, 22:00-06:30.
    private const val SLEEP_SET = "081110099a01144a120801120e0a040816100012040806101e1800"

    private fun hm(h: Int, m: Int) = Proto.join(Proto.num(1, h.toLong()), Proto.num(2, m.toLong()))

    private fun alarm(id: Long, h: Int, m: Int, mode: Long, flags: Long?, enabled: Boolean, smart: Long): ByteArray =
        Proto.join(
            Proto.num(1, id),
            Proto.bytes(
                2,
                Proto.join(
                    Proto.bytes(2, hm(h, m)),
                    Proto.num(3, mode),
                    flags?.let { Proto.num(4, it) } ?: ByteArray(0),
                    Proto.num(5, if (enabled) 1 else 0),
                    Proto.num(7, smart),
                ),
            ),
        )

    /** An alarm whose details are [details] as the watch sent them. */
    private fun alarmOf(id: Long, details: ByteArray): ByteArray = Proto.join(Proto.num(1, id), Proto.bytes(2, details))

    private val WORK1 = alarm(1, 7, 30, 5, 31, true, 2)
    private val DAILY1 = alarm(1, 6, 15, 1, null, true, 1)
    private val DAILY2 = alarm(2, 6, 15, 1, null, true, 1)
    private val WORK2 = alarm(2, 7, 30, 5, 31, true, 2)

    // What firmware 2.2.105 stored for CREATE_SUN_OFF: the alarm, but enabled.
    private val SUN2_ON = alarm(2, 6, 43, 5, 64, true, 2)
    private val SUN2_OFF = alarm(2, 6, 43, 5, 64, false, 2)

    private fun list(max: Long, vararg alarms: ByteArray): ByteArray =
        Proto.command(17, 0, Proto.bytes(19, Proto.bytes(1, Proto.join(*alarms.map { Proto.bytes(1, it) }.toTypedArray(), Proto.num(2, max)))))

    private fun sleep(enabled: Boolean, sh: Int, sm: Int, eh: Int, em: Int): ByteArray =
        Proto.command(17, 8, Proto.bytes(19, Proto.bytes(9, Proto.join(Proto.num(1, if (enabled) 1 else 0), Proto.bytes(2, Proto.join(Proto.bytes(1, hm(sh, sm)), Proto.bytes(2, hm(eh, em))))))))

    private fun ack(sub: Int, status: Long = 0, id: Long? = null): ByteArray =
        Proto.command(17, sub, Proto.bytes(19, id?.let { Proto.num(4, it) } ?: ByteArray(0)), Proto.num(100, status))

    /** Plays the watch for one blocking route call. */
    private class Watch(val rig: FeatureRig) {
        private var seen = rig.watch.sent.size

        fun expect(hex: String, reply: ByteArray? = null) {
            val deadline = System.currentTimeMillis() + 5000
            while (rig.watch.sent.size <= seen) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("Nothing sent; expected $hex")
                Thread.sleep(2)
            }
            val got = HealthCapture.hex(rig.watch.sent[seen++])
            check(got == hex, "Sent $got, expected $hex")
            if (reply != null) rig.receive(reply)
        }

        fun quiet(ms: Long = 150) {
            Thread.sleep(ms)
            check(rig.watch.sent.size == seen, "Nothing more is sent")
        }
    }

    private fun start(rig: FeatureRig, method: String, target: String, body: JSONObject = JSONObject()): () -> Pair<Int, JSONObject> {
        var out: Pair<Int, JSONObject>? = null
        var err: Throwable? = null
        val t = Thread {
            try {
                out = rig.call(method, target, body)
            } catch (e: Throwable) {
                err = e
            }
        }
        t.start()
        return {
            t.join(15_000)
            check(!t.isAlive, "$method $target finished")
            err?.let { throw it }
            out!!
        }
    }

    private fun bad(rig: FeatureRig, method: String, target: String, body: JSONObject, error: String) {
        val before = rig.watch.sent.size
        val (code, json) = rig.call(method, target, body)
        check(code == 400 && json.getString("error") == error, "$method $target $body -> 400 '$error', got $code $json")
        check(rig.watch.sent.size == before, "A rejected request sends nothing")
    }

    private fun days(vararg d: String) = JSONArray(d.toList())

    /** A label as the feature stores it, with its alarm's time and day mask. */
    private fun stored(label: String, time: String, repeat: Int) = JSONObject().put("label", label).put("time", time).put("repeat", repeat)

    @JvmStatic
    fun main(args: Array<String>) {
        connectAndRead()
        createAndValidate()
        createDisabled()
        editInPlace()
        editFallsBackToReplace()
        deleteRoute()
        sleepMode()
        missingReplies()
        lateReplies()
        reconnectMidOperation()
        disconnectAndBusy()
        watchPushes()
        readAfterRing()
        pollWhileConnected()
        readAfterSnooze()
        readAfterTimeChange()
        staleWrites()
        editKeepsWatchFields()
        createKeepsUnsupportedSmart()
        labelsFollowTheWatch()
        labelsPrunedByTheirList()
        labelsFollowTheirAlarm()
        labelsCheckedAfterRestore()
        println("Alarms feature: $assertions assertions passed")
    }

    private fun connectAndRead() {
        var now = 1000L
        val rig = FeatureRig(AlarmsFeature(3000, 3000) { now })
        val subs = rig.watch.handlers.keys.filter { it.first == 17 }.map { it.second }.toSet()
        check(subs == setOf(0, 1, 2, 3, 4, 5, 8, 9), "Handles alarm and sleep subtypes only, got $subs")

        val s0 = rig.status().getJSONObject("alarms")
        check(s0.isNull("alarms") && s0.isNull("last_read") && s0.isNull("supported") && s0.isNull("sleep_mode_supported"), "Unknown before any read")

        rig.connect()
        check(rig.take() == listOf(LIST, SLEEP), "Connect reads the alarm list and the sleep schedule")
        check(rig.status().getJSONObject("alarms").getString("read_state") == "waiting", "Waiting for the reply")

        now = 5000L
        rig.receive(list(10, WORK1))
        rig.receive(sleep(true, 22, 0, 6, 30))
        val s = rig.status().getJSONObject("alarms")
        check(s.getInt("alarms") == 1 && s.getLong("last_read") == 5000L && s.getBoolean("supported") && s.getBoolean("sleep_mode_supported"), "Status after replies: $s")
        check(s.getInt("max_alarms") == 10 && s.getString("edit_method") == "edit", "Max and edit method: edit is confirmed on this watch")

        val (code, body) = rig.call("GET", "/api/alarms")
        val a = body.getJSONArray("alarms").getJSONObject(0)
        check(code == 200 && body.getBoolean("supported") && body.getLong("last_read") == 5000L && body.getBoolean("connected"), "GET returns the cache")
        check(a.getLong("id") == 1L && a.getString("time") == "07:30" && a.getJSONArray("repeat").similar(days("mon", "tue", "wed", "thu", "fri")), "Alarm decoded: $a")
        check(a.getString("repeat_mode") == "weekly" && a.getBoolean("enabled") && !a.getBoolean("smart") && a.isNull("label"), "Alarm flags: $a")

        val sl = rig.call("GET", "/api/alarms/sleep-mode").second
        check(sl.getBoolean("supported") && sl.getBoolean("enabled") && sl.getString("start") == "22:00" && sl.getString("end") == "06:30", "Sleep schedule cached: $sl")

        // An unsolicited list (e.g. after a change on the watch) refreshes the cache.
        now = 6000L
        rig.receive(list(10, WORK1, alarm(3, 9, 0, 0, null, false, 1)))
        val two = rig.call("GET", "/api/alarms").second.getJSONArray("alarms")
        check(two.length() == 2 && two.getJSONObject(1).getString("repeat_mode") == "once" && two.getJSONObject(1).getJSONArray("repeat").length() == 0, "Unsolicited list cached")
        check(two.getJSONObject(1).getBoolean("smart") && !two.getJSONObject(1).getBoolean("enabled"), "Once alarm flags")
        val odd = alarm(4, 8, 0, 7, null, true, 2)
        rig.receive(list(10, odd))
        check(rig.call("GET", "/api/alarms").second.getJSONArray("alarms").getJSONObject(0).getString("repeat_mode") == "mode_7", "Unknown repeat mode is reported raw")

        // A rejected list marks alarms unsupported and keeps the old cache.
        rig.receive(Proto.command(17, 0, Proto.num(100, 3)))
        val r = rig.status().getJSONObject("alarms")
        check(!r.getBoolean("supported") && r.getString("read_state") == "rejected" && r.getInt("alarms") == 1, "Rejected list: $r")
        rig.receive(Proto.command(17, 8))
        check(!rig.status().getJSONObject("alarms").getBoolean("sleep_mode_supported"), "Sleep reply without the schedule is unsupported")

        // A stale reply to an earlier read does not answer a newer one.
        rig.disconnect()
        rig.connect()
        rig.take()
        val w = Watch(rig)
        val refresh = start(rig, "POST", "/api/alarms/refresh")
        w.expect(LIST)
        rig.receive(list(10))
        Thread.sleep(100)
        rig.receive(list(10, WORK1))
        val (rc, rb) = refresh()
        check(rc == 200 && rb.getJSONArray("alarms").length() == 1, "Refresh returns the reply to its own read: $rb")
    }

    private fun createAndValidate() {
        val rig = FeatureRig(AlarmsFeature(3000, 3000))
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30"), "Connect the watch first")
        rig.connect()
        rig.receive(list(2))
        rig.receive(sleep(false, 22, 0, 6, 0))
        rig.take()

        bad(rig, "POST", "/api/alarms", JSONObject(), "Give the alarm time as HH:MM")
        for (t in listOf("7:30", "24:00", "12:60", "0730")) bad(rig, "POST", "/api/alarms", JSONObject().put("time", t), "Use a time like 07:30 (HH:MM, 24-hour)")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", 730), "Use a time like 07:30 (HH:MM, 24-hour)")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30").put("repeat", days("monday")), "Use repeat days from mon, tue, wed, thu, fri, sat, sun")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30").put("repeat", "mon"), "repeat must be a list of days")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30").put("enabled", "yes"), "enabled must be true or false")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30").put("smart", 1), "smart must be true or false")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30").put("snooze", 5), "Unknown alarm field 'snooze'")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30").put("label", "x".repeat(41)), "Use a label of at most 40 characters")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30").put("label", 5), "label must be text")

        val w = Watch(rig)
        val created = start(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30").put("repeat", days("fri", "mon", "tue", "wed", "thu")).put("label", " Work "))
        w.expect(LIST, list(2))
        w.expect(CREATE_WORK, ack(1, id = 1))
        w.expect(LIST, list(2, WORK1))
        val (code, body) = created()
        val a = body.getJSONObject("alarm")
        check(code == 200 && body.getBoolean("verified") && body.getString("method") == "create", "Create verified: $body")
        check(a.getLong("id") == 1L && a.getString("label") == "Work" && a.getString("time") == "07:30", "Created alarm: $a")
        check(JSONObject(rig.settings.values.getValue("feature.alarms.labels")).similar(JSONObject().put("1", stored("Work", "07:30", 31))), "Label stored under the watch id")
        check(rig.call("GET", "/api/alarms").second.getJSONArray("alarms").getJSONObject(0).getString("label") == "Work", "GET shows the label")

        val once = start(rig, "POST", "/api/alarms", JSONObject().put("time", "23:59").put("enabled", false))
        w.expect(LIST, list(2, WORK1))
        w.expect(CREATE_ONCE, ack(1))
        w.expect(LIST, list(2, WORK1, alarm(2, 23, 59, 0, null, false, 2)))
        val ob = once().second
        check(ob.getJSONObject("alarm").getLong("id") == 2L && ob.getJSONArray("alarms").length() == 2, "Once alarm found without an ackId: $ob")

        // Slot limit from the watch's maxAlarms.
        val full = start(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30"))
        w.expect(LIST, list(2, WORK1, alarm(2, 23, 59, 0, null, false, 2)))
        val (fc, fb) = full()
        check(fc == 400 && fb.getString("error") == "The watch holds at most 2 alarms; delete one first", "Slot limit: $fb")
        w.quiet()

        val rejected = start(rig, "POST", "/api/alarms", JSONObject().put("time", "23:59").put("enabled", false))
        w.expect(LIST, list(3))
        w.expect(CREATE_ONCE, ack(1, status = 5))
        val (jc, jb) = rejected()
        check(jc == 503 && jb.getString("error") == "The watch rejected the alarm (status 5)", "Rejected create: $jb")

        val lost = start(rig, "POST", "/api/alarms", JSONObject().put("time", "23:59").put("enabled", false))
        w.expect(LIST, list(3))
        w.expect(CREATE_ONCE, ack(1))
        w.expect(LIST, list(3))
        val (lc, lb) = lost()
        check(lc == 503 && lb.getString("error") == "The watch did not save the alarm", "Unsaved create: $lb")
    }

    /** Firmware 2.2.105 stores a created alarm enabled although 17/1 asked for it disabled. */
    private fun createDisabled() {
        val rig = FeatureRig(AlarmsFeature(3000, 300))
        rig.connect()
        rig.receive(list(10, WORK1))
        rig.take()
        val w = Watch(rig)
        val body = JSONObject().put("time", "06:43").put("repeat", days("sun")).put("label", "WN test").put("enabled", false)

        val created = start(rig, "POST", "/api/alarms", body)
        w.expect(LIST, list(10, WORK1))
        w.expect(CREATE_SUN_OFF, ack(1))
        w.expect(LIST, list(10, WORK1, SUN2_ON))
        w.expect(EDIT_2_SUN_OFF, ack(2))
        w.expect(LIST, list(10, WORK1, SUN2_OFF))
        val (code, out) = created()
        val a = out.getJSONObject("alarm")
        check(code == 200 && out.getString("method") == "create" && out.getBoolean("verified"), "Create matched on time, repeat and smart, then disabled: $out")
        check(a.getLong("id") == 2L && !a.getBoolean("enabled") && a.getString("time") == "06:43" && a.getJSONArray("repeat").similar(days("sun")) && a.getString("label") == "WN test", "Created alarm: $a")
        check(JSONObject(rig.settings.values.getValue("feature.alarms.labels")).similar(JSONObject().put("2", stored("WN test", "06:43", 64))), "Label under the new id")
        w.quiet()

        // The disabling edit does not take: the alarm exists, enabled, and keeps its label.
        val sun3On = alarm(3, 6, 43, 5, 64, true, 2)
        val stuck = start(rig, "POST", "/api/alarms", body)
        w.expect(LIST, list(10, WORK1, SUN2_OFF))
        w.expect(CREATE_SUN_OFF, ack(1, id = 3))
        w.expect(LIST, list(10, WORK1, SUN2_OFF, sun3On))
        w.expect("081110029a01141a120803120e12040806102b1805204028003802") // alarm 3 -> disabled; no reply: the re-read decides
        w.expect(LIST, list(10, WORK1, SUN2_OFF, sun3On))
        val (sc, sb) = stuck()
        check(sc == 503 && sb.getString("error") == "Alarm 3 was saved but left enabled: the watch did not apply the edit", "Says what happened: $sb")
        check(JSONObject(rig.settings.values.getValue("feature.alarms.labels")).getJSONObject("3").similar(stored("WN test", "06:43", 64)), "The label still maps to the new id: ${rig.settings.values}")
        val listed = rig.call("GET", "/api/alarms").second.getJSONArray("alarms").getJSONObject(2)
        check(listed.getLong("id") == 3L && listed.getBoolean("enabled") && listed.getString("label") == "WN test", "The cache shows the alarm as the watch holds it: $listed")

        // Only time, repeat and smart identify the new alarm: a different time is not ours.
        rig.receive(list(10, WORK1))
        val wrong = start(rig, "POST", "/api/alarms", body)
        w.expect(LIST, list(10, WORK1))
        w.expect(CREATE_SUN_OFF, ack(1))
        w.expect(LIST, list(10, WORK1, alarm(2, 6, 44, 5, 64, true, 2)))
        val (wc, wb) = wrong()
        check(wc == 503 && wb.getString("error") == "The watch did not save the alarm", "A different alarm is not taken for ours: $wb")
        w.quiet()
    }

    private fun editInPlace() {
        val rig = FeatureRig(AlarmsFeature(3000, 3000))
        rig.connect()
        rig.receive(list(10, WORK1))
        rig.take()
        val w = Watch(rig)

        bad(rig, "PUT", "/api/alarms/1", JSONObject(), "Give at least one alarm field to change")
        bad(rig, "PUT", "/api/alarms/1", JSONObject().put("time", "25:00"), "Use a time like 07:30 (HH:MM, 24-hour)")
        bad(rig, "PUT", "/api/alarms/01", JSONObject().put("enabled", false), "Invalid alarm id")
        bad(rig, "PUT", "/api/alarms/x", JSONObject().put("enabled", false), "Invalid alarm id")

        val missing = start(rig, "PUT", "/api/alarms/9", JSONObject().put("enabled", false))
        w.expect(LIST, list(10, WORK1))
        val (mc, mb) = missing()
        check(mc == 400 && mb.getString("error") == "No alarm with id 9 on the watch", "Unknown id: $mb")

        val all = days("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val edit = start(rig, "PUT", "/api/alarms/1", JSONObject().put("time", "06:15").put("repeat", all).put("smart", true))
        w.expect(LIST, list(10, WORK1))
        w.expect(EDIT_1, ack(2))
        w.expect(LIST, list(10, DAILY1))
        val (code, body) = edit()
        check(code == 200 && body.getString("method") == "edit" && body.getBoolean("verified"), "Edit verified in place: $body")
        val a = body.getJSONObject("alarm")
        check(a.getLong("id") == 1L && a.getString("repeat_mode") == "daily" && a.getJSONArray("repeat").similar(all) && a.getBoolean("smart"), "Edited alarm: $a")
        check(rig.status().getJSONObject("alarms").getString("edit_method") == "edit" && "feature.alarms.edit" !in rig.settings.values, "Edit method is fixed, not learned")

        // Label only: the watch alarm is untouched.
        val label = start(rig, "PUT", "/api/alarms/1", JSONObject().put("label", "Gym"))
        w.expect(LIST, list(10, DAILY1))
        val lb = label().second
        check(lb.getString("method") == "unchanged" && lb.getJSONObject("alarm").getString("label") == "Gym", "Label-only edit: $lb")
        check(lb.getJSONArray("alarms").getJSONObject(0).getString("label") == "Gym", "The list in the reply has the new label: $lb")
        w.quiet()

        // Watch changed something else: no fallback, report it.
        val odd = start(rig, "PUT", "/api/alarms/1", JSONObject().put("enabled", false))
        w.expect(LIST, list(10, DAILY1))
        w.expect("081110029a01121a100801120c12040806100f180128003801")
        Thread.sleep(50)
        w.expect(LIST, list(10, alarm(1, 5, 0, 0, null, true, 2)))
        val (oc, ob) = odd()
        check(oc == 503 && ob.getString("error") == "Alarm 1 changed in an unexpected way; read the alarms again", "Unexpected change: $ob")

    }

    private fun editFallsBackToReplace() {
        val rig = FeatureRig(AlarmsFeature(3000, 300))
        rig.connect()
        rig.receive(list(10, WORK1))
        rig.take()
        val w = Watch(rig)
        val label = start(rig, "PUT", "/api/alarms/1", JSONObject().put("label", "Gym"))
        w.expect(LIST, list(10, WORK1))
        check(label().second.getString("method") == "unchanged", "Label set without a watch write")
        val all = days("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val body = JSONObject().put("time", "06:15").put("repeat", all).put("smart", true)

        val edit = start(rig, "PUT", "/api/alarms/1", body)
        w.expect(LIST, list(10, WORK1))
        w.expect(EDIT_1) // no reply: the ack wait times out
        w.expect(LIST, list(10, WORK1))
        w.expect(CREATE_DAILY_SMART, ack(1, id = 2))
        w.expect(LIST, list(10, WORK1, DAILY2))
        w.expect(DELETE_1, ack(4))
        w.expect(LIST, list(10, DAILY2))
        val (code, out) = edit()
        check(code == 200 && out.getString("method") == "replace" && out.getLong("previous_id") == 1L && out.getBoolean("verified"), "Fell back to replace: $out")
        check(out.getJSONObject("alarm").getLong("id") == 2L && out.getJSONObject("alarm").getString("label") == "Gym", "New id keeps the label: $out")
        check(out.getString("note").startsWith("The watch did not apply an in-place edit"), "Says so")
        check(rig.status().getJSONObject("alarms").getString("edit_method") == "edit", "One failed edit does not switch to replace for good")

        // The next edit tries 17/2 again.
        val again = start(rig, "PUT", "/api/alarms/2", JSONObject().put("time", "07:30").put("repeat", days("mon", "tue", "wed", "thu", "fri")).put("smart", false))
        w.expect(LIST, list(1, DAILY2))
        w.expect(EDIT_2_WORK, ack(2))
        w.expect(LIST, list(1, WORK2))
        val ab = again().second
        check(ab.getString("method") == "edit" && ab.getJSONObject("alarm").getLong("id") == 2L && ab.getJSONObject("alarm").getString("label") == "Gym", "Edit tried again after a fallback: $ab")

        // A failed edit at the slot limit deletes before it creates.
        val half = start(rig, "PUT", "/api/alarms/2", JSONObject().put("enabled", false))
        w.expect(LIST, list(1, WORK2))
        w.expect(EDIT_2_WORK_OFF) // no reply
        w.expect(LIST, list(1, WORK2))
        w.expect(DELETE_2, ack(4))
        w.expect(LIST, list(1))
        w.expect("081110019a0110120e12040807101e1805201f28003802", ack(1))
        w.expect(LIST, list(1))
        val (hc, hb) = half()
        check(hc == 503 && hb.getString("error") == "Alarm 2 was deleted, but its replacement was not saved: The watch did not save the alarm", "Half-done replace says what happened: $hb")

        // At the slot limit the delete comes first, and its list prunes the old id's label: the label still moves.
        rig.receive(list(1, WORK2))
        val relabel = start(rig, "PUT", "/api/alarms/2", JSONObject().put("label", "Gym"))
        w.expect(LIST, list(1, WORK2))
        relabel()
        val full = start(rig, "PUT", "/api/alarms/2", JSONObject().put("enabled", false))
        w.expect(LIST, list(1, WORK2))
        w.expect(EDIT_2_WORK_OFF) // no reply
        w.expect(LIST, list(1, WORK2))
        w.expect(DELETE_2, ack(4))
        w.expect(LIST, list(1))
        w.expect("081110019a0110120e12040807101e1805201f28003802", ack(1, id = 3))
        w.expect(LIST, list(1, alarm(3, 7, 30, 5, 31, true, 2)))
        w.expect("081110029a01141a120803120e12040807101e1805201f28003802", ack(2))
        w.expect(LIST, list(1, alarm(3, 7, 30, 5, 31, false, 2)))
        val fb = full().second
        check(fb.getString("method") == "replace" && fb.getJSONObject("alarm").getLong("id") == 3L && fb.getJSONObject("alarm").getString("label") == "Gym", "Label moved to the new id: $fb")
    }

    private fun deleteRoute() {
        val rig = FeatureRig(AlarmsFeature(3000, 300))
        rig.connect()
        rig.receive(list(10, WORK1, DAILY2))
        rig.take()
        val w = Watch(rig)
        bad(rig, "DELETE", "/api/alarms/abc", JSONObject(), "Invalid alarm id")

        val del = start(rig, "DELETE", "/api/alarms/2")
        w.expect(LIST, list(10, WORK1, DAILY2))
        w.expect(DELETE_2) // no ack in time
        w.expect(LIST, list(10, WORK1))
        val (code, body) = del()
        check(code == 200 && body.getLong("deleted") == 2L && body.getBoolean("verified") && body.getJSONArray("alarms").length() == 1, "Delete verified: $body")
        check(rig.status().getJSONObject("alarms").getInt("alarms") == 1, "Cache follows")
        rig.receive(ack(4, status = 9)) // its ack, late: it answers that delete, not the next one
        w.expect(LIST, list(10, WORK1)) // and, answering no route, it has the list read again

        val gone = start(rig, "DELETE", "/api/alarms/2")
        w.expect(LIST, list(10, WORK1))
        check(gone().let { it.first == 400 && it.second.getString("error") == "No alarm with id 2 on the watch" }, "Delete unknown id")

        val stuck = start(rig, "DELETE", "/api/alarms/1")
        w.expect(LIST, list(10, WORK1))
        w.expect(DELETE_1, ack(4))
        w.expect(LIST, list(10, WORK1))
        val (sc, sb) = stuck()
        check(sc == 503 && sb.getString("error") == "The watch did not delete alarm 1", "Unverified delete: $sb")

        val refused = start(rig, "DELETE", "/api/alarms/1")
        w.expect(LIST, list(10, WORK1))
        w.expect(DELETE_1, ack(4, status = 2))
        check(refused().second.getString("error") == "The watch refused to delete alarm 1 (status 2)", "Refused delete")
    }

    private fun sleepMode() {
        val rig = FeatureRig(AlarmsFeature(3000, 3000))
        bad(rig, "PUT", "/api/alarms/sleep-mode", JSONObject().put("enabled", true).put("start", "22:00").put("end", "06:30"), "Connect the watch first")
        rig.connect()
        rig.receive(list(10))
        rig.receive(sleep(false, 23, 0, 7, 0))
        rig.take()
        val w = Watch(rig)
        bad(rig, "PUT", "/api/alarms/sleep-mode", JSONObject(), "Give enabled, start or end")
        bad(rig, "PUT", "/api/alarms/sleep-mode", JSONObject().put("days", 1), "Unknown sleep mode field 'days'")
        bad(rig, "PUT", "/api/alarms/sleep-mode", JSONObject().put("start", "22"), "Use a time like 07:30 (HH:MM, 24-hour)")
        bad(rig, "PUT", "/api/alarms/sleep-mode", JSONObject().put("enabled", 1), "enabled must be true or false")
        bad(rig, "PUT", "/api/alarms/sleep-mode", JSONObject().put("enabled", true).put("start", "22:00").put("end", "22:00"), "Start and end must differ")

        val put = start(rig, "PUT", "/api/alarms/sleep-mode", JSONObject().put("enabled", true).put("start", "22:00").put("end", "06:30"))
        w.expect(SLEEP_SET, Proto.command(17, 9))
        w.expect(SLEEP, sleep(true, 22, 0, 6, 30))
        val (code, body) = put()
        check(code == 200 && body.getBoolean("verified") && body.getString("start") == "22:00" && body.getString("end") == "06:30" && body.getBoolean("enabled"), "Sleep set verified: $body")
        check(rig.status().getJSONObject("alarms").getBoolean("sleep_mode_supported"), "Sleep support from the read")

        val partial = start(rig, "PUT", "/api/alarms/sleep-mode", JSONObject().put("enabled", false))
        w.expect(SLEEP, sleep(true, 22, 0, 6, 30))
        w.expect("081110099a01144a120800120e0a040816100012040806101e1800", Proto.command(17, 9))
        w.expect(SLEEP, sleep(true, 22, 0, 6, 30))
        val (pc, pb) = partial()
        check(pc == 503 && pb.getString("error") == "The watch did not apply the sleep schedule", "Unapplied sleep schedule: $pb")

        val rejected = start(rig, "PUT", "/api/alarms/sleep-mode", JSONObject().put("enabled", true).put("start", "22:00").put("end", "06:30"))
        w.expect(SLEEP_SET, Proto.command(17, 9, Proto.num(100, 1)))
        check(rejected().second.getString("error") == "The watch rejected the sleep schedule (status 1)", "Rejected sleep schedule")
    }

    private fun missingReplies() {
        val rig = FeatureRig(AlarmsFeature(500, 50))
        rig.connect()
        check(rig.take() == listOf(LIST, SLEEP), "Connect reads")
        rig.fire()
        val s = rig.status().getJSONObject("alarms")
        check(s.getString("read_state") == "no_reply" && s.isNull("supported") && s.getString("sleep_mode_read_state") == "no_reply" && s.isNull("sleep_mode_supported"), "No reply is unknown, not unsupported: $s")

        // The connect read is still owed, so the next reply is its and this read cannot claim it.
        val w = Watch(rig)
        val (code, body) = rig.call("POST", "/api/alarms", JSONObject().put("time", "07:30"))
        check(code == 503 && body.getString("error") == "The watch has not answered an earlier alarm list request; reconnect the watch", "Unanswered read: $body")
        w.expect(LIST)

        // Both late replies land in the cache and settle what was owed.
        rig.receive(list(10))
        rig.receive(list(10))
        check(rig.status().getJSONObject("alarms").getBoolean("supported"), "Late replies count for the cache")

        // A missing create ack does not break the write: the re-read decides.
        val created = start(rig, "POST", "/api/alarms", JSONObject().put("time", "23:59").put("enabled", false))
        w.expect(LIST, list(10))
        w.expect(CREATE_ONCE)
        w.expect(LIST, list(10, alarm(5, 23, 59, 0, null, false, 2)))
        val cb = created().second
        check(cb.getJSONObject("alarm").getLong("id") == 5L, "Create without ack verified: $cb")

        val sl = start(rig, "PUT", "/api/alarms/sleep-mode", JSONObject().put("enabled", true).put("start", "22:00").put("end", "06:30"))
        w.expect(SLEEP_SET)
        w.expect(SLEEP)
        check(sl().second.getString("error") == "The watch has not answered an earlier sleep mode request; reconnect the watch", "Unverifiable sleep write is reported")
        check(rig.watch.handlers.keys.none { it.first == 17 && it.second in setOf(14, 15, 17, 18) }, "Reminder subtypes untouched")
    }

    /** Read A times out; route B starts; A's reply arrives, then B's. B must use its own. */
    private fun lateReplies() {
        val rig = FeatureRig(AlarmsFeature(300, 100))
        rig.connect()
        rig.receive(list(10))
        rig.receive(sleep(false, 23, 0, 7, 0))
        rig.take()
        val w = Watch(rig)
        val a = rig.call("POST", "/api/alarms/refresh")
        check(a.first == 503 && a.second.getString("error") == "The watch did not answer the alarm list request", "Read A times out: $a")
        w.expect(LIST)

        val b = start(rig, "POST", "/api/alarms", JSONObject().put("time", "23:59").put("enabled", false))
        w.expect(LIST, list(1, WORK1)) // A's late reply: full, before the user freed a slot
        Thread.sleep(50)
        rig.receive(list(10)) // B's own
        w.expect(CREATE_ONCE, ack(1))
        w.expect(LIST, list(10, alarm(5, 23, 59, 0, null, false, 2)))
        val (code, body) = b()
        check(code == 200 && body.getJSONObject("alarm").getLong("id") == 5L, "B used its own read: $body")
    }

    /** The watch restarts its session between two steps of one create: the next step never reaches the new session. */
    private fun reconnectMidOperation() {
        val rig = FeatureRig(AlarmsFeature(3000, 100))
        rig.connect()
        rig.receive(list(10, WORK1))
        rig.take()
        val w = Watch(rig)
        // Saving the label sits between the verified create and the edit that disables the alarm.
        rig.settings.onPut = { key ->
            if (key == "feature.alarms.labels") {
                rig.settings.onPut = null
                rig.disconnect()
                rig.connect()
            }
        }
        val create = start(rig, "POST", "/api/alarms", JSONObject().put("time", "06:43").put("repeat", days("sun")).put("label", "WN test").put("enabled", false))
        w.expect(LIST, list(10, WORK1))
        w.expect(CREATE_SUN_OFF, ack(1))
        w.expect(LIST, list(10, WORK1, SUN2_ON))
        w.expect(LIST) // the new session's own reads
        w.expect(SLEEP)
        val (code, body) = create()
        check(code == 503 && body.getString("error") == "Alarm 2 was saved but left enabled: Watch disconnected", "The operation ends with its session: $body")
        w.quiet()
    }

    private fun disconnectAndBusy() {
        val rig = FeatureRig(AlarmsFeature(3000, 3000))
        rig.connect()
        rig.receive(list(10))
        rig.take()
        val w = Watch(rig)

        val first = start(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30"))
        w.expect(LIST)
        val (bc, bb) = rig.call("DELETE", "/api/alarms/1")
        check(bc == 409 && bb.getString("error") == "Another watch operation is in progress", "One write at a time: $bb")
        w.quiet(10)
        rig.disconnect()
        val (code, body) = first()
        check(code == 503 && body.getString("error") == "Watch disconnected", "Disconnect ends the wait: $body")
        check(rig.status().getJSONObject("alarms").getInt("alarms") == 0, "Cache kept after disconnect")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30"), "Connect the watch first")
        bad(rig, "DELETE", "/api/alarms/1", JSONObject(), "Connect the watch first")
        bad(rig, "POST", "/api/alarms/refresh", JSONObject(), "Connect the watch first")
        check(rig.call("GET", "/api/alarms").let { it.first == 200 && !it.second.getBoolean("connected") }, "GET works offline")

        rig.take()
        rig.connect()
        check(rig.take() == listOf(LIST, SLEEP), "Reconnect reads again")
        check(rig.status().getJSONObject("alarms").getString("read_state") == "waiting", "Waiting again")
    }

    /** The watch's own 17/1-17/5 and 17/9, its news of a change made on the wrist, make the app read again. */
    private fun watchPushes() {
        val rig = FeatureRig(AlarmsFeature(3000, 300))
        rig.connect()
        rig.receive(list(10, WORK1))
        rig.receive(sleep(false, 23, 0, 7, 0))
        rig.take()

        rig.receive(ack(5))
        check(rig.take() == listOf(LIST), "A pushed 17/5 reads the list again")
        check("Watch sent 17/5 on its own; reading its alarms again" in rig.logs, "Logged: ${rig.logs}")
        rig.receive(ack(2))
        check(rig.take().isEmpty(), "No second read while one is owed")
        rig.receive(list(10, alarm(1, 7, 30, 5, 31, false, 2)))
        check(!rig.call("GET", "/api/alarms").second.getJSONArray("alarms").getJSONObject(0).getBoolean("enabled"), "The cache shows the alarm turned off on the wrist")

        for (sub in listOf(1, 2, 3, 4)) {
            rig.receive(ack(sub))
            check(rig.take() == listOf(LIST), "A pushed 17/$sub reads the list again")
            rig.receive(list(10, WORK1))
        }
        rig.receive(ack(9))
        check(rig.take() == listOf(SLEEP), "A pushed 17/9 reads the sleep schedule again")
        rig.receive(sleep(true, 22, 0, 6, 30))
        check(rig.call("GET", "/api/alarms/sleep-mode").second.getBoolean("enabled"), "The sleep schedule follows")

        // A watch that never answers 17/2 leaves the edit owed; its next push still has the list read.
        val w = Watch(rig)
        val off = start(rig, "PUT", "/api/alarms/1", JSONObject().put("enabled", false))
        w.expect(LIST, list(10, WORK1))
        w.expect("081110029a01141a120801120e12040807101e1805201f28003802")
        w.expect(LIST, list(10, alarm(1, 7, 30, 5, 31, false, 2)))
        check(off().first == 200, "Edit verified by the re-read")
        rig.receive(ack(5))
        w.expect(LIST)
    }

    /**
     * A one-time alarm turns itself off when it rings, so the list is read again two minutes after,
     * by phone time, on a wakeup alarm: a timer's clock stops while the phone sleeps.
     */
    private fun readAfterRing() {
        var now = 1792832400000L // 24 October 2026, 06:00 in Sao Paulo (09:00 UTC)
        val rig = FeatureRig(AlarmsFeature(3000, 3000, { ZoneId.of("America/Sao_Paulo") }) { now })
        rig.connect()
        rig.take()
        fun reads() = rig.phone.wakeups.map { it.first }
        val once0630 = alarm(1, 6, 30, 0, null, true, 2)
        val once0500 = alarm(2, 5, 0, 0, null, true, 2) // already past today: rings tomorrow
        val daily0610 = alarm(3, 6, 10, 1, null, true, 2)
        val off0605 = alarm(4, 6, 5, 0, null, false, 2)
        rig.receive(list(10, once0630, once0500, daily0610, off0605))
        check(reads() == listOf(1792834320000L), "Read again at 06:32: ${reads()}")
        check(rig.watch.delays() == listOf(300_000L, 10_000L), "No link timer, only the poll and the connect wait: ${rig.watch.delays()}")

        now = 1792834260000L // 06:31: rang a minute ago, not yet read
        rig.receive(list(10, once0630, once0500, daily0610, off0605))
        check(reads() == listOf(1792834320000L), "Still read at 06:32: ${reads()}")

        now = 1792834320000L // 06:32
        rig.phone.wake(now)
        check(rig.take() == listOf(LIST), "The wakeup reads the list")
        rig.receive(list(10, alarm(1, 6, 30, 0, null, false, 2), once0500, daily0610, off0605))
        check(reads() == listOf(1792915320000L), "Next: tomorrow's 05:00 plus 2 minutes: ${reads()}")
        check(!rig.call("GET", "/api/alarms").second.getJSONArray("alarms").getJSONObject(0).getBoolean("enabled"), "The rung alarm shows off")

        now = 1792897230000L // 25 October, 00:00:30: yesterday's 23:59 is read at 00:01
        rig.receive(list(10, alarm(5, 23, 59, 0, null, true, 2)))
        check(reads() == listOf(1792897260000L), "Across midnight: ${reads()}")

        rig.receive(list(10, daily0610))
        check(reads().isEmpty(), "Repeating alarms need no read")
        rig.receive(list(10, once0630))
        val stale = rig.phone.wakeups.single().second
        rig.disconnect()
        check(reads().isEmpty(), "Disconnect cancels the read")
        rig.connect()
        rig.take()
        stale()
        check(rig.take().isEmpty(), "A read armed in the last session sends nothing")
    }

    /** The watch reports no change made on the wrist, so the list is read every five minutes while connected, and not after. */
    private fun pollWhileConnected() {
        val rig = FeatureRig(AlarmsFeature(3000, 3000))
        rig.connect()
        check(rig.take() == listOf(LIST, SLEEP), "Connect reads the list")
        rig.receive(list(10, WORK1))
        rig.receive(sleep(true, 22, 0, 6, 30))
        check(rig.watch.delays() == listOf(300_000L, 10_000L), "A poll in five minutes: ${rig.watch.delays()}")
        rig.watch.fire()
        check(rig.take() == listOf(LIST), "The poll reads the list")
        check(rig.watch.delays() == listOf(300_000L), "And sets the next one: ${rig.watch.delays()}")
        rig.watch.fire()
        check(rig.take().isEmpty(), "No second read while one is owed")
        rig.receive(list(10))
        check(rig.call("GET", "/api/alarms").second.getJSONArray("alarms").length() == 0, "The polled list replaces the cache")
        rig.disconnect()
        check(rig.watch.delays().isEmpty(), "Disconnect stops polling")
    }

    /** An alarm still on at the read after its ring was snoozed: read again 10 and 20 minutes after it rang, then stop. */
    private fun readAfterSnooze() {
        var now = 1792834320000L // 24 October 2026, 06:32 in Sao Paulo
        val rig = FeatureRig(AlarmsFeature(3000, 3000, { ZoneId.of("America/Sao_Paulo") }) { now })
        rig.connect()
        rig.take()
        fun reads() = rig.phone.wakeups.map { it.first }
        val once0630 = alarm(1, 6, 30, 0, null, true, 2)
        rig.receive(list(10, once0630))
        check(reads() == listOf(1792834800000L), "Still on at 06:32: read again at 06:40: ${reads()}")

        now = 1792834800000L // 06:40
        rig.phone.wake(now)
        check(rig.take() == listOf(LIST), "The 06:40 read")
        rig.receive(list(10, once0630))
        check(reads() == listOf(1792835400000L), "Still on: read again at 06:50: ${reads()}")

        now = 1792835400000L // 06:50
        rig.phone.wake(now)
        check(rig.take() == listOf(LIST), "The 06:50 read")
        rig.receive(list(10, once0630))
        check(reads() == listOf(1792920720000L), "Then only after tomorrow's ring, at 06:32: ${reads()}")
    }

    /** A time zone or clock change moves the ring, so the read is armed again from the cached list. */
    private fun readAfterTimeChange() {
        val now = 1792832400000L // 24 October 2026, 06:00 in Sao Paulo, 04:00 at UTC-5
        var zone = ZoneId.of("America/Sao_Paulo")
        val rig = FeatureRig(AlarmsFeature(3000, 3000, { zone }) { now })
        fun reads() = rig.phone.wakeups.map { it.first }
        rig.phone.timeChanged()
        check(reads().isEmpty(), "No list, no read")
        rig.connect()
        rig.take()
        rig.receive(list(10, alarm(1, 6, 30, 0, null, true, 2)))
        check(reads() == listOf(1792834320000L), "06:32 in Sao Paulo: ${reads()}")

        zone = ZoneId.of("Etc/GMT+5") // UTC-5: POSIX zone names flip the sign
        rig.phone.timeChanged()
        check(reads() == listOf(1792841520000L), "06:32 at UTC-5: ${reads()}")
        check(rig.take().isEmpty(), "Armed from the cached list, without a read")

        rig.disconnect()
        rig.phone.timeChanged()
        check(reads().isEmpty(), "Not armed while disconnected")
        rig.connect()
        rig.receive(list(10, alarm(1, 6, 30, 0, null, true, 2)))
        rig.features.close()
        check(reads().isEmpty(), "Close cancels the read")
        rig.phone.timeChanged()
        check(reads().isEmpty(), "Close stops listening for time changes")
    }

    /** A write may carry the alarm as the caller saw it; one changed or gone on the watch answers 409 and writes nothing. */
    private fun staleWrites() {
        val rig = FeatureRig(AlarmsFeature(3000, 300))
        rig.connect()
        rig.receive(list(10, WORK1))
        rig.take()
        val w = Watch(rig)
        val seen = JSONObject().put("time", "07:30").put("repeat", days("mon", "tue", "wed", "thu", "fri")).put("repeat_mode", "weekly").put("enabled", true).put("smart", false)

        bad(rig, "PUT", "/api/alarms/1", JSONObject().put("expect", seen), "Give at least one alarm field to change")
        bad(rig, "PUT", "/api/alarms/1", JSONObject().put("enabled", false).put("expect", "07:30"), "expect must be an object of alarm fields")
        bad(rig, "PUT", "/api/alarms/1", JSONObject().put("enabled", false).put("expect", JSONObject().put("label", "Gym")), "Unknown expect field 'label'")
        bad(rig, "PUT", "/api/alarms/1", JSONObject().put("enabled", false).put("expect", JSONObject().put("time", "7:30")), "Use a time like 07:30 (HH:MM, 24-hour)")
        bad(rig, "DELETE", "/api/alarms/1", JSONObject().put("expect", JSONObject().put("enabled", "yes")), "enabled must be true or false")
        bad(rig, "POST", "/api/alarms", JSONObject().put("time", "07:30").put("expect", seen), "Unknown alarm field 'expect'")

        // On the wrist, alarm 1 was deleted and a new alarm took its id; the app's list predates both.
        val reused = alarm(1, 9, 0, 0, null, true, 2)
        val toggle = start(rig, "PUT", "/api/alarms/1", JSONObject().put("enabled", false).put("expect", seen))
        w.expect(LIST, list(10, reused))
        val (tc, tb) = toggle()
        check(tc == 409 && tb.getString("error") == "Alarm 1 changed on the watch; read the alarms again", "Stale toggle refused: $tb")
        w.quiet()
        check(rig.call("GET", "/api/alarms").second.getJSONArray("alarms").getJSONObject(0).getString("time") == "09:00", "The cache shows the watch's alarm")

        val gone = start(rig, "DELETE", "/api/alarms/2", JSONObject().put("expect", seen))
        w.expect(LIST, list(10, reused))
        val (gc, gb) = gone()
        check(gc == 409 && gb.getString("error") == "Alarm 2 is no longer on the watch; read the alarms again", "Gone alarm: $gb")
        w.quiet()

        val del = start(rig, "DELETE", "/api/alarms/1", JSONObject().put("expect", JSONObject().put("time", "09:00").put("repeat", JSONArray()).put("enabled", true)))
        w.expect(LIST, list(10, reused))
        w.expect(DELETE_1, ack(4))
        w.expect(LIST, list(10))
        check(del().first == 200, "An alarm as seen is deleted")
    }

    /** An edit sends back what it does not change as the watch gave it: a repeat mode the app cannot write, a field it does not know. */
    private fun editKeepsWatchFields() {
        val rig = FeatureRig(AlarmsFeature(3000, 3000))
        rig.connect()
        // 08:00 in repeat mode 7, with field 6 (a label in Xiaomi's protocol) set by another app.
        fun mode7(on: Long) = alarmOf(4, Proto.join(Proto.bytes(2, hm(8, 0)), Proto.num(3, 7), Proto.num(5, on), Proto.bytes(6, "Wake".toByteArray()), Proto.num(7, 2)))
        val weekdays = alarm(5, 7, 0, 4, null, true, 2) // Xiaomi's Monday-to-Friday mode
        rig.receive(list(10, mode7(1), weekdays))
        rig.take()
        val w = Watch(rig)
        val listed = rig.call("GET", "/api/alarms").second.getJSONArray("alarms")
        check(listed.getJSONObject(0).getString("repeat_mode") == "mode_7" && listed.getJSONObject(1).getString("repeat_mode") == "weekdays", "Modes: $listed")
        check(listed.getJSONObject(1).getJSONArray("repeat").similar(days("mon", "tue", "wed", "thu", "fri")), "Mode 4 is Monday to Friday")

        val off = start(rig, "PUT", "/api/alarms/4", JSONObject().put("enabled", false))
        w.expect(LIST, list(10, mode7(1), weekdays))
        w.expect("081110029a01181a1608041212120408081000180728003802320457616b65", ack(2))
        w.expect(LIST, list(10, mode7(0), weekdays))
        val (oc, ob) = off()
        check(oc == 200 && ob.getString("method") == "edit" && !ob.getJSONObject("alarm").getBoolean("enabled") && ob.getJSONObject("alarm").getString("repeat_mode") == "mode_7", "Turned off with its mode kept: $ob")

        val later = start(rig, "PUT", "/api/alarms/5", JSONObject().put("time", "07:05"))
        w.expect(LIST, list(10, mode7(0), weekdays))
        w.expect("081110029a01121a100805120c120408071005180428013802", ack(2))
        w.expect(LIST, list(10, mode7(0), alarm(5, 7, 5, 4, null, true, 2)))
        check(later().second.getString("method") == "edit", "Mode 4 kept on a time change")

        // An edit the watch does not apply to a mode the app cannot write is not replaced: 17/1 never wrote that mode.
        val stuck = start(rig, "PUT", "/api/alarms/4", JSONObject().put("enabled", true))
        w.expect(LIST, list(10, mode7(0), weekdays))
        w.expect("081110029a01181a1608041212120408081000180728013802320457616b65", ack(2))
        w.expect(LIST, list(10, mode7(0), weekdays))
        val (sc, sb) = stuck()
        check(sc == 503 && sb.getString("error") == "The watch did not apply the edit to alarm 4", "No replace: $sb")
        w.quiet()
    }

    /** A watch that drops smart wake on create still holds the alarm: it is found, the edit is tried, and the error says what stuck. */
    private fun createKeepsUnsupportedSmart() {
        val rig = FeatureRig(AlarmsFeature(3000, 300))
        rig.connect()
        rig.receive(list(10, WORK1))
        rig.take()
        val w = Watch(rig)
        val body = JSONObject().put("time", "06:00").put("smart", true)
        val normal = alarm(2, 6, 0, 0, null, true, 2)

        val stuck = start(rig, "POST", "/api/alarms", body)
        w.expect(LIST, list(10, WORK1))
        w.expect(CREATE_SMART, ack(1))
        w.expect(LIST, list(10, WORK1, normal))
        w.expect(EDIT_2_SMART, ack(2))
        w.expect(LIST, list(10, WORK1, normal))
        val (sc, sb) = stuck()
        check(sc == 503 && sb.getString("error") == "Alarm 2 was saved but left without smart wake: the watch did not apply the edit", "Says what happened: $sb")

        val fixed = start(rig, "POST", "/api/alarms", body)
        w.expect(LIST, list(10, WORK1))
        w.expect(CREATE_SMART, ack(1))
        w.expect(LIST, list(10, WORK1, normal))
        w.expect(EDIT_2_SMART, ack(2))
        w.expect(LIST, list(10, WORK1, alarm(2, 6, 0, 0, null, true, 1)))
        val fb = fixed().second
        check(fb.getString("method") == "create" && fb.getJSONObject("alarm").getBoolean("smart"), "Smart wake set by the edit: $fb")
    }

    /** A label whose alarm left the watch is dropped, so an alarm that later gets the id does not inherit it. */
    private fun labelsFollowTheWatch() {
        val rig = FeatureRig(AlarmsFeature(3000, 3000))
        rig.connect()
        rig.receive(list(10, WORK1))
        rig.take()
        val w = Watch(rig)
        val label = start(rig, "PUT", "/api/alarms/1", JSONObject().put("label", "Gym"))
        w.expect(LIST, list(10, WORK1))
        check(label().second.getJSONObject("alarm").getString("label") == "Gym", "Labelled")

        rig.receive(list(10)) // deleted on the wrist
        check(JSONObject(rig.settings.values.getValue("feature.alarms.labels")).length() == 0, "Label dropped with its alarm: ${rig.settings.values}")
        rig.receive(list(10, alarm(1, 9, 0, 0, null, true, 2))) // added on the wrist, with the free id
        check(rig.call("GET", "/api/alarms").second.getJSONArray("alarms").getJSONObject(0).isNull("label"), "The new alarm has no label")
    }

    /**
     * A prune drops the labels its own list lacked, even when a later list already holds an alarm with
     * the id again, but never a label set after that list arrived.
     */
    private fun labelsPrunedByTheirList() {
        val rig = FeatureRig(AlarmsFeature(3000, 3000))
        rig.connect()
        rig.receive(list(10, WORK1))
        rig.take()
        val w = Watch(rig)
        fun labels() = JSONObject(rig.settings.values.getValue("feature.alarms.labels"))
        // A list as the reader thread hands it over, before the commands thread runs what it submitted.
        fun deliver(cmd: ByteArray) = rig.watch.handlers.getValue(17 to 0)(Proto.decode(cmd))
        val label = start(rig, "PUT", "/api/alarms/1", JSONObject().put("label", "Gym"))
        w.expect(LIST, list(10, WORK1))
        label()

        deliver(list(10)) // deleted on the wrist
        deliver(list(10, alarm(1, 9, 0, 0, null, true, 2))) // added on the wrist, with the free id
        rig.drain()
        check(labels().length() == 0, "The prune of the list without alarm 1 still drops its label: ${rig.settings.values}")
        check(rig.call("GET", "/api/alarms").second.getJSONArray("alarms").getJSONObject(0).isNull("label"), "The new alarm has no label")

        val gym = start(rig, "PUT", "/api/alarms/1", JSONObject().put("label", "Gym"))
        w.expect(LIST, list(10, alarm(1, 9, 0, 0, null, true, 2)))
        gym()
        deliver(list(10)) // deleted on the wrist; its prune waits
        val create = start(rig, "POST", "/api/alarms", JSONObject().put("time", "06:00").put("smart", true).put("label", "Nap"))
        w.expect(LIST)
        deliver(list(10))
        w.expect(CREATE_SMART)
        rig.watch.handlers.getValue(17 to 1)(Proto.decode(ack(1)))
        w.expect(LIST)
        deliver(list(10, alarm(1, 6, 0, 0, null, true, 1)))
        val (code, body) = create()
        check(code == 200 && body.getJSONObject("alarm").getString("label") == "Nap", "Created with the label: $body")
        check(labels().similar(JSONObject().put("1", stored("Nap", "06:00", 0))), "A label set after the list arrived survives its prune: ${rig.settings.values}")
        check(body.getJSONArray("alarms").getJSONObject(0).getString("label") == "Nap", "The reply's list shows it: $body")
    }

    /**
     * Without a restore a label follows its alarm's id: a time or repeat changed on the wrist, or
     * while the link was down, is stored with the label. A label saved as plain text gets its alarm.
     */
    private fun labelsFollowTheirAlarm() {
        val saved = MemSettings()
        saved.setting("feature.alarms.labels", JSONObject().put("1", stored("Work", "07:30", 31)).put("2", stored("Gym", "18:00", 0)).put("3", "Old").toString())
        val rig = FeatureRig(AlarmsFeature(3000, 3000), settings = saved)
        fun labels() = JSONObject(rig.settings.values.getValue("feature.alarms.labels"))
        val nine2 = alarm(2, 9, 0, 0, null, true, 2)
        rig.connect()
        rig.receive(list(10, WORK1, nine2, alarm(3, 8, 0, 1, null, true, 2)))
        check(
            labels().similar(JSONObject().put("1", stored("Work", "07:30", 31)).put("2", stored("Gym", "09:00", 0)).put("3", stored("Old", "08:00", 127))),
            "The first list of a connection keeps every label and stores its alarm's time and repeat: ${rig.settings.values}",
        )

        rig.receive(list(10, alarm(1, 7, 45, 5, 31, true, 2), nine2, alarm(3, 8, 0, 1, null, true, 2))) // alarm 1 moved on the wrist
        check(labels().getJSONObject("1").similar(stored("Work", "07:45", 31)), "A later list moves the label with its alarm: ${rig.settings.values}")

        rig.disconnect()
        rig.connect()
        rig.receive(list(10, alarm(1, 7, 45, 5, 31, true, 2), nine2, alarm(3, 8, 30, 1, null, true, 2))) // alarm 3 changed while away
        check(labels().getJSONObject("3").similar(stored("Old", "08:30", 127)), "The next connection's first list moves the label too: ${rig.settings.values}")
        check(!rig.settings.values.containsKey("feature.alarms.restored"), "No restore marker: ${rig.settings.values}")
    }

    /**
     * A restored label may sit on an id the watch has since given another alarm: the first list after
     * a restore drops a label whose alarm has another time or repeat, then clears the marker. Later
     * lists, and the next connection, follow the alarm again.
     */
    private fun labelsCheckedAfterRestore() {
        val saved = MemSettings()
        saved.setting("feature.alarms.labels", JSONObject().put("1", stored("Work", "07:30", 31)).put("2", stored("Gym", "18:00", 0)).put("3", "Old").toString())
        markAlarmsRestored(saved::setting)
        check(saved.values["feature.alarms.restored"] == """{"labels":true}""", "The restore leaves its marker: ${saved.values}")
        val rig = FeatureRig(AlarmsFeature(3000, 3000), settings = saved)
        fun labels() = JSONObject(rig.settings.values.getValue("feature.alarms.labels"))
        val nine2 = alarm(2, 9, 0, 0, null, true, 2)
        rig.connect()
        rig.receive(list(10, WORK1, nine2, alarm(3, 8, 0, 1, null, true, 2)))
        check(
            labels().similar(JSONObject().put("1", stored("Work", "07:30", 31)).put("3", stored("Old", "08:00", 127))),
            "The first list drops the label on the alarm that reused id 2 and stores alarm 3 with its label: ${rig.settings.values}",
        )
        check(rig.settings.values["feature.alarms.restored"] == "{}", "The marker is cleared: ${rig.settings.values}")
        val listed = rig.call("GET", "/api/alarms").second.getJSONArray("alarms")
        check(
            listed.getJSONObject(0).getString("label") == "Work" && listed.getJSONObject(1).isNull("label") && listed.getJSONObject(2).getString("label") == "Old",
            "Labels shown: $listed",
        )

        rig.receive(list(10, alarm(1, 7, 45, 5, 31, true, 2), nine2, alarm(3, 8, 0, 1, null, true, 2))) // alarm 1 moved on the wrist
        check(labels().getJSONObject("1").similar(stored("Work", "07:45", 31)), "A later list moves the label with its alarm: ${rig.settings.values}")

        // Restarted: the cleared marker checks nothing.
        val again = FeatureRig(AlarmsFeature(3000, 3000), settings = rig.settings)
        again.connect()
        again.receive(list(10, alarm(1, 7, 45, 5, 31, true, 2), nine2, alarm(3, 8, 30, 1, null, true, 2))) // alarm 3 changed while away
        check(
            labels().similar(JSONObject().put("1", stored("Work", "07:45", 31)).put("3", stored("Old", "08:30", 127))),
            "After the first list, a changed alarm keeps its label: ${rig.settings.values}",
        )

        // A marker with no labels to check is cleared too.
        val empty = MemSettings()
        markAlarmsRestored(empty::setting)
        val fresh = FeatureRig(AlarmsFeature(3000, 3000), settings = empty)
        fresh.connect()
        fresh.receive(list(10, WORK1))
        check(empty.values["feature.alarms.restored"] == "{}" && !empty.values.containsKey("feature.alarms.labels"), "Cleared without a label write: ${empty.values}")
    }
}
