package local.hyprwatch

import org.json.JSONObject

object HealthSettingsFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun bytes(hex: String): ByteArray = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun h(hex: String) = hex.replace(" ", "")

    /** A watch reply 8/[sub] carrying [body] as Health field [field]. */
    private fun reply(sub: Int, field: Int, body: String, vararg extra: ByteArray) =
        Proto.command(8, sub, Proto.bytes(10, Proto.bytes(field, bytes(body))), *extra)

    private const val NOW = 1_700_000_000_000L
    private const val HR = "08 00 10 0a 18 01 20 78 2a 02 08 01 30 05 38 01 42 04 08 01 10 2d 48 01 58 02"
    private const val SPO2 = "08 01 10 02 18 00 22 04 08 01 10 5a"
    private const val SEDENTARY = "08 01 12 04 08 06 10 00 1a 04 08 16 10 00"
    private const val GOALS = "0a 02 08 01 0a 02 08 03 12 02 08 01 12 02 08 03 12 02 08 04"
    private val READS = listOf("0808 1008", "0808 100a", "0808 100c", "0808 100e", "0808 1015", "0808 102a", "0808 1023").map(::h)

    private fun put(rig: FeatureRig, body: JSONObject) = rig.call("PUT", "/api/watch-settings", body)

    private fun get(rig: FeatureRig) = rig.call("GET", "/api/watch-settings").second

    @JvmStatic
    fun main(args: Array<String>) {
        var now = NOW
        val rig = FeatureRig(HealthSettingsFeature({ now }, verifyWaitMs = 0))

        val offline = put(rig, JSONObject().put("stress_auto", true))
        check(offline.first == 400 && offline.second.getString("error") == "Connect the watch first", "Disconnected PUT is a 400")
        check(rig.status().getJSONObject("watch_settings").isNull("read_at"), "Nothing read yet")

        rig.connect()
        check(rig.take() == READS, "Connect reads every config block, GB's order")
        val before = get(rig)
        check(before.getJSONObject("supported").getString("heart_rate") == "unknown" && before.getJSONObject("settings").isNull("hr_interval"), "No reply yet: unknown")
        val early = put(rig, JSONObject().put("hr_interval", "smart"))
        check(early.first == 400 && early.second.getString("error") == "The watch has not reported its heart_rate settings on this connection", "Write before read is a 400")
        check(rig.sent().isEmpty(), "Nothing sent for it")

        rig.receive(reply(10, 8, HR))
        rig.receive(reply(8, 7, SPO2))
        rig.receive(Proto.command(8, 14, Proto.num(100, 1)))
        rig.receive(reply(12, 9, SEDENTARY))
        rig.receive(reply(21, 13, "08 00"))
        now += 5000
        rig.receive(reply(42, 38, GOALS))

        val read = get(rig)
        val s = read.getJSONObject("settings")
        check(s.getString("hr_interval") == "10min" && s.getInt("hr_high_alert") == 120 && s.getInt("hr_low_alert") == 45, "HR interval and alerts")
        check(s.getBoolean("sleep_advanced_monitoring") && s.getBoolean("sleep_breathing_quality"), "Sleep switches")
        check(s.isNull("irregular_rhythm_alert") && !s.getBoolean("sleep_respiratory_rate"), "Tri-state: 0 is null, 2 is false")
        check(s.getString("spo2_mode") == "all_day" && s.getInt("spo2_low_alert") == 90, "SpO2")
        check(s.getBoolean("sedentary_reminder") && s.getString("sedentary_start") == "06:00" && s.getString("sedentary_end") == "22:00" && s.isNull("sedentary_quiet_start"), "Sedentary reminder")
        check(!s.getBoolean("goal_notifications") && s.getString("secondary_goal") == "moving", "Goals")
        check(s.isNull("stress_auto") && s.isNull("vitality_seven_day"), "Unsupported and unanswered blocks are null")
        val sup = read.getJSONObject("supported")
        check(sup.getString("heart_rate") == "supported" && sup.getString("stress") == "unsupported" && sup.getString("vitality_score") == "unknown", "Support per block")
        val opts = read.getJSONObject("options")
        check(opts.getJSONArray("spo2_mode").toList() == listOf("off", "all_day") && opts.getJSONArray("secondary_goal").toList() == listOf("moving", "standing"), "Options from the watch")
        check(read.getString("read_at") == "2023-11-14T22:13:25Z", "read_at is the latest reply")
        val st = rig.status().getJSONObject("watch_settings")
        check(st.getInt("supported_count") == 5 && st.getString("read_at") == "2023-11-14T22:13:25Z", "Status")
        check(rig.logs.contains("Watch has no stress settings (status 1)"), "Rejection logged")

        // Edits change only their fields; unknown field 6 and the rest of the reply survive.
        val hrEdit = put(rig, JSONObject().put("hr_interval", "smart").put("hr_low_alert", 0))
        check(hrEdit.first == 200 && hrEdit.second.getJSONObject("verified").isNull("heart_rate"), "PUT answers before the re-read")
        val newHr = "08 00 10 00 18 01 20 78 2a 02 08 01 30 05 38 01 42 04 08 00 10 2d 48 01 58 02"
        check(rig.take() == listOf(h("08 08 10 0b 52 1c 42 1a $newHr"), h("0808 100a")), "HR write, then re-read")
        rig.receive(reply(10, 8, newHr))
        val after = get(rig)
        check(after.getJSONObject("verified").getBoolean("heart_rate"), "Re-read matches: verified")
        check(after.getJSONObject("settings").getString("hr_interval") == "smart" && after.getJSONObject("settings").getInt("hr_low_alert") == 0, "Cache follows the re-read")

        put(rig, JSONObject().put("hr_interval", "off").put("sleep_respiratory_rate", true).put("hr_high_alert", 150))
        check(rig.take().first() == h("08 08 10 0b 52 1d 42 1b 08 01 10 00 18 01 20 96 01 2a 02 08 01 30 05 38 01 42 04 08 00 10 2d 48 01 58 01"), "Off keeps the interval; 150 is a two-byte varint")
        rig.receive(reply(10, 8, newHr))
        check(!get(rig).getJSONObject("verified").getBoolean("heart_rate"), "Re-read that disagrees: not verified")

        put(rig, JSONObject().put("hr_interval", "30min"))
        rig.take()
        rig.receive(Proto.command(8, 11, Proto.num(100, 3)))
        check(!get(rig).getJSONObject("verified").getBoolean("heart_rate") && rig.logs.contains("Watch rejected the heart_rate settings with status 3"), "SET rejection: not verified")
        rig.receive(Proto.command(8, 11))
        check(rig.logs.count { it.startsWith("Watch rejected") } == 1, "A status-0 SET reply is quiet")

        for ((body, error) in listOf(
            JSONObject().put("hr_high_alert", 105) to "hr_high_alert must be one of 0, 100, 110, 120, 130, 140, 150",
            JSONObject().put("hr_interval", "5min") to "hr_interval must be one of off, smart, 1min, 10min, 30min",
            JSONObject().put("hr_interval", "smart").put("irregular_rhythm_alert", true) to "The watch does not offer irregular_rhythm_alert",
            JSONObject().put("spo2_mode", "sleep") to "spo2_mode must be one of off, all_day",
            JSONObject().put("spo2_low_alert", "90") to "spo2_low_alert must be one of 0, 80, 85, 90",
            JSONObject().put("sedentary_start", "24:00") to "sedentary_start must be a time HH:MM",
            JSONObject().put("goal_notifications", 1) to "goal_notifications must be true or false",
            JSONObject().put("stress_auto", true) to "The watch has not reported its stress settings on this connection",
            JSONObject().put("vitality_seven_day", true) to "The watch has not reported its vitality_score settings on this connection",
            JSONObject().put("bogus", 1) to "Unknown watch setting bogus",
            JSONObject().put("hr_low_alert", JSONObject.NULL) to "hr_low_alert must be one of 0, 40, 45, 50",
            JSONObject() to "Send at least one setting",
        )) {
            val (code, b) = put(rig, body)
            check(code == 400 && b.getString("error") == error, "400: $error (got $code ${b.optString("error")})")
        }
        check(rig.sent().isEmpty(), "Invalid writes send nothing, even with a valid field beside them")

        put(rig, JSONObject().put("spo2_low_alert", 0))
        check(rig.take() == listOf(h("08 08 10 09 52 0e 3a 0c 08 01 10 02 18 00 22 04 08 00 10 5a"), h("0808 1008")), "SpO2 alert off keeps the threshold")
        // The cache still holds the alert on until the read-back answers, so a second edit would turn it back on.
        val early2 = put(rig, JSONObject().put("spo2_mode", "off"))
        check(early2.first == 400 && early2.second.getString("error") == "The watch has not confirmed the last spo2 change; try again shortly", "Edit of an unconfirmed block is refused")
        check(rig.take() == listOf(h("0808 1008")), "and the block is read again, in case its read-back was lost")
        rig.receive(reply(8, 7, "08 01 10 02 18 00 22 04 08 00 10 5a"))
        put(rig, JSONObject().put("spo2_mode", "off"))
        check(rig.take().first() == h("08 08 10 09 52 0e 3a 0c 08 01 10 00 18 00 22 04 08 00 10 5a"), "SpO2 mode from the confirmed reply keeps the alert off")

        put(rig, JSONObject().put("sedentary_quiet_start", "12:30"))
        check(rig.take().first() == h("08 08 10 0d 52 16 4a 14 08 01 12 04 08 06 10 00 1a 04 08 16 10 00 32 04 08 0c 10 1e"), "New sub-message lands in field order with both required fields")

        put(rig, JSONObject().put("goal_notifications", true))
        check(rig.take().first() == h("08 08 10 16 52 06 6a 04 08 01 10 01"), "Goal notification adds GB's unknown2 = 1")

        put(rig, JSONObject().put("secondary_goal", "standing"))
        check(rig.take().first() == h("08 08 10 2b 52 17 b2 02 14 0a 02 08 01 0a 02 08 04 12 02 08 01 12 02 08 03 12 02 08 04"), "Secondary goal swaps 3 for 4, echoes supported")

        // A later unsolicited config reply replaces the cache.
        rig.receive(reply(14, 10, "08 01"))
        check(get(rig).getJSONObject("settings").getBoolean("stress_auto"), "Stress read after all")
        put(rig, JSONObject().put("relax_reminder", true))
        check(rig.take().first() == h("08 08 10 0f 52 0a 52 08 08 01 12 04 08 01 10 00"), "Relax reminder created with GB's unknown2 = 0")

        rig.disconnect()
        val gone = get(rig)
        check(gone.getJSONObject("supported").getString("heart_rate") == "unknown" && gone.getJSONObject("settings").isNull("hr_interval") && gone.getJSONObject("verified").length() == 0, "Disconnect forgets this connection's reads")
        check(put(rig, JSONObject().put("hr_interval", "smart")).first == 400, "And refuses writes")
        rig.receive(reply(10, 8, HR))
        rig.connect()
        rig.take()
        check(put(rig, JSONObject().put("hr_interval", "smart")).first == 400, "A reply from before the reconnect does not count")

        // A full send window: reads wait for an ack.
        val busy = FeatureRig(HealthSettingsFeature({ now }, verifyWaitMs = 0))
        busy.watch.refuse = true
        busy.connect()
        check(busy.sent().isEmpty(), "Refused reads are kept")
        busy.watch.refuse = false
        busy.ack()
        check(busy.take() == READS, "and sent on the next ack")
        busy.receive(reply(21, 13, "08 00"))
        busy.watch.refuse = true
        val refused = put(busy, JSONObject().put("goal_notifications", true))
        check(refused.first == 503 && refused.second.getString("error") == "Could not send the goal_notification settings to the watch; try again", "Refused write is a 503")
        busy.watch.refuse = false
        check(get(busy).getJSONObject("verified").length() == 0, "and leaves no pending verification")

        // The real wait: PUT returns once the re-read arrives, or when the wait runs out.
        val waiting = FeatureRig(HealthSettingsFeature({ now }, verifyWaitMs = 3000))
        waiting.connect()
        waiting.receive(reply(21, 13, "08 00"))
        var result: Pair<Int, JSONObject>? = null
        val t = Thread { result = put(waiting, JSONObject().put("goal_notifications", true)) }
        val started = System.nanoTime()
        t.start()
        Thread.sleep(200)
        waiting.receive(reply(21, 13, "08 01 10 01"))
        t.join()
        check((System.nanoTime() - started) / 1_000_000 < 2500, "The re-read ends the wait")
        check(result!!.first == 200 && result!!.second.getJSONObject("verified").getBoolean("goal_notification"), "PUT reports it verified")
        val timeout = FeatureRig(HealthSettingsFeature({ now }, verifyWaitMs = 100))
        timeout.connect()
        timeout.receive(reply(21, 13, "08 00"))
        val late = put(timeout, JSONObject().put("goal_notifications", true))
        check(late.first == 200 && late.second.getJSONObject("verified").isNull("goal_notification"), "No re-read: verified null")

        // One PUT at a time: a second one while the first waits for its read-back is a 409.
        val first = FeatureRig(HealthSettingsFeature({ now }, verifyWaitMs = 3000))
        first.connect()
        first.receive(reply(21, 13, "08 00"))
        first.receive(reply(10, 8, HR))
        var one: Pair<Int, JSONObject>? = null
        val t1 = Thread { one = put(first, JSONObject().put("goal_notifications", true)) }
        t1.start()
        while (first.watch.sent.size < READS.size + 2) Thread.sleep(2)
        val two = put(first, JSONObject().put("hr_interval", "smart"))
        check(two.first == 409 && two.second.getString("error") == "Another watch operation is in progress", "Concurrent PUT is a 409: $two")
        first.receive(reply(21, 13, "08 01 10 01"))
        t1.join()
        check(one!!.first == 200, "The first PUT still finishes")

        // The watch restarts its session between two blocks of one PUT: the second never reaches the new session.
        val split = FeatureRig(HealthSettingsFeature({ now }, verifyWaitMs = 0))
        split.connect()
        split.receive(reply(21, 13, "08 00"))
        split.receive(reply(10, 8, HR))
        split.take()
        split.watch.onSend = {
            if (split.watch.sent.size == 2) {
                split.watch.onSend = null
                split.disconnect()
                split.connect()
            }
        }
        val crossed = put(split, JSONObject().put("goal_notifications", true).put("hr_interval", "smart"))
        check(crossed.first == 503 && crossed.second.getString("error") == "The watch disconnected during the write", "Write crossing a reconnect fails: $crossed")
        check(split.take() == listOf(h("08 08 10 16 52 06 6a 04 08 01 10 01"), h("0808 1015")) + READS, "Only the first block went out, before the reconnect's reads")

        // A read-back that never comes: the block is read again after 10 s, and the answer unlocks it.
        val lost = FeatureRig(HealthSettingsFeature({ now }, verifyWaitMs = 0))
        lost.connect()
        lost.receive(reply(21, 13, "08 00"))
        lost.take()
        put(lost, JSONObject().put("goal_notifications", true))
        check(lost.take() == listOf(h("08 08 10 16 52 06 6a 04 08 01 10 01"), h("0808 1015")) && lost.watch.delays() == listOf(10_000L), "Write, read-back, 10 s timer")
        lost.fire()
        check(lost.take() == listOf(h("0808 1015")), "No read-back by then: read again")
        lost.receive(reply(21, 13, "08 01 10 01"))
        check(put(lost, JSONObject().put("goal_notifications", false)).first == 200, "The answer unlocks the block")
        lost.take()
        lost.receive(reply(21, 13, "08 00 10 01"))
        lost.fire()
        check(lost.sent().isEmpty(), "A confirmed block is not read again")
        put(lost, JSONObject().put("goal_notifications", true))
        lost.disconnect()
        lost.connect()
        lost.take()
        lost.fire()
        check(lost.sent().isEmpty(), "Nor a block written in an earlier session")
        lost.receive(reply(21, 13, "08 00 10 01"))
        put(lost, JSONObject().put("goal_notifications", true))
        lost.take()
        lost.watch.refuse = true
        put(lost, JSONObject().put("goal_notifications", false))
        put(lost, JSONObject().put("goal_notifications", false))
        lost.watch.refuse = false
        lost.ack()
        check(lost.take() == listOf(h("0808 1015")), "A refused re-read waits for room and goes once")

        // Two delayed replies: the read-back of write A and the read a refused edit asked for. The first confirms A; the
        // second answers a read sent before write B, so it neither confirms B nor reopens the block to edits.
        val delayed = FeatureRig(HealthSettingsFeature({ now }, verifyWaitMs = 0))
        delayed.connect()
        delayed.receive(reply(21, 13, "08 00"))
        delayed.take()
        put(delayed, JSONObject().put("goal_notifications", true))
        check(delayed.take() == listOf(h("08 08 10 16 52 06 6a 04 08 01 10 01"), h("0808 1015")), "Write A, read-back")
        check(put(delayed, JSONObject().put("goal_notifications", false)).first == 400 && delayed.take() == listOf(h("0808 1015")), "An early edit is refused and reads again")
        delayed.receive(reply(21, 13, "08 01 10 01"))
        check(get(delayed).getJSONObject("verified").getBoolean("goal_notification"), "The first reply confirms A")
        check(put(delayed, JSONObject().put("goal_notifications", false)).first == 200, "Write B")
        check(delayed.take() == listOf(h("08 08 10 16 52 06 6a 04 08 00 10 01"), h("0808 1015")), "B from A's confirmed reply, then its read-back")
        delayed.receive(reply(21, 13, "08 01 10 01"))
        val stale = get(delayed)
        check(stale.getJSONObject("verified").isNull("goal_notification") && delayed.logs.last() == "Ignored a goal_notification settings reply that predates the last write", "The second reply predates B: B stays unverified")
        val blocked = put(delayed, JSONObject().put("goal_notifications", true))
        check(blocked.first == 400 && blocked.second.getString("error") == "The watch has not confirmed the last goal_notification change; try again shortly", "and the block stays closed to edits")
        delayed.take()
        delayed.receive(reply(21, 13, "08 00 10 01"))
        val confirmed = get(delayed)
        check(confirmed.getJSONObject("verified").getBoolean("goal_notification") && !confirmed.getJSONObject("settings").getBoolean("goal_notifications"), "B's own read-back confirms it")
        delayed.receive(reply(21, 13, "08 00 10 01"))
        check(put(delayed, JSONObject().put("goal_notifications", true)).first == 200, "The refused edit's read answers last; the block is open again")

        println("Health settings feature: $assertions assertions passed")
    }
}
