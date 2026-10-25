package local.hyprwatch

import java.io.IOException
import java.security.MessageDigest

/** DataUpload over FeatureRig: the 22/0 handshake, payload and part bytes, ack pacing and every way it ends. */
object DataUploadTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun bytes(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun hex(b: ByteArray): String = HealthCapture.hex(b)

    /** 0x01..0x0a, MD5 70903e79b7575e3f4e7ffa15c2608ac7. */
    private val TEN = ByteArray(10) { (it + 1).toByte() }

    /** 22/0 {dataUpload(24) {dataUploadRequest(1) {type 50, md5, size 10}}}. */
    private const val REQUEST = "08161000c201180a160832121070903e79b7575e3f4e7ffa15c2608ac7180a"

    /** The 36-byte payload [0][50][md5][10 u32 LE][data][CRC-32 LE] in parts of 18 - 6 bytes, each [3 u16][i u16]. */
    private val PARTS = listOf(
        "03000100003270903e79b7575e3f4e7f",
        "03000200fa15c2608ac70a0000000102",
        "03000300030405060708090a48c1e370",
    )

    /** 22/0 {dataUpload(24) {dataUploadAck(2) {...}}}. */
    private fun ack(errno: Long = 0, resume: Long = 0, chunk: Long? = 18, md5: ByteArray? = null, status: Long = 0): ByteArray =
        Proto.command(
            22, 0,
            Proto.bytes(24, Proto.bytes(2, Proto.join(md5?.let { Proto.bytes(1, it) } ?: ByteArray(0), Proto.num(2, errno), Proto.num(4, resume), chunk?.let { Proto.num(5, it) } ?: ByteArray(0)))),
            if (status != 0L) Proto.num(100, status) else ByteArray(0),
        )

    private class Run {
        var now = 1_000L
        val upload = DataUpload(ackTimeoutMs = 10_000, stallMs = 35_000) { now }
        val rig = FeatureRig(upload)
        val results = ArrayList<String?>()

        fun start(data: ByteArray = TEN, type: Int = DataUpload.TYPE_NOTIFICATION_ICON): Boolean = upload.start(type, data) { results.add(it) }

        fun status() = rig.status().getJSONObject("data_upload")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        handshake()
        pacing()
        chunkMath()
        refusals()
        timeouts()
        sessions()
        reservations()
        println("Data upload: $assertions assertions passed")
    }

    private fun handshake() {
        check(hex(MessageDigest.getInstance("MD5").digest(TEN)) == "70903e79b7575e3f4e7ffa15c2608ac7", "Fixture MD5")
        check(
            hex(DataUpload.payload(50, TEN, MessageDigest.getInstance("MD5").digest(TEN), 0)) == "003270903e79b7575e3f4e7ffa15c2608ac70a0000000102030405060708090a48c1e370",
            "Payload: 0, type, MD5, size u32 LE, data, CRC-32 LE",
        )

        val r = Run()
        var offline: String? = null
        try {
            r.start()
        } catch (e: IOException) {
            offline = e.message
        }
        check(offline == "Connect the watch first" && r.rig.sent().isEmpty(), "No upload while disconnected")
        check(r.status().getString("state") == "idle", "Idle status")

        r.rig.connect()
        check(r.start(), "Started")
        check(r.rig.take() == listOf(REQUEST), "22/0 upload request with type, MD5 and size")
        check(r.rig.data().isEmpty() && r.status().getString("state") == "requesting" && r.status().getString("kind") == "notification_icon", "Nothing on channel 2 before the watch's ack")
        check(!r.start(), "A second upload is refused while one runs")
        check(r.rig.sent().isEmpty(), "and sends nothing")

        r.rig.receive(ack())
        check(r.rig.takeData() == PARTS, "All three parts fit the window of 3, bytes exact")
        val s = r.status()
        check(s.getString("state") == "sending" && s.getInt("chunk_size") == 18 && s.getInt("parts_total") == 3 && s.getInt("parts_sent") == 3 && s.getInt("parts_acked") == 0, "Sending: $s")
        check(r.results.isEmpty(), "Not done when the last part is queued")
        r.rig.ackData(1)
        check(r.status().getInt("parts_acked") == 1 && r.status().getInt("percent") == 33 && r.results.isEmpty(), "One part acked")
        r.now = 1_500
        r.rig.ackData(2)
        check(r.results == listOf<String?>(null), "Done once the watch acked the last part")
        val done = r.status()
        check(done.getString("state") == "done" && done.getInt("percent") == 100 && done.getInt("parts_acked") == 3 && done.getLong("finished_at") == 1_500L && done.isNull("error"), "Done: $done")
        check(r.rig.logs.contains("Data upload done: notification_icon, 3 parts acked in 500 ms"), "Logged: ${r.rig.logs}")
        r.rig.ack()
        check(r.results.size == 1, "Later acks change nothing")
        check(r.start(), "The next upload may start")
    }

    /** The link holds back chunks while its window is full; each ack sends the next. */
    private fun pacing() {
        val r = Run()
        r.rig.connect()
        r.rig.watch.dataWindow = 1
        r.start()
        r.rig.take()
        r.rig.receive(ack())
        check(r.rig.takeData() == PARTS.subList(0, 1), "Window of 1: one part")
        r.rig.ack()
        check(r.rig.takeData() == PARTS.subList(1, 2), "The ack sends the second")
        r.rig.watch.refuse = true
        r.rig.ack()
        check(r.rig.takeData().isEmpty() && r.results.isEmpty(), "A refusal (window full of commands) just waits")
        r.rig.watch.refuse = false
        r.rig.ack()
        check(r.rig.takeData() == PARTS.subList(2, 3) && r.results.isEmpty(), "The next ack sends the third")
        r.rig.ack()
        check(r.results == listOf<String?>(null), "Done on its ack")
    }

    private fun chunkMath() {
        // No chunk size in the ack: Gadgetbridge's 2048, so parts of 2042 bytes.
        var r = Run()
        r.rig.connect()
        val file = ByteArray(5000) { (it * 7).toByte() }
        r.start(file, DataUpload.TYPE_WATCHFACE)
        r.rig.take()
        r.rig.receive(ack(chunk = null))
        r.rig.ackData(3)
        val parts = r.rig.takeData()
        val payload = DataUpload.payload(16, file, MessageDigest.getInstance("MD5").digest(file), 0)
        check(payload.size == 5026, "Payload is 22 + 5000 + 4 bytes")
        check(parts.map { it.length / 2 } == listOf(2046, 2046, 946), "5026 bytes in parts of 2042: ${parts.map { it.length / 2 }}")
        check(parts.map { it.substring(0, 8) } == listOf("03000100", "03000200", "03000300"), "Headers count 1 to 3 of 3")
        check(parts.joinToString("") { it.substring(8) } == hex(payload), "Parts join to the payload")
        check(parts[0].substring(8, 12) == "0010" && parts[2].endsWith(hex(payload.copyOfRange(5022, 5026))), "Starts [0][16], ends with the CRC")
        check(r.results == listOf<String?>(null) && r.status().getString("kind") == "watch_face", "Watch face upload done")

        // Resume: only the tail goes, under the whole file's MD5 and size; numbering restarts at 1.
        r = Run()
        r.rig.connect()
        r.start()
        r.rig.take()
        r.rig.receive(ack(resume = 4, md5 = MessageDigest.getInstance("MD5").digest(TEN)))
        check(
            r.rig.takeData() == listOf("03000100003270903e79b7575e3f4e7f", "03000200fa15c2608ac7060000000506", "030003000708090a0adb80ec"),
            "Resume at 4: the tail's size, data from byte 5, CRC over the shorter payload",
        )
        check(r.status().getInt("resume_position") == 4, "Resume position in status")

        // TEN's 36 bytes end on a part boundary (3 parts, no empty fourth); 11 bytes make 37, so a 1-byte fourth part.
        r = Run()
        r.rig.connect()
        r.start(ByteArray(11))
        r.rig.take()
        r.rig.receive(ack())
        check(
            r.rig.takeData() == listOf("04000100003274da4121dc1c0ed2a8e5", "04000200b0741f8240340b0000000000", "040003000000000000000000005b0d9e"),
            "37 bytes: the first three of four parts fill the window",
        )
        r.rig.ackData(1)
        check(r.rig.takeData() == listOf("0400040060") && r.status().getInt("parts_total") == 4, "The fourth holds the CRC's last byte")
    }

    private fun refusals() {
        fun refused(reply: ByteArray, expected: String) {
            val r = Run()
            r.rig.connect()
            r.start()
            r.rig.take()
            r.rig.receive(reply)
            check(r.results == listOf<String?>(expected) && r.rig.data().isEmpty(), "$expected: ${r.results}")
            check(r.status().getString("state") == "failed" && r.status().getString("error") == expected, "Failed status for $expected")
            check(r.start(), "A new upload may start after: $expected")
        }
        refused(ack(errno = 3), "The watch refused the upload (error 3)")
        refused(ack(status = 1), "The watch refused the upload (status 1)")
        refused(ack(chunk = 6), "The watch asked for unusable 6-byte chunks")
        refused(ack(resume = 11), "The watch asked to resume at 11 of 10 bytes")
        refused(ack(md5 = ByteArray(16)), "The watch answered the upload request for another file")

        // The watch stops a running upload.
        var r = Run()
        r.rig.connect()
        r.start()
        r.rig.take()
        r.rig.receive(ack())
        r.rig.receive(ack(errno = 5))
        check(r.results == listOf<String?>("The watch stopped the upload (status 0, error 5)"), "A pushed error ends it: ${r.results}")
        r.rig.ack()
        check(r.rig.data().size == 3 && r.results.size == 1, "Nothing more is sent")

        // The link refuses the chunk outright (writer gone, frame too large).
        r = Run()
        r.rig.connect()
        r.start()
        r.rig.take()
        r.rig.watch.dataBroken = "Upload chunk of 16 bytes exceeds the watch's 8-byte packets"
        r.rig.receive(ack())
        check(r.results == listOf<String?>("Upload chunk of 16 bytes exceeds the watch's 8-byte packets") && r.status().getString("state") == "failed", "Link refusal ends the upload")

        // The request itself could not be sent.
        r = Run()
        r.rig.connect()
        r.rig.watch.refuse = true
        var unsent: String? = null
        try {
            r.start()
        } catch (e: IOException) {
            unsent = e.message
        }
        check(unsent == "Could not send the upload request to the watch" && r.results.isEmpty() && !r.upload.busy, "Unsent request throws, no callback")
    }

    private fun timeouts() {
        // No answer to the request: it fails; the late answer is not taken for the next request's.
        val r = Run()
        r.rig.connect()
        r.start()
        check(r.rig.watch.delays() == listOf(10_000L), "Ack timer of 10 s")
        r.rig.fire()
        check(r.results == listOf<String?>("The watch did not answer the upload request"), "Ack timeout")
        r.rig.take()
        r.start(ByteArray(2))
        r.rig.receive(ack())
        check(r.rig.data().isEmpty() && r.results.size == 1 && r.status().getString("state") == "requesting", "The late reply answers the first request, not the second")
        r.rig.receive(ack())
        check(r.rig.takeData() == listOf("030001000032c4103f122d27677c9db1", "0300020044cae1394a66020000000000", "03000300f9a52406"), "Its own reply starts it")
    }

    private fun sessions() {
        // The watch stops acking: the stall timer ends the upload once nothing moved for a whole period.
        var r = Run()
        r.rig.connect()
        r.start()
        r.rig.take()
        r.rig.receive(ack())
        r.rig.fire()
        check(r.results.isEmpty(), "Parts went out since the timer was armed: it re-arms")
        r.rig.ackData(1)
        r.rig.fire()
        check(r.results.isEmpty(), "An ack is movement too")
        r.rig.fire()
        check(r.results == listOf<String?>("The watch stopped acknowledging upload data (1 of 3 parts)"), "Stalled: ${r.results}")
        r.rig.ack()
        check(r.results.size == 1 && r.status().getString("state") == "failed", "A late ack does not finish it")

        // Disconnect mid-upload: fails once; acks of the next session do not resurrect it.
        r = Run()
        r.rig.connect()
        r.start()
        r.rig.take()
        r.rig.receive(ack())
        r.rig.ackData(1)
        r.rig.disconnect()
        check(r.results == listOf<String?>("The watch disconnected") && !r.upload.busy, "Disconnect ends it")
        r.rig.connect()
        r.rig.ack()
        check(r.results.size == 1 && r.rig.takeData().size == 3, "Nothing resumes after reconnect")
        r.rig.receive(ack())
        check(r.rig.data().isEmpty() && r.rig.logs.last() == "Data upload: ignored a 22/0 that answers no current request (status 0, error 0)", "A reply after reconnect answers nothing: ${r.rig.logs.last()}")

        // Disconnect while requesting.
        r.start()
        r.rig.disconnect()
        check(r.results == listOf<String?>("The watch disconnected", "The watch disconnected"), "Requesting upload ends on disconnect")
        r.rig.connect()
        r.rig.take()
        r.rig.fire()
        check(r.results.size == 2, "The old ack timer does nothing")
    }

    /** A reservation holds the uploader for one caller; every way the uploader frees up tells the idle listeners. */
    private fun reservations() {
        val r = Run()
        var idle = 0
        r.upload.onIdle { idle++ }
        r.rig.connect()
        check(!r.rig.watch.tracing, "Idle: the link does not trace")
        val held = r.upload.reserve()!!
        check(r.upload.busy && r.upload.reserve() == null && !r.start() && r.rig.sent().isEmpty(), "Reserved: no other upload or reservation")
        check(r.rig.watch.tracing, "From the reservation (a face's 4/4) the link traces what the watch sends")
        check(r.upload.start(DataUpload.TYPE_WATCHFACE, TEN, held) { r.results.add(it) } && r.rig.take().size == 1, "Its holder starts")
        r.upload.release(held)
        check(r.upload.busy && idle == 0, "Releasing a reservation the upload took changes nothing")
        r.rig.receive(ack(errno = 1))
        check(r.results == listOf<String?>("The watch refused the upload (error 1)") && idle == 1, "A failed upload frees the uploader: idle")
        check(!r.rig.watch.tracing, "and ends the trace")

        val unused = r.upload.reserve()!!
        check(r.rig.watch.tracing, "Reserved again: traced")
        r.upload.release(unused)
        check(!r.upload.busy && idle == 2 && !r.rig.watch.tracing, "Releasing an unused reservation: idle, untraced")
        r.upload.release(unused)
        check(idle == 2, "once")

        val voided = r.upload.reserve()!!
        r.rig.disconnect()
        check(!r.rig.watch.tracing, "A disconnect ends the trace after the link's reset")
        r.rig.connect()
        var error: String? = null
        try {
            r.upload.start(DataUpload.TYPE_WATCHFACE, TEN, voided) {}
        } catch (e: IOException) {
            error = e.message
        }
        check(error == "The watch disconnected" && !r.upload.busy && r.rig.sent().isEmpty(), "A new session voids a reservation")

        check(r.start() && r.rig.watch.tracing, "Free again; the 22/0 handshake is traced")
        r.rig.take()
        r.rig.receive(ack())
        r.rig.ack()
        check(r.results.last() == null && idle == 3 && !r.rig.watch.tracing, "A finished upload: idle, untraced")
    }
}
