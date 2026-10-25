package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject

/** QuickRepliesFeature through FeatureRig: 7/9 reads, 7/12 writes and their verification. */
object QuickRepliesFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private const val GET = "080710094a024001"

    /** ["OK"] padded to min 3, max 5. */
    private const val SET_OK = "0807100c4a104a0e080312024f4b12012d12012d1805"

    /** The watch's 7/9 answer: Notification(9).cannedMessages(9) = ReplyMsg{min, reply[], max}. */
    private fun list(min: Int, max: Int, vararg replies: String, status: Long? = null): ByteArray {
        val msg = Proto.join(Proto.num(1, min.toLong()), *replies.map { Proto.text(2, it) }.toTypedArray(), Proto.num(3, max.toLong()))
        val st = status?.let { Proto.num(100, it) } ?: ByteArray(0)
        return Proto.command(7, 9, Proto.bytes(9, Proto.bytes(9, msg)), st)
    }

    private fun replies(vararg r: Any): JSONObject = JSONObject().put("replies", JSONArray(r.toList()))

    private fun FeatureRig.qr(): JSONObject = status().getJSONObject("quick_replies")

    @JvmStatic
    fun main(args: Array<String>) {
        var now = 1000L
        val rig = FeatureRig(QuickRepliesFeature({ now }, 10_000))

        val early = rig.call("PUT", "/api/quick-replies", replies("OK"))
        check(early.first == 400 && early.second.getString("error") == "Connect the watch first", "Disconnected PUT is a 400")

        rig.connect()
        check(rig.take() == listOf(GET), "Connect reads the list with unknown8 = 1")
        check(rig.watch.delays() == listOf(10_000L), "A reply timer is armed")
        val s0 = rig.qr()
        check(s0.getInt("count") == 0 && s0.isNull("read_at") && s0.isNull("supported") && !s0.getBoolean("writable") && s0.isNull("write"), "Unknown until the watch answers")
        val tooSoon = rig.call("PUT", "/api/quick-replies", replies("OK"))
        check(tooSoon.first == 400 && tooSoon.second.getString("error").startsWith("The watch has not reported"), "No write before a read")
        check(rig.take().isEmpty(), "Nothing sent")

        now = 2000L
        rig.receive(list(3, 5, "Busy", "Later"))
        check(rig.watch.delays().isEmpty(), "The reply cancels the timer")
        val s1 = rig.qr()
        check(s1.getInt("count") == 2 && s1.getLong("read_at") == 2000L && s1.getBoolean("supported") && s1.getInt("min") == 3 && s1.getInt("max") == 5 && s1.getBoolean("writable"), "Read lands in status")
        val g = rig.call("GET", "/api/quick-replies")
        check(g.first == 200 && g.second.getJSONArray("replies").toList() == listOf("Busy", "Later") && g.second.getInt("reply_max_chars") == 30, "GET serves the cached list")

        val bad = listOf(
            JSONObject() to "Send replies as a list of strings",
            JSONObject().put("replies", "OK") to "Send replies as a list of strings",
            replies("a", "b", "c", "d", "e", "f") to "The watch holds at most 5 quick replies",
            replies("OK", "") to "Reply 2: use 1 to 30 characters on one line",
            replies("  ") to "Reply 1: use 1 to 30 characters on one line",
            replies("x".repeat(31)) to "Reply 1: use 1 to 30 characters on one line",
            replies("a\nb") to "Reply 1: use 1 to 30 characters on one line",
            replies(7) to "Reply 1: use 1 to 30 characters on one line",
        )
        for ((body, error) in bad) {
            val r = rig.call("PUT", "/api/quick-replies", body)
            check(r.first == 400 && r.second.getString("error") == error, "400: $error (got ${r.second})")
        }
        check(rig.take().isEmpty() && rig.qr().isNull("write"), "Bad PUTs send nothing")

        now = 3000L
        val put = rig.call("PUT", "/api/quick-replies", replies(" OK "))
        check(put.first == 200 && put.second.getJSONObject("write").getString("state") == "pending" && put.second.getJSONObject("write").getLong("at") == 3000L, "Write is pending")
        check(rig.take() == listOf(SET_OK, GET), "Write pads to min with \"-\", then reads back")
        rig.receive(list(3, 5, "OK", "-", "-"))
        check(rig.qr().getJSONObject("write").getString("state") == "verified" && rig.qr().getInt("count") == 3, "Read-back matches: verified")

        rig.call("PUT", "/api/quick-replies", replies("OK"))
        rig.take()
        rig.receive(list(3, 5, "Busy", "-", "-"))
        check(rig.qr().getJSONObject("write").getString("state") == "mismatch", "Read-back differs: mismatch")

        rig.call("PUT", "/api/quick-replies", replies("OK"))
        rig.take()
        rig.fire()
        check(rig.qr().getJSONObject("write").getString("state") == "no_reply", "No read-back: no_reply")
        check(rig.logs.last() == "Watch did not report its quick replies", "Timeout logged")
        rig.receive(list(3, 5, "OK", "-", "-"))
        check(rig.qr().getJSONObject("write").getString("state") == "no_reply", "A late list does not rewrite a finished write")

        rig.call("PUT", "/api/quick-replies", replies("OK"))
        rig.take()
        rig.receive(Proto.command(7, 12, Proto.num(100, 1)))
        check(rig.qr().getJSONObject("write").getString("state") == "rejected", "7/12 with a status: rejected")
        rig.receive(Proto.command(7, 12))
        check(rig.qr().getJSONObject("write").getString("state") == "rejected", "A 7/12 status 0 changes nothing")

        val five = rig.call("PUT", "/api/quick-replies", replies("a", "b", "c", "d", "e"))
        check(five.first == 200, "Max replies fit")
        check(rig.take().first() == "0807100c4a154a13080312016112016212016312016412016518" + "05", "No padding when min is met")

        rig.watch.refuse = true
        val refused = rig.call("PUT", "/api/quick-replies", replies("OK"))
        check(refused.first == 503 && refused.second.getString("error") == "The watch did not take the quick replies; try again", "A refused write is a 503")
        rig.watch.refuse = false

        // Disconnect mid-write.
        rig.call("PUT", "/api/quick-replies", replies("OK"))
        rig.take()
        rig.disconnect()
        check(rig.qr().getJSONObject("write").getString("state") == "disconnected", "Disconnect ends the pending write")
        check(!rig.qr().getBoolean("writable") && rig.watch.delays().isEmpty(), "Not writable, timer cancelled")
        check(rig.call("GET", "/api/quick-replies").second.getJSONArray("replies").length() == 3, "The cache survives a disconnect")

        rig.connect()
        check(rig.take() == listOf(GET), "Each connect reads again")
        val stale = rig.call("PUT", "/api/quick-replies", replies("OK"))
        check(stale.first == 400 && stale.second.getString("error").startsWith("The watch has not reported"), "A read from an earlier connection does not allow writes")
        rig.fire()
        check(rig.qr().getBoolean("supported") && rig.qr().getInt("count") == 3, "No reply keeps the last known list and support")

        rig.receive(list(3, 5, "Busy", status = 2))
        check(!rig.qr().getBoolean("supported") && rig.qr().getInt("count") == 3, "Non-zero status: unsupported, cache kept")
        val unsupported = rig.call("PUT", "/api/quick-replies", replies("OK"))
        check(unsupported.first == 400 && unsupported.second.getString("error") == "The watch has not reported its quick replies yet; try again in a few seconds", "Still no successful read this connection")

        rig.receive(Proto.command(7, 9))
        check(!rig.qr().getBoolean("supported"), "A reply without the list: unsupported")
        rig.receive(list(0, 0))
        check(!rig.qr().getBoolean("supported") && !rig.qr().getBoolean("writable"), "max 0: no quick replies on this watch")
        val zero = rig.call("PUT", "/api/quick-replies", replies("OK"))
        check(zero.first == 400 && zero.second.getString("error") == "This watch does not accept quick replies", "No writes when max is 0")

        rig.receive(list(0, 20, "Busy"))
        val many = rig.call("PUT", "/api/quick-replies", replies(*Array(17) { "r$it" }))
        check(many.first == 400 && many.second.getString("error") == "The watch holds at most 16 quick replies", "Slots are capped at 16")
        rig.take()
        rig.call("PUT", "/api/quick-replies", replies())
        check(rig.take() == listOf("0807100c4a064a0408001814", GET), "An empty list with min 0 is sent as is")

        val personal = listOf("Busy", "Later", "OK")
        check(rig.logs.none { line -> personal.any { line.contains(it) } }, "Logs carry no reply text: ${rig.logs}")
        println("Quick replies feature: $assertions assertions passed")
    }
}
