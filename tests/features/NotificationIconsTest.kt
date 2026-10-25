package local.hyprwatch

import org.json.JSONObject
import java.time.ZoneOffset

/** Notification icons: 7/16 query, 7/15 reply and request, the pixel formats, then a type-50 upload on channel 2. */
object NotificationIconsTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun hex(b: ByteArray): String = HealthCapture.hex(b)

    private object NoListener : NotificationsFeature.ListenerSettings {
        override fun get(): JSONObject = JSONObject()

        override fun put(input: JSONObject): JSONObject = input

        override fun openAccess(): Boolean = false
    }

    /** 7/16 {notification(9) {notificationIconQuery(16) {package: "com.whatsa"}}}, a truncated name as the watch sends it. */
    private const val QUERY = "080710104a0f82010c0a0a636f6d2e776861747361"

    /** 7/15 {notification(9) {notificationIconReply(14) {package: "com.whatsa"}}}: Gadgetbridge echoes the watch's name. */
    private const val REPLY = "0807100f4a0e720c0a0a636f6d2e776861747361"

    /** 2x2 of 0xFF123456 in ARGB8565 LE: 12 bytes; 22/0 for type 50 with their MD5 and size 12. */
    private const val UPLOAD_REQUEST = "08161000c201180a1608321210e8d3ae2a2b3cf40d49644d3c48587956180c"
    private val UPLOAD_PARTS = listOf("040001000032e8d3ae2a2b3cf40d4964", "040002004d3c485879560c000000aa11", "04000300ffaa11ffaa11ffaa11ffcba1", "04000400d080")

    private fun query(pkg: String) = Proto.command(7, 16, Proto.bytes(9, Proto.bytes(16, Proto.text(1, pkg))))

    private fun request(format: Int, size: Int, status: Int = 0) = Proto.command(7, 15, Proto.bytes(9, Proto.bytes(15, Proto.join(Proto.num(1, status.toLong()), Proto.num(2, format.toLong()), Proto.num(3, size.toLong())))))

    private fun uploadAck() = Proto.command(22, 0, Proto.bytes(24, Proto.bytes(2, Proto.join(Proto.num(2, 0), Proto.num(4, 0), Proto.num(5, 18)))))

    private fun note(pkg: String, key: String = pkg, call: Boolean = false) = NotificationEvent.Posted(PhoneNotification(key, pkg, "A", "Secret title", "Secret body", 0, call))

    private class Run(withUploads: Boolean = true) {
        val uploads = DataUpload()
        val feature = NotificationsFeature(NoListener, WatchNotifications(NotificationIds()) { ZoneOffset.UTC }, if (withUploads) uploads else null)
        val rig = if (withUploads) FeatureRig(uploads, feature) else FeatureRig(feature)

        init {
            rig.phone.icons["com.whatsapp"] = IntArray(4) { 0xFF123456.toInt() }
            rig.phone.icons["org.telegram.messenger"] = IntArray(1) { 0x80FF0000.toInt() }
            rig.connect()
        }

        fun post(e: NotificationEvent) {
            feature.forward(e)
            rig.drain()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        pixels()
        flow()
        pairing()
        behindAnotherUpload()
        releaseRace()
        refusals()
        println("Notification icons: $assertions assertions passed")
    }

    private fun pixels() {
        val opaque = intArrayOf(0xFF123456.toInt())
        val half = intArrayOf(0x80FF0000.toInt())
        val clear = intArrayOf(0x00FFFFFF)
        val expected = mapOf(
            IconPixels.RGB565_LE to listOf("aa11", "0080", "0000"),
            IconPixels.RGB565_BE to listOf("11aa", "8000", "0000"),
            IconPixels.XRGB8888_LE to listOf("563412ff", "000080ff", "000000ff"),
            IconPixels.ARGB8888_LE to listOf("563412ff", "0000ff80", "ffffff00"),
            IconPixels.ARGB8565_LE to listOf("aa11ff", "00f880", "ffff00"),
            IconPixels.ABGR8565_LE to listOf("a251ff", "1f0080", "ffff00"),
        )
        for ((format, want) in expected) {
            val got = listOf(opaque, half, clear).map { hex(IconPixels.convert(it, format)) }
            check(got == want, "Pixel format $format: $got, want $want")
        }
        check(hex(IconPixels.convert(intArrayOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt()), IconPixels.RGB565_LE)) == "1f00e007", "Row order and channel bits: blue then green")
        var threw = false
        try {
            IconPixels.convert(opaque, 4)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        check(threw, "Unknown pixel format refused")
    }

    private fun flow() {
        val r = Run()
        r.post(note("com.whatsapp"))
        r.rig.take()

        check(hex(query("com.whatsa")) == QUERY, "Query fixture")
        r.rig.receive(hexBytes(QUERY))
        check(r.rig.take() == listOf(REPLY), "A truncated name matching a forwarded app is answered with the watch's own name")
        check(r.rig.logs.last() == "Notification icon query for 'com.whatsa': offered com.whatsapp", r.rig.logs.last())

        r.rig.receive(request(IconPixels.ARGB8565_LE, 2))
        check(r.rig.phone.iconSizes == listOf("com.whatsapp" to 2), "The full package's icon is drawn at the asked size")
        check(r.rig.take() == listOf(UPLOAD_REQUEST), "Type 50 upload of the 12 converted bytes")
        r.rig.receive(uploadAck())
        check(r.rig.takeData() == UPLOAD_PARTS.subList(0, 3), "Three parts fill the window")
        r.rig.ack()
        check(r.rig.takeData() == UPLOAD_PARTS.subList(3, 4), "The ack sends the last")
        r.rig.ack()
        check(r.rig.logs.last() == "Uploaded the icon for com.whatsapp" && r.rig.status().getJSONObject("data_upload").getString("state") == "done", "Done: ${r.rig.logs.last()}")
        check(r.rig.logs.none { "Secret" in it }, "Titles and bodies never reach the logs")
    }

    /** Requests carry no package: they pair with answered queries in order, and an icon waits while another uploads. */
    private fun pairing() {
        val r = Run()
        r.post(note("com.whatsapp"))
        r.post(note("org.telegram.messenger"))
        r.rig.take()
        r.rig.receive(query("com.whatsapp"))
        r.rig.receive(query("org.telegram.messenger"))
        check(r.rig.take().size == 2, "Both queries answered")
        r.rig.receive(request(IconPixels.ARGB8565_LE, 2))
        r.rig.receive(request(IconPixels.ARGB8888_LE, 1))
        check(r.rig.phone.iconSizes == listOf("com.whatsapp" to 2, "org.telegram.messenger" to 1), "First request is WhatsApp's, second Telegram's")
        check(r.rig.take() == listOf(UPLOAD_REQUEST), "Only the first upload starts")
        r.rig.receive(uploadAck())
        r.rig.ack()
        r.rig.ack()
        check(r.rig.takeData() == UPLOAD_PARTS, "WhatsApp's parts")
        val second = r.rig.take()
        check(second.size == 1 && second[0].startsWith("08161000c201180a160832") && second[0].endsWith("1804"), "When it is done the waiting icon (4 bytes) starts: $second")
        r.rig.receive(uploadAck())
        val parts = r.rig.takeData()
        check(parts.size == 3 && parts[0].startsWith("03000100"), "30 bytes: 3 parts: $parts")
        check(parts.joinToString("") { it.substring(8) }.substring(44, 52) == "0000ff80", "Telegram's straight-alpha ARGB8888 pixel inside the payload")

        // A session change forgets answered queries and waiting icons.
        r.rig.receive(query("com.whatsapp"))
        r.rig.disconnect()
        r.rig.connect()
        r.rig.take()
        r.rig.receive(request(IconPixels.RGB565_LE, 2))
        check(r.rig.logs.last() == "Notification icon request without a query" && r.rig.sent().isEmpty(), "No pairing across sessions")
    }

    /** An icon waiting for the uploader starts as soon as it is free, also when a face upload fails or a reservation is given back. */
    private fun behindAnotherUpload() {
        val r = Run()
        r.post(note("com.whatsapp"))
        r.rig.take()
        check(r.uploads.start(DataUpload.TYPE_WATCHFACE, ByteArray(10)) {}, "A face upload is requested")
        r.rig.take()
        r.rig.receive(query("com.whatsapp"))
        r.rig.receive(request(IconPixels.ARGB8565_LE, 2))
        check(r.rig.take().size == 1, "Only the query is answered: the icon waits behind it")
        r.rig.receive(Proto.command(22, 0, Proto.bytes(24, Proto.bytes(2, Proto.num(2, 7)))))
        check(r.rig.take() == listOf(UPLOAD_REQUEST), "The face's 22/0 is refused: the waiting icon starts at once, no link ack needed")
        r.rig.receive(uploadAck())
        r.rig.ack()
        r.rig.ack()
        r.rig.takeData()

        val held = r.uploads.reserve()!!
        r.rig.receive(query("com.whatsapp"))
        r.rig.receive(request(IconPixels.ARGB8565_LE, 2))
        check(r.rig.take().size == 1, "An icon waits behind a reservation")
        r.uploads.release(held)
        r.rig.drain()
        check(r.rig.take() == listOf(UPLOAD_REQUEST), "and starts when it is released")
    }

    /**
     * Another caller reserves the uploader while an icon is about to start, then releases it before
     * the icon's start has finished failing: the icon must still go, not sit in the queue after the
     * only wakeup. The other caller (a face install) reserves when the icon side reads `connected`,
     * and gives the uploader back from a thread holding the icon lock (inside the 7/15 reply's send),
     * once this thread is blocked on that lock.
     */
    private fun releaseRace() {
        val r = Run()
        r.rig.phone.icons["com.whatsapp"] = IntArray(256 * 256) { 0xFF123456.toInt() }
        r.post(note("com.whatsapp"))
        r.rig.take()
        val held = r.uploads.reserve()!!
        r.rig.receive(query("com.whatsapp"))
        r.rig.receive(request(IconPixels.ARGB8888_LE, 256))
        check(r.rig.take().size == 1, "A 256 px icon waits behind a reservation")
        val main = Thread.currentThread()
        val returned = java.util.concurrent.atomic.AtomicBoolean(false)
        var other: Thread? = null
        r.rig.watch.onConnectedRead = {
            r.rig.watch.onConnectedRead = null
            val face = r.uploads.reserve()!!
            r.rig.watch.onSend = {
                r.rig.watch.onSend = null
                val deadline = System.nanoTime() + 2_000_000_000L
                while (main.state != Thread.State.BLOCKED && !returned.get() && System.nanoTime() < deadline) Thread.sleep(1)
                r.uploads.release(face)
            }
            other = Thread { r.rig.receive(query("com.whatsapp")) }.also { it.start() }
        }
        r.uploads.release(held)
        returned.set(true)
        other!!.join(5_000)
        val sent = r.rig.take()
        check(sent.count { it.startsWith("08161000") } == 1 && r.rig.status().getJSONObject("data_upload").getString("state") == "requesting", "The icon starts once the uploader is free: $sent")
    }

    private fun refusals() {
        val r = Run()
        r.post(note("com.whatsapp"))
        r.post(note("phone.dialer", call = true))
        r.rig.take()
        r.rig.receive(query("com.example"))
        check(r.rig.sent().isEmpty() && r.rig.logs.last() == "Notification icon query for 'com.example': no app forwarded lately matches", "Unknown app: no reply")
        r.rig.receive(query("phone"))
        check(r.rig.sent().isEmpty(), "Calls are not remembered for icons")
        r.rig.receive(query(""))
        check(r.rig.sent().isEmpty(), "An empty name matches nothing")
        r.rig.receive(request(0, 2))
        check(r.rig.logs.last() == "Notification icon request without a query", "Request without a query")

        r.rig.receive(query("com.whatsapp"))
        r.rig.take()
        r.rig.receive(request(0, 2, status = 1))
        check(r.rig.logs.last() == "The watch declined the icon for com.whatsapp (status 1)" && r.rig.sent().isEmpty(), "Declined")
        r.rig.receive(query("com.whatsapp"))
        r.rig.take()
        r.rig.receive(request(5, 2))
        check(r.rig.logs.last() == "The watch asked for the icon of com.whatsapp in unknown pixel format 5" && r.rig.sent().isEmpty(), "Unknown format")
        r.rig.receive(query("com.whatsapp"))
        r.rig.take()
        r.rig.receive(request(0, 1000))
        check(r.rig.logs.last() == "The watch asked for the icon of com.whatsapp at unsupported size 1000" && r.rig.sent().isEmpty(), "Size out of range")
        r.rig.phone.icons.remove("com.whatsapp")
        r.rig.receive(query("com.whatsapp"))
        r.rig.take()
        r.rig.receive(request(0, 2))
        check(r.rig.logs.last() == "No icon for com.whatsapp" && r.rig.sent().isEmpty(), "No icon on the phone")

        // A full window drops the reply; the request then finds no query.
        r.rig.watch.refuse = true
        r.rig.receive(query("com.whatsapp"))
        r.rig.watch.refuse = false
        r.rig.receive(request(0, 2))
        check(r.rig.logs.last() == "Notification icon request without a query", "An unsent reply is not paired")

        // Without the uploader the feature leaves 7/15 and 7/16 to the link's unhandled log.
        val bare = Run(withUploads = false)
        var unhandled = false
        try {
            bare.rig.receive(query("com.whatsapp"))
        } catch (e: IllegalStateException) {
            unhandled = e.message == "No feature handles 7/16"
        }
        check(unhandled, "No icon handlers without uploads")
    }

    private fun hexBytes(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
