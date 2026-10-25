package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject

/** ContactsFeature through FeatureRig: 21/7 pushes, the routes, 21/2 lookups and replies. */
object ContactsFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun contacts(vararg c: Pair<String, String>): JSONObject =
        JSONObject().put("contacts", JSONArray().also { a -> c.forEach { a.put(JSONObject().put("name", it.first).put("number", it.second)) } })

    private const val ANN = "08151007ba0111220f0a0d0a03416e6e12062b3120353535"
    private const val ANN_BO = "08151007ba011c221a0a0d0a03416e6e12062b31203535350a090a02426f1203393939"
    private const val EMPTY = "08151007ba01022200"

    @JvmStatic
    fun main(args: Array<String>) {
        var now = 1000L
        val rig = FeatureRig(ContactsFeature { now })

        rig.connect()
        check(rig.take().isEmpty(), "Never configured: the watch's list is left alone")
        val s0 = rig.status().getJSONObject("contacts")
        check(s0.getInt("count") == 0 && s0.isNull("synced_at") && s0.isNull("supported"), "Empty status, support unknown")
        val g0 = rig.call("GET", "/api/contacts")
        check(g0.first == 200 && !g0.second.getBoolean("configured") && g0.second.getInt("max") == 10 && g0.second.getJSONArray("contacts").length() == 0, "GET before any PUT")

        rig.disconnect()
        val offline = rig.call("PUT", "/api/contacts", contacts(" Ann " to " +1 555 "))
        check(offline.first == 200 && !offline.second.getBoolean("sent_to_watch"), "Offline PUT saves without sending")
        check(JSONObject(rig.settings.values.getValue("feature.contacts.list")).similar(contacts("Ann" to "+1 555")), "Saved trimmed under the feature prefix")
        check(rig.status().getJSONObject("contacts").getInt("count") == 1, "Status counts the stored list")

        now = 2000L
        rig.connect()
        check(rig.take() == listOf(ANN), "Connect pushes the whole list")
        check(rig.status().getJSONObject("contacts").getLong("synced_at") == 2000L, "synced_at is the push time")

        val bad = listOf(
            JSONObject() to "Send contacts as a list of {name, number}",
            JSONObject().put("contacts", "Ann") to "Send contacts as a list of {name, number}",
            contacts(*Array(11) { "N$it" to "10$it" }) to "The watch holds at most 10 favourite contacts",
            JSONObject().put("contacts", JSONArray().put("Ann")) to "Contact 1: send {name, number}",
            contacts("Ann" to "1", "" to "2") to "Contact 2: use a name of 1 to 32 characters",
            contacts("   " to "2") to "Contact 1: use a name of 1 to 32 characters",
            contacts("x".repeat(33) to "2") to "Contact 1: use a name of 1 to 32 characters",
            contacts("A\nB" to "2") to "Contact 1: use a name of 1 to 32 characters",
            JSONObject().put("contacts", JSONArray().put(JSONObject().put("name", "Ann").put("number", 555))) to
                "Contact 1: use a number of digits, spaces and an optional leading +, at most 20 digits",
            contacts("Ann" to "555-1234") to "Contact 1: use a number of digits, spaces and an optional leading +, at most 20 digits",
            contacts("Ann" to "1+5") to "Contact 1: use a number of digits, spaces and an optional leading +, at most 20 digits",
            contacts("Ann" to "") to "Contact 1: use a number of digits, spaces and an optional leading +, at most 20 digits",
            contacts("Ann" to "1".repeat(21)) to "Contact 1: use a number of digits, spaces and an optional leading +, at most 20 digits",
            contacts("Ann" to "+1 555", "Bo" to "1555") to "Contact 2: that number is already in the list",
        )
        for ((body, error) in bad) {
            val r = rig.call("PUT", "/api/contacts", body)
            check(r.first == 400 && r.second.getString("error") == error, "400: $error (got ${r.second})")
        }
        check(rig.take().isEmpty() && rig.status().getJSONObject("contacts").getInt("count") == 1, "Bad PUTs change nothing")
        check(rig.call("PUT", "/api/contacts", contacts(*Array(10) { "N$it" to "10$it" })).first == 200, "Ten contacts fit")
        check(rig.call("PUT", "/api/contacts", contacts("x".repeat(32) to "1".repeat(20))).first == 200, "Limits are inclusive")
        rig.take()

        now = 3000L
        val put = rig.call("PUT", "/api/contacts", contacts("Ann" to "+1 555", "Bo" to "999"))
        check(put.first == 200 && put.second.getBoolean("sent_to_watch") && put.second.getLong("synced_at") == 3000L, "Connected PUT sends at once")
        check(rig.take() == listOf(ANN_BO), "Two entries in order")
        check(put.second.getJSONArray("contacts").getJSONObject(1).getString("number") == "999", "PUT answers the stored list")

        rig.watch.refuse = true
        now = 4000L
        val refused = rig.call("PUT", "/api/contacts", contacts("Ann" to "+1 555"))
        check(refused.first == 200 && !refused.second.getBoolean("sent_to_watch") && rig.take().isEmpty(), "A full window: saved, not sent")
        check(rig.status().getJSONObject("contacts").getLong("synced_at") == 3000L, "synced_at stays at the last push")
        rig.watch.refuse = false
        rig.ack()
        check(rig.take() == listOf(ANN), "The refused push goes out on the next ack")
        rig.ack()
        check(rig.take().isEmpty(), "Only once")

        val empty = rig.call("PUT", "/api/contacts", contacts())
        check(empty.first == 200 && empty.second.getBoolean("sent_to_watch") && rig.take() == listOf(EMPTY), "An empty list clears the watch")
        check(empty.second.getBoolean("configured") && empty.second.getInt("count") == 0, "Configured but empty")
        rig.disconnect()
        rig.connect()
        check(rig.take() == listOf(EMPTY), "A configured empty list is pushed on connect too")

        // Replies to 21/7 are not known to exist; one would settle support.
        rig.receive(Proto.command(21, 7))
        check(rig.status().getJSONObject("contacts").getBoolean("supported"), "Status 0 reply: supported")
        rig.receive(Proto.command(21, 7, Proto.num(100, 3)))
        check(!rig.status().getJSONObject("contacts").getBoolean("supported"), "Non-zero status: unsupported")
        check(rig.logs.last() == "Watch rejected the favourite contacts with status 3", "Rejection logged")

        rig.call("PUT", "/api/contacts", contacts("Ann" to "+44 20 7946 0958"))
        rig.take()
        rig.receive(Proto.command(21, 2, Proto.bytes(23, Proto.text(2, "020 7946 0958"))))
        check(
            rig.take() == listOf("08151003ba01161a140a03416e6e120d303230203739343620303935" + "38"),
            "A favourite's number with a national prefix gets its name",
        )
        rig.receive(Proto.command(21, 2, Proto.bytes(23, Proto.text(2, "12345"))))
        check(rig.take() == listOf("08151003ba010b1a090a0012053132333435"), "An unknown caller gets an empty name")
        rig.receive(Proto.command(21, 2, Proto.bytes(23, Proto.text(2, "0000000"))))
        check(rig.take() == listOf("08151003ba010d1a0b0a001207" + "30303030303030"), "Zeros alone match nothing")
        rig.receive(Proto.command(21, 2))
        check(rig.take().isEmpty(), "A lookup without a number is ignored")

        rig.disconnect()
        check(rig.call("PUT", "/api/contacts", contacts("Cy" to "123")).second.getBoolean("sent_to_watch").not() && rig.take().isEmpty(), "Disconnected mid-use: nothing sent")
        rig.connect()
        check(rig.take() == listOf("08151007ba010d220b0a090a024379120331323" + "3"), "The change made while away goes on reconnect")

        val personal = listOf("Ann", "Bo", "Cy", "555", "999", "7946", "123")
        check(rig.logs.none { line -> personal.any { line.contains(it) } }, "Logs carry no names or numbers: ${rig.logs}")
        check(rig.logs.contains("Sent 1 favourite contacts"), "Logs carry counts")
        println("Contacts feature: $assertions assertions passed")
    }
}
