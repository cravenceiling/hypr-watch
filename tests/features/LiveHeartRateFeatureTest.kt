package local.hyprwatch

import org.json.JSONObject

object LiveHeartRateFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private const val START = "0808102d"
    private const val STOP = "0808102e"

    private fun push(steps: Long, calories: Long, bpm: Long) =
        Proto.command(8, 47, Proto.bytes(10, Proto.bytes(39, Proto.join(Proto.num(1, steps), Proto.num(2, calories), Proto.num(3, 9), Proto.num(4, bpm)))))

    private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun post(rig: FeatureRig, on: Any) = rig.call("POST", "/api/heart-rate/live", JSONObject().put("on", on))

    private fun live(rig: FeatureRig) = rig.call("GET", "/api/heart-rate/live").second

    @JvmStatic
    fun main(args: Array<String>) {
        var now = 1_700_000_000_000L
        val rig = FeatureRig(LiveHeartRateFeature({ now }))

        val offline = post(rig, true)
        check(offline.first == 400 && offline.second.getString("error") == "Connect the watch first", "Start while disconnected is a 400")
        check(post(rig, "yes").first == 400 && rig.call("POST", "/api/heart-rate/live").first == 400, "on must be a boolean")
        check(rig.status().getJSONObject("live_heart_rate").toString() == """{"on":false}""", "Status starts off")

        rig.connect()
        check(rig.take().isEmpty(), "Connect sends nothing")
        val (code, started) = post(rig, true)
        check(code == 200 && started.getBoolean("on") && started.isNull("bpm") && started.isNull("at"), "Started, no reading yet")
        check(rig.take() == listOf(START), "8/45 starts the stream")
        check(rig.watch.delays() == listOf(120_000L), "Two-minute lease")
        post(rig, true)
        check(rig.take().isEmpty() && rig.watch.delays() == listOf(120_000L), "Renewal sends nothing and replaces the lease")

        rig.receive(push(1234, 56, 72))
        val first = live(rig)
        check(first.getInt("bpm") == 72 && first.getLong("steps") == 1234L && first.getLong("calories") == 56L && first.getString("at") == "2023-11-14T22:13:20Z", "Latest push")
        now += 1000
        rig.receive(push(1240, 57, 8))
        val warm = live(rig)
        check(warm.isNull("bpm") && warm.getLong("steps") == 1240L && warm.getString("at") == "2023-11-14T22:13:21Z", "10 bpm or less: measuring")
        check(rig.logs.none { it.contains("72") || it.contains("1234") }, "Readings are never logged")
        check(rig.logs.filter { it.startsWith("Live heart rate push carries") } == listOf("Live heart rate push carries RealTimeStats fields [1, 2, 3, 4]"), "Field numbers logged once per session: ${rig.logs}")

        // As firmware 2.2.105 sends it: a heart rate but no steps or calories fields.
        // 8/47 { health(10) { realTimeStats(39) { 3: 9, 4: 84 } } }
        now += 1000
        rig.receive(bytes("0808102f5207ba020418092054"))
        val sparse = live(rig)
        check(sparse.getInt("bpm") == 84 && sparse.isNull("steps") && sparse.isNull("calories"), "Absent steps and calories are null, not 0: $sparse")
        check(rig.logs.count { it.startsWith("Live heart rate push carries") } == 1, "Still logged once per session")
        // realTimeStats { 1: 0, 2: 0, 4: 0 }: zeros the watch does send stay 0.
        rig.receive(bytes("0808102f5209ba0206080010002000"))
        val zeros = live(rig)
        check(zeros.isNull("bpm") && zeros.getLong("steps") == 0L && zeros.getLong("calories") == 0L, "An explicit 0 stays 0: $zeros")
        now += 10_001
        val stale = live(rig)
        check(stale.getBoolean("on") && stale.isNull("bpm") && stale.isNull("steps") && stale.isNull("calories") && stale.isNull("at"), "Nothing within 10 s: nulls")

        rig.fire()
        check(rig.take() == listOf(STOP) && !rig.status().getJSONObject("live_heart_rate").getBoolean("on"), "The lease lapses: 8/46")
        rig.receive(push(1300, 60, 70))
        check(rig.take() == listOf(STOP) && !live(rig).getBoolean("on"), "A push nobody holds stops the stream")
        check(post(rig, false).first == 200 && rig.take().isEmpty(), "Stopping while off sends nothing")

        post(rig, true)
        rig.take()
        val stopped = post(rig, false)
        check(stopped.first == 200 && !stopped.second.getBoolean("on") && rig.take() == listOf(STOP) && rig.watch.delays().isEmpty(), "Stop sends 8/46 and drops the lease")

        post(rig, true)
        rig.receive(push(1400, 61, 80))
        rig.take()
        rig.disconnect()
        check(!rig.status().getJSONObject("live_heart_rate").getBoolean("on") && rig.watch.delays().isEmpty() && rig.sent().isEmpty(), "Disconnect ends the hold silently")
        check(live(rig).isNull("bpm"), "and forgets the reading")
        rig.connect()
        check(!live(rig).getBoolean("on") && rig.take().isEmpty(), "Reconnect does not restart it")
        rig.receive(push(1500, 62, 81))
        check(rig.take() == listOf(STOP) && rig.logs.count { it.startsWith("Live heart rate push carries") } == 2, "The watch's leftover stream is stopped; the new session logs its fields again")

        rig.watch.refuse = true
        val refused = post(rig, true)
        check(refused.first == 503 && refused.second.getString("error") == "Could not start live heart rate; try again", "A refused start is a 503")
        rig.watch.refuse = false
        check(!rig.status().getJSONObject("live_heart_rate").getBoolean("on") && rig.watch.delays().isEmpty(), "and holds nothing")

        // A lease from before a renewal never stops the new one.
        post(rig, true)
        rig.take()
        val oldLease = ArrayList(rig.watch.delays())
        post(rig, true)
        check(oldLease.size == 1 && rig.watch.delays().size == 1, "Renewal cancels the old timer")
        println("Live heart rate feature: $assertions assertions passed")
    }
}
