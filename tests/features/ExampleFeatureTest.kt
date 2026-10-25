package local.hyprwatch

import org.json.JSONObject

/** The docs/watch-features.md example: one feature, tested through FeatureRig only. */
object ExampleFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun hex(b: ByteArray): String = HealthCapture.hex(b)

    @JvmStatic
    fun main(args: Array<String>) {
        val rig = FeatureRig(ExampleFeature())
        rig.answer(99, 2) { Proto.command(99, 2, Proto.num(3, 41)) }

        rig.connect()
        check(rig.take() == listOf(hex(Proto.command(99, 0, Proto.text(3, "hi"))), hex(Proto.command(99, 2))), "Connect greets, then reads the counter")
        check(rig.status().getJSONObject("example").getLong("counter") == 41L, "The reply lands in status")

        rig.receive(Proto.command(99, 1))
        rig.receive(Proto.command(99, 1))
        val (code, body) = rig.call("GET", "/api/example")
        check(code == 200 && body.getInt("presses") == 2 && body.getString("greeting") == "hi", "Watch presses reach the route")

        val put = rig.call("PUT", "/api/example/greeting/hello")
        check(put.first == 200 && put.second.getBoolean("sent_to_watch"), "New greeting is sent")
        check(rig.settings.values["feature.example.greeting"] == JSONObject().put("text", "hello").toString(), "Saved under the feature's prefix")
        val bad = rig.call("PUT", "/api/example/greeting/${"x".repeat(21)}")
        check(bad.first == 400 && bad.second.getString("error") == "Use a greeting of at most 20 characters", "Bad input is a 400")

        rig.take()
        rig.disconnect()
        check(rig.status().getJSONObject("example").isNull("counter"), "Disconnect clears the counter")
        val offline = rig.call("PUT", "/api/example/greeting/later")
        check(offline.first == 200 && !offline.second.getBoolean("sent_to_watch") && rig.logs.last() == "Dropped a command for example: watch is not connected", "Offline: saved, not sent")
        rig.connect()
        check(rig.take().first() == hex(Proto.command(99, 0, Proto.text(3, "later"))), "The saved greeting goes out on the next connect")
        println("Example feature: $assertions assertions passed")
    }
}
