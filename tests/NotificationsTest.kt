package local.hyprwatch

import org.json.JSONObject
import java.time.ZoneOffset
import java.util.Locale

object NotificationsTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun hex(b: ByteArray): String = b.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }

    private fun check(condition: Boolean, name: String) {
        if (!condition) throw AssertionError(name)
    }

    private fun eq(actual: ByteArray?, expected: String, name: String) {
        val a = actual?.let(::hex)
        if (a != expected) throw AssertionError("$name\n  expected $expected\n  actual   $a")
    }

    private fun cmd(s: String): Map<Int, List<Any>> = Proto.decode(hex(s))

    private fun note(key: String, pkg: String = "com.a", title: String = "Hi", body: String = "Yo", isCall: Boolean = false, postTime: Long = 0) =
        PhoneNotification(key, pkg, "A", title, body, 0, isCall, postTime)

    /** Commands [codec] sends for [e], hex; [accept] says whether the link takes each one. */
    private fun sends(codec: WatchNotifications, e: NotificationEvent, accept: Boolean = true): List<String> {
        val out = ArrayList<String>()
        codec.forward(e) {
            out.add(hex(it))
            accept
        }
        return out
    }

    /** The one command [codec] sends for [e], or null. */
    private fun WatchNotifications.encode(e: NotificationEvent): ByteArray? {
        val out = sends(this, e)
        check(out.size <= 1, "One command per event: $out")
        return out.firstOrNull()?.let(::hex)
    }

    private fun fields(cmd: ByteArray): Map<Int, List<Any>> = Proto.message(Proto.message(Proto.message(Proto.decode(cmd), 9), 3), 1)

    private fun apiError(json: String): String? =
        try {
            NotificationFilter.fromApi("local.hyprwatch", JSONObject(json))
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }

    private const val TS = "320f313937303031303154303030303030" // timestamp(6) = "19700101T000000"

    @JvmStatic
    fun main(args: Array<String>) {
        val utc = ZoneOffset.UTC
        check(WatchNotifications.timestamp(0, utc) == "19700101T000000", "Timestamp format")

        // 7/0: Command{type 7, sub 0, notification(9){notification2(3){notification3(1){...}}}}
        val key = "0|com.a|1|null|10"
        eq(
            WatchNotifications.send(note(key), 1, utc),
            "08071000" + "4a40" + "1a3e" + "0a3c" +
                "0a05636f6d2e61" + "120141" + "1a024869" + "2200" + "2a02596f" + TS + "3801" +
                "6211307c636f6d2e617c317c6e756c6c7c3130" + "6801",
            "Send notification bytes",
        )
        val big = hex(WatchNotifications.send(note(key), -2, utc)!!)
        check("38feffffff0f62" in big && big.startsWith("080710004a44"), "Id 0xFFFFFFFE is a 5-byte uint32 varint")
        var threw = false
        try {
            WatchNotifications.send(note(key), 0, utc)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        check(threw, "Id 0 refused for app notifications")
        eq(
            WatchNotifications.send(note("k", body = ""), 1, utc),
            "08071000" + "4a2e" + "1a2c" + "0a2a" +
                "0a05636f6d2e61" + "120141" + "1a024869" + "2200" + "2a00" + TS + "3801" + "62016b" + "6801",
            "Empty body still sent",
        )

        // 7/1: notificationDismiss(4){notificationId(1){id, package, key}}
        eq(WatchNotifications.delete(1, "com.a", "k"), "08071001" + "4a10" + "220e" + "0a0c" + "0801" + "1205636f6d2e61" + "22016b", "Delete bytes")
        eq(WatchNotifications.endCall(), "08071001" + "4a0d" + "220b" + "0a09" + "0800" + "120570686f6e65", "End call bytes")
        eq(
            WatchNotifications.incomingCall("Bob", "123", 0, utc),
            "08071000" + "4a35" + "1a33" + "0a31" +
                "0a0570686f6e65" + "120570686f6e65" + "1a03426f62" + "2200" + "2a03313233" + TS + "3800" + "4001" + "5800",
            "Incoming call bytes",
        )
        eq(
            WatchNotifications.incomingCall("", "", 0, utc),
            "08071000" + "4a31" + "1a2f" + "0a2d" +
                "0a0570686f6e65" + "120570686f6e65" + "1a013f" + "2200" + "2a013f" + TS + "3800" + "4001" + "5800",
            "Unknown caller shown as ?",
        )

        // Id allocator.
        var ids = NotificationIds(capacity = 2)
        check(ids.idFor("k1", "p") == 1 && ids.idFor("k2", "p") == 2 && ids.idFor("k1", "p") == 1, "Stable id per key")
        check(ids.byId(1)?.key == "k1", "Lookup by id refreshes k1")
        check(ids.idFor("k3", "p") == 3 && ids.byKey("k2") == null && ids.byKey("k1") != null && ids.size() == 2, "LRU eviction")
        ids = NotificationIds(capacity = 4, start = -1)
        check(ids.idFor("a", "p") == -1 && ids.idFor("b", "p") == 1, "Wrap skips 0")
        ids = NotificationIds(capacity = 4, start = 1)
        ids.idFor("a", "p")
        check(ids.idFor("b", "p") == 2, "Sequential ids")
        val seen = HashSet<Int>()
        ids = NotificationIds(capacity = 3, start = -3)
        for (i in 0 until 20) seen.add(ids.idFor("n$i", "p"))
        check(0 !in seen && ids.size() == 3, "Never 0 across wrap, bounded")

        // Codec state: ids map back to keys, watch dismissals are not echoed.
        val codec = WatchNotifications(NotificationIds(capacity = 8, start = -2)) { utc }
        check(hex(codec.encode(NotificationEvent.Posted(note("a")))!!).contains("38feffffff0f"), "First id 0xFFFFFFFE")
        codec.encode(NotificationEvent.Posted(note("b")))
        check(hex(codec.encode(NotificationEvent.Posted(note("c")))!!).contains("3801"), "Third id skips 0")
        val dismiss = codec.decode(1, cmd("08071001" + "4a0e" + "220c" + "0a020801" + "0a0608feffffff0f"))
        check(dismiss == WatchAction.Dismiss(listOf("c", "a")), "Watch dismiss resolves uint32 ids to keys")
        check(codec.encode(NotificationEvent.Removed("a")) == null, "No delete echoed after watch dismiss")
        eq(
            codec.encode(NotificationEvent.Removed("b")),
            "08071001" + "4a14" + "2212" + "0a10" + "08ffffffff0f" + "1205636f6d2e61" + "220162",
            "Phone-side removal of id 0xFFFFFFFF",
        )
        check(codec.encode(NotificationEvent.Removed("never-sent")) == null, "Unknown key sends nothing")
        check(codec.decode(1, cmd("08071001" + "4a06" + "2204" + "0a020809")) == null, "Dismiss of unknown id ignored")
        codec.encode(NotificationEvent.Posted(note("d")))
        check(codec.decode(8, cmd("08071008" + "4a04" + "12020802")) == WatchAction.Open("d"), "Open on phone")
        check(codec.decode(8, cmd("08071008" + "4a04" + "12020863")) == null, "Open of unknown id ignored")
        check(codec.decode(6, cmd("08071006")) == null, "Unhandled subtype ignored")

        // Calls.
        val call = note("call", pkg = "com.dialer", title = "Bob", body = "123", isCall = true, postTime = 100)
        eq(codec.encode(NotificationEvent.Posted(call)), hex(WatchNotifications.incomingCall("Bob", "123", 0, utc)), "Call posted")
        check(codec.encode(NotificationEvent.Posted(call.copy(postTime = 200))) == null, "Call repost not resent")
        check(codec.decode(5, cmd("08071005")) == WatchAction.IgnoreCall, "Ignore call decoded; the phone cannot act on it")
        check(codec.decode(2, cmd("08071002")) == WatchAction.RejectCall(CallRef("call", 200)), "Reject names the ringing call's latest post")
        check(codec.encode(NotificationEvent.Removed("call")) == null, "No end-call after watch reject")
        check(codec.decode(2, cmd("08071002")) == WatchAction.RejectCall(null), "Reject without a known call")
        codec.encode(NotificationEvent.Posted(call))
        eq(codec.encode(NotificationEvent.Removed("call")), hex(WatchNotifications.endCall()), "Call end")
        codec.encode(NotificationEvent.Posted(call))
        codec.disconnected()
        eq(codec.encode(NotificationEvent.Posted(call)), hex(WatchNotifications.incomingCall("Bob", "123", 0, utc)), "Call sent again after a disconnect")

        // Cleaning and truncation.
        check(WatchNotifications.clean(" a\u0007b​c‮\nd\td ", 64) == "abc\nd\td", "Control and format chars stripped, whitespace kept")
        check(WatchNotifications.clean("x".repeat(64), 64) == "x".repeat(64), "At the limit untouched")
        check(WatchNotifications.clean("x".repeat(65), 64) == "x".repeat(63) + "…", "Over the limit ends with an ellipsis")
        val emoji = WatchNotifications.clean("😀".repeat(70), 64)
        check(emoji == "😀".repeat(63) + "…", "Truncation never splits a surrogate pair")
        check(WatchNotifications.clean("a\uD800b", 10) == "ab", "Unpaired surrogate stripped")
        val long = hex(WatchNotifications.send(note("k", title = "t".repeat(100), body = "b".repeat(600)), 1, utc)!!)
        check(("1a42" + "74".repeat(63) + "e280a6") in long, "Title capped at ${WatchNotifications.TITLE_MAX}")
        check(("2a8204" + "62".repeat(511) + "e280a6") in long, "Body capped at ${WatchNotifications.BODY_MAX}")
        check(("1a0141") in hex(WatchNotifications.send(note("k", title = "\u0007 "), 1, utc)!!), "Blank title falls back to app name")

        // Byte bounds: caps count code points, so 4-byte characters are cut further to fit one command.
        check(WatchNotifications.fit("a\u00e9😀", 5) == "a…" && WatchNotifications.fit("abc", 3) == "abc", "fit cuts at code points within a byte budget")
        val wide = WatchNotifications.send(note("k", title = "😀".repeat(64), body = "😀".repeat(512)), 1, utc)!!
        val wideBody = Proto.string(fields(wide), 5)
        check(wide.size == 1018 && wideBody == "😀".repeat(176) + "…", "Body cut so the command fits 1024 bytes: ${wide.size}, ${wideBody.length}")
        val tagKey = "0|com.a|1|" + "t".repeat(300) + "|10"
        val tagged = fields(WatchNotifications.send(note(tagKey), 1, utc)!!)
        check(!tagged.containsKey(12) && Proto.number(tagged, 13, 0) == 1L && Proto.number(tagged, 7, 0) == 1L, "Oversized key dropped; id and open-on-phone kept")
        check(WatchNotifications.send(note("k", pkg = "p." + "x".repeat(1100)), 1, utc) == null, "Command too large even without a body is not sent")
        // The same bounds hold for the delete: a tag past Frames.encode's 65,535 bytes is left out too.
        val hugeKey = "0|com.a|1|" + "t".repeat(70_000) + "|10"
        val tags = WatchNotifications(NotificationIds()) { utc }
        check(sends(tags, NotificationEvent.Posted(note(hugeKey))).size == 1, "Huge-tag notification posted")
        check(
            sends(tags, NotificationEvent.Removed(hugeKey)) == listOf("08071001" + "4a0d" + "220b" + "0a09" + "0801" + "1205636f6d2e61"),
            "Removal of a huge-tag notification sends id and package only",
        )
        check(WatchNotifications.delete(1, "p." + "x".repeat(1100), "k") == null, "Delete too large is not sent")

        // State changes only once the link takes the command, so a refusal (full send window) loses nothing.
        val refused = WatchNotifications(NotificationIds()) { utc }
        val callHex = hex(WatchNotifications.incomingCall("Bob", "123", 0, utc))
        check(sends(refused, NotificationEvent.Posted(call), accept = false) == listOf(callHex), "Call offered, refused")
        check(sends(refused, NotificationEvent.Posted(call.copy(postTime = 200))) == listOf(callHex), "Refused call is sent on its next post")
        check(sends(refused, NotificationEvent.Removed("call"), accept = false) == listOf(hex(WatchNotifications.endCall())), "Call end refused")
        val owed = ArrayList<String>()
        check(!refused.flush { owed.add(hex(it)); false } && owed == listOf(hex(WatchNotifications.endCall())), "End-call still owed after another refusal")
        check(refused.flush { owed.add(hex(it)); true } && owed.size == 2 && refused.flush { owed.add(hex(it)); true } && owed.size == 2, "Owed end-call sent once")
        check(refused.decode(2, cmd("08071002")) == WatchAction.RejectCall(null), "Delivered end-call clears the call")
        check(sends(refused, NotificationEvent.Posted(note("x")), accept = false).size == 1, "Post refused")
        check(sends(refused, NotificationEvent.Posted(note("y"))) == listOf(hex(WatchNotifications.send(note("y"), 1, utc)!!)), "Refused post allocated no id")
        check(sends(refused, NotificationEvent.Removed("x")).isEmpty(), "Never-delivered post has nothing to delete")
        val deleteY = hex(WatchNotifications.delete(1, "com.a", "y")!!)
        check(sends(refused, NotificationEvent.Removed("y"), accept = false) == listOf(deleteY), "Delete refused")
        check(refused.decode(8, cmd("08071008" + "4a04" + "12020801")) == WatchAction.Open("y"), "Refused delete keeps the id")
        check(sends(refused, NotificationEvent.Posted(note("z"))) == listOf(deleteY, hex(WatchNotifications.send(note("z"), 2, utc)!!)), "Owed delete goes first on the next send")
        check(sends(refused, NotificationEvent.Removed("y")).isEmpty(), "Delivered delete forgot the id")
        sends(refused, NotificationEvent.Removed("z"), accept = false)
        refused.disconnected()
        check(sends(refused, NotificationEvent.Posted(note("z", body = "again"))) == listOf(hex(WatchNotifications.send(note("z", body = "again"), 2, utc)!!)), "A repost cancels the owed delete")

        // A new call on the key of one whose end is still owed: the end goes first, then the new caller.
        val reuse = WatchNotifications(NotificationIds()) { utc }
        val endHex = hex(WatchNotifications.endCall())
        sends(reuse, NotificationEvent.Posted(call))
        check(sends(reuse, NotificationEvent.Removed("call"), accept = false) == listOf(endHex), "End-call refused")
        val alice = call.copy(title = "Alice", body = "456", postTime = 300)
        check(sends(reuse, NotificationEvent.Posted(alice)) == listOf(endHex, hex(WatchNotifications.incomingCall("Alice", "456", 0, utc))), "New call on an owed key is sent")
        check(sends(reuse, NotificationEvent.Posted(alice.copy(postTime = 400))).isEmpty(), "Its repost is not resent")

        // If that end-call is refused again, the new caller waits for it instead of being lost.
        val waiting = WatchNotifications(NotificationIds()) { utc }
        sends(waiting, NotificationEvent.Posted(call))
        sends(waiting, NotificationEvent.Removed("call"), accept = false)
        val aliceHex = hex(WatchNotifications.incomingCall("Alice", "456", 0, utc))
        check(sends(waiting, NotificationEvent.Posted(alice), accept = false) == listOf(endHex), "End-call refused, new call not offered yet")
        val acked = ArrayList<String>()
        check(waiting.flush { acked.add(hex(it)); true } && acked == listOf(endHex, aliceHex), "Ack flush sends the end-call, then the new caller")
        check(waiting.decode(2, cmd("08071002")) == WatchAction.RejectCall(CallRef("call", 300)), "The new call is the active one")
        check(sends(waiting, NotificationEvent.Posted(alice.copy(postTime = 500)), accept = false) == listOf(aliceHex), "Refused call is owed")
        check(sends(waiting, NotificationEvent.Removed("call")).isEmpty(), "A call that ends while owed needs no end-call")
        check(sends(waiting, NotificationEvent.Posted(note("n"))) == listOf(hex(WatchNotifications.send(note("n"), 1, utc)!!)), "and is never offered")

        // Filter.
        val plain = NotificationFlags()
        var f = NotificationFilter("local.hyprwatch")
        check(!f.accept(note("k1"), plain, 0), "Default off")
        f = NotificationFilter("local.hyprwatch", setOf("com.a", "local.hyprwatch"))
        check(f.accept(note("k1"), plain, 0), "Allowed package")
        check(!f.accept(note("k2", pkg = "com.b"), plain, 0), "Other package")
        check(!f.accept(note("k3"), NotificationFlags(ongoing = true), 0), "Ongoing ignored")
        check(!f.accept(note("k3"), NotificationFlags(foregroundService = true), 0), "Foreground service ignored")
        check(!f.accept(note("k3"), NotificationFlags(groupSummary = true), 0), "Group summary ignored")
        check(!f.accept(note("k3"), NotificationFlags(localOnly = true), 0), "Local-only ignored")
        check(!f.accept(note("k3", title = " ", body = ""), plain, 0), "Empty notification ignored")
        check(!f.accept(note("k4", pkg = "local.hyprwatch"), plain, 0), "Own package ignored")
        check(!f.accept(note("k1"), plain, 4_999), "Duplicate within window")
        check(f.accept(note("k1", body = "new"), plain, 5_000), "Changed text passes")
        check(f.accept(note("k1", body = "new"), plain, 10_000), "Same text after window passes")
        check(f.accept(note("k5"), plain, 5_000), "Same text, other key passes")
        check(f.accept(note("k7", body = "10%"), NotificationFlags(alertOnce = true), 0), "First alert-once post passes")
        check(!f.accept(note("k7", body = "10%"), NotificationFlags(alertOnce = true), 60_000), "Alert-once update with unchanged text is not resent")
        check(f.accept(note("k7", body = "Done"), NotificationFlags(alertOnce = true), 61_000), "Alert-once update with new text passes")
        check(!f.accept(call, NotificationFlags(ongoing = true, foregroundService = true), 0), "Calls off by default")
        check(!f.wants("com.dialer", true) && f.wants("com.a", false) && !f.wants("com.b", false) && !f.wants("local.hyprwatch", false), "Settings pre-check")
        f = NotificationFilter("local.hyprwatch", f.allowed, calls = true)
        check(f.accept(call, NotificationFlags(ongoing = true, foregroundService = true), 0), "Calls pass when enabled even if ongoing")
        check(f.wants("com.dialer", true) && !f.wants("local.hyprwatch", true), "Calls pre-check")
        val json = f.toJson().toString()
        check(f.toJson().getJSONArray("allowed").toString() == "[\"com.a\",\"local.hyprwatch\"]" && f.toJson().getBoolean("calls"), "Filter JSON: $json")
        f = NotificationFilter.fromJson("local.hyprwatch", json)
        check(f.allowed == setOf("com.a", "local.hyprwatch") && f.calls, "Filter JSON round trip")
        val api = f.toApi { if (it == "com.a") "App A" else it }
        check(
            api.similar(
                JSONObject(
                    "{\"allowed\":[\"com.a\",\"local.hyprwatch\"],\"calls\":true,\"allowed_apps\":" +
                        "[{\"package\":\"com.a\",\"app_name\":\"App A\"},{\"package\":\"local.hyprwatch\",\"app_name\":\"local.hyprwatch\"}]}",
                ),
            ),
            "API settings name every allowed app, unknown labels fall back to the package: $api",
        )
        f = NotificationFilter.fromJson("local.hyprwatch", "{not json")
        check(f.allowed.isEmpty() && !f.calls, "Malformed JSON falls back to off")
        check(NotificationFilter.fromJson("x", null).allowed.isEmpty(), "Missing file falls back to off")

        // PUT /api/notifications body.
        f = NotificationFilter.fromApi("local.hyprwatch", JSONObject("{\"calls\":true,\"allowed\":[\"com.whatsapp\",\"org.telegram.messenger\",\"com.whatsapp\"]}"))
        check(f.calls && f.allowed == setOf("com.whatsapp", "org.telegram.messenger"), "Valid settings, duplicates merged")
        check(NotificationFilter.fromApi("x", JSONObject("{\"calls\":false,\"allowed\":[]}")).allowed.isEmpty(), "Empty list turns forwarding off")
        check(apiError("{\"calls\":true}") == "allowed must be a list of at most 500 package names", "allowed required")
        check(apiError("{\"allowed\":[]}") == "calls must be true or false", "calls required")
        check(apiError("{\"calls\":\"yes\",\"allowed\":[]}") == "calls must be true or false", "calls must be boolean")
        check(apiError("{\"calls\":true,\"allowed\":[],\"x\":1}") == "Unknown notification setting x", "Unknown key rejected")
        check(apiError("{\"calls\":true,\"allowed\":[\"com.a\",\"not a package\"]}") == "allowed[1] is not a package name", "Invalid package rejected")
        check(apiError("{\"calls\":true,\"allowed\":[\"nodots\"]}") == "allowed[0] is not a package name", "Package needs a dot")
        check(apiError("{\"calls\":true,\"allowed\":[7]}") == "allowed[0] is not a package name", "Number rejected")
        val tooMany = (0..500).joinToString(",") { "\"p.a$it\"" }
        check(apiError("{\"calls\":true,\"allowed\":[$tooMany]}") == "allowed must be a list of at most 500 package names", "List bounded")

        // Apps that posted lately: bounded, newest first, a repeat moves to the front.
        val apps = RecentApps<String?>(capacity = 2)
        apps.add("com.a", 1, null)
        apps.add("com.b", 2, "info")
        apps.add("com.a", 3, null)
        apps.add("com.c", 4, null)
        check(apps.list().map { it.pkg to it.lastSeen } == listOf("com.c" to 4L, "com.a" to 3L), "Recent apps bounded and ordered")
        apps.add("android", 5, null)
        check(apps.list().map { it.pkg } == listOf("com.c", "com.a"), "Packages the settings would reject are not listed")
        check(NotificationFilter.isPackage("com.a") && !NotificationFilter.isPackage("android") && !NotificationFilter.isPackage("a." + "b".repeat(254)), "Package rule")

        println("NotificationsTest passed")
    }
}
