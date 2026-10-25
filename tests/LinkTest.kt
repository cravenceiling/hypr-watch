package local.hyprwatch

import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Timer driven by the test: fire() runs every task scheduled so far. */
class ManualTimer : WatchTimer {
    private class Task(val ms: Long, val run: () -> Unit)

    private val tasks = ArrayList<Task>()

    @Synchronized
    override fun after(ms: Long, task: () -> Unit): () -> Unit {
        val t = Task(ms, task)
        tasks.add(t)
        return { synchronized(this) { tasks.remove(t) } }
    }

    @Synchronized
    fun delays(): List<Long> = tasks.map { it.ms }

    fun fire() {
        val due = synchronized(this) { ArrayList(tasks).also { tasks.clear() } }
        for (t in due) {
            t.run()
        }
    }
}

object LinkTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun bytes(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private class Rig {
        val written: MutableList<String> = Collections.synchronizedList(ArrayList())
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val drops: MutableList<String> = Collections.synchronizedList(ArrayList())
        val traces: MutableList<String> = Collections.synchronizedList(ArrayList())
        val timer = ManualTimer()
        @Volatile var refuse = false
        val link = SppLink(
            { if (refuse) throw IOException("Watch is not accepting data") else written.add(HealthCapture.hex(it)) },
            timer,
            { logs.add(it) },
            { drops.add(it) },
            { traces.add(it) },
        ).apply { authenticated() }
        private val reader = Frames.Reader { logs.add(it) }

        fun receive(hex: String) {
            val b = bytes(hex)
            reader.add(b, b.size)
            while (true) {
                link.receive(reader.next(true) ?: break)
            }
        }

        fun take(): List<String> = synchronized(written) { ArrayList(written).also { written.clear() } }

        fun sendNumbered(count: Int): List<String> {
            for (i in 0 until count) {
                link.send(byteArrayOf(i.toByte()))
            }
            return take()
        }
    }

    private fun seq(frame: String): Int = frame.substring(6, 8).toInt(16)

    /** The Redmi Watch 5 Active's session config on firmware 2.2.105, as logged on the hardware. */
    const val WATCH_CONFIG = "02" + "010300" + "02005f" + "020200" + "0080" + "030200" + "0300" + "040200" + "803e"

    /** Channel 2 (upload data): plaintext, numbered in the command window, paced to the watch's tx window. */
    private fun uploadData() {
        check(
            Frames.sessionConfig(bytes(WATCH_CONFIG)) == "opcode 2: version 2.0.95, max packet 32768 bytes, tx window 3 frames, send timeout 16000 ms",
            "The hardware's session config reads as logged",
        )
        check(Frames.sessionValue(bytes(WATCH_CONFIG), Frames.TX_WINDOW) == 3 && Frames.sessionValue(bytes(WATCH_CONFIG), Frames.MAX_PACKET) == 32768, "Tx window and max packet")
        check(Frames.sessionValue(bytes("02" + "010300" + "02005f"), Frames.TX_WINDOW) == null && Frames.sessionValue(bytes("02030200"), Frames.TX_WINDOW) == null, "Absent or truncated values are null")

        var r = Rig()
        check(r.link.uploadWindow() == 1, "Before a session config, one frame at a time")
        r.link.configure(bytes(WATCH_CONFIG))
        check(r.link.uploadWindow() == 3, "The watch's tx window of 3")
        r.link.encrypt = { it.reversedArray() }

        // Shared numbering; the upload frame is [2][1][chunk] in the clear while commands are encrypted.
        check(r.link.sendData(r.link.epoch, bytes("01000100cc")), "First chunk sent")
        r.link.send(bytes("0102"))
        check(r.link.sendData(r.link.epoch, bytes("02000100aabb")), "Second chunk sent")
        val frames = r.take()
        check(frames[0] == "a5a5030007004e44020101000100cc", "Chunk at seq 0, channel 2, opcode 1, plaintext: ${frames[0]}")
        check(seq(frames[1]) == 1 && frames[1].endsWith("01020201"), "Command at seq 1, channel 1, opcode 2, encrypted: ${frames[1]}")
        check(seq(frames[2]) == 2 && frames[2].substring(16) == "020102000100aabb", "Next chunk continues the numbering at 2: ${frames[2]}")
        check(r.link.uploadsInFlight(r.link.epoch) == 2, "Two upload frames await acks")

        // Pacing: three frames of any kind in flight hold back the next chunk but never a command.
        check(!r.link.sendData(r.link.epoch, bytes("03000300dd")) && r.take().isEmpty(), "A fourth frame in flight is held back, nothing written")
        r.link.send(bytes("0103"))
        check(r.take().map { seq(it) } == listOf(3), "A command still goes out, past the upload window")
        r.receive("a5a5010100000000")
        check(r.link.uploadsInFlight(r.link.epoch) == 1, "Ack of 0-1 leaves chunk 2 in flight")
        check(r.link.sendData(r.link.epoch, bytes("03000300dd")), "Frames 2 and 3 in flight: room for one chunk")
        check(!r.link.sendData(r.link.epoch, bytes("04000400ee")), "Three in flight again: held back")
        r.receive("a5a5010200000000")
        check(r.link.uploadsInFlight(r.link.epoch) == 1, "Ack of 2 leaves chunk 4 in flight")
        check(r.link.sendData(r.link.epoch, bytes("04000400ee")) && !r.link.sendData(r.link.epoch, bytes("05000500ff")), "Room for exactly one more")
        val paced = r.take()
        check(paced.map { seq(it) } == listOf(4, 5) && r.link.uploadsInFlight(r.link.epoch) == 2, "Chunks 4 and 5 written")

        // Go-back-N covers upload frames: a nack resends them byte for byte, with the command between.
        r.receive("a5a5000300000000")
        val resent = r.take()
        check(resent.map { seq(it) } == listOf(3, 4, 5) && resent.subList(1, 3) == paced, "Nack for 3 resends the command and both chunks unchanged")
        r.timer.fire()
        check(r.take().map { seq(it) } == listOf(3, 4, 5), "The resend timer covers them too")
        r.receive("a5a5010500000000")
        check(r.link.uploadsInFlight(r.link.epoch) == 0 && r.timer.delays().isEmpty(), "All acked")

        // A frame larger than the watch's max packet, header included, is refused before it is numbered.
        var tooBig: String? = null
        try {
            r.link.sendData(r.link.epoch, ByteArray(32759))
        } catch (e: IOException) {
            tooBig = e.message
        }
        check(tooBig == "Upload chunk of 32759 bytes makes a 32769-byte frame, over the watch's 32768-byte packets" && r.take().isEmpty(), "Oversize chunk refused: $tooBig")
        check(r.link.sendData(r.link.epoch, ByteArray(32758)), "A chunk whose frame fills the packet is sent")
        val full = r.take()
        check(full.map { seq(it) } == listOf(6) && full[0].length / 2 == 32768, "at the next number, as a 32768-byte frame")

        // A writer refusal gives back the number; a reset empties the upload count and refuses data until authentication.
        r.receive("a5a5010600000000")
        r.refuse = true
        var refusedWrite: String? = null
        try {
            r.link.sendData(r.link.epoch, bytes("01000100cc"))
        } catch (e: IOException) {
            refusedWrite = e.message
        }
        r.refuse = false
        check(refusedWrite == "Watch is not accepting data" && r.link.uploadsInFlight(r.link.epoch) == 0, "Writer refusal: nothing tracked")
        check(r.link.sendData(r.link.epoch, bytes("01000100cc")) && r.take().map { seq(it) } == listOf(7), "The refused number is reused")
        // While upload frames are in flight, each received frame is traced by kind, channel, opcode and command; never its payload.
        r.receive(HealthCapture.hex(Frames.encode(3, 9, byteArrayOf(1, 1) + Proto.command(7, 1, Proto.text(3, "secret")))))
        check(r.traces.last() == "During upload: data 9 channel 1 opcode 1 command 7/1", "Frame during an upload traced: ${r.traces.last()}")
        check(r.traces.filter { it.startsWith("During upload") } == listOf("ack 1", "ack 2", "nack 3", "ack 5", "ack 6", "data 9 channel 1 opcode 1 command 7/1").map { "During upload: $it" }, "Every frame received with upload frames in flight: ${r.traces}")
        check(r.traces.none { "secret" in it } && r.logs.none { "secret" in it }, "No payload in traces or logs")
        val before = r.link.epoch
        r.link.reset(IOException("Watch disconnected"))
        check(
            r.logs.last() == "Link reset during an upload (Watch disconnected): 1 of 6 upload frames unacked, 6 frames received meanwhile, last: data 9 channel 1 opcode 1 command 7/1",
            "A reset with upload frames in flight logs one summary line: ${r.logs.last()}",
        )
        check(r.link.epoch == before + 1 && r.link.uploadsInFlight(r.link.epoch) == 0, "A reset starts a new epoch with no upload frames")
        var ended: String? = null
        try {
            r.link.uploadsInFlight(before)
        } catch (e: IOException) {
            ended = e.message
        }
        check(ended == "The watch link restarted during the upload", "The old epoch's count is not 0 but an error, so dropped frames never read as acked")
        r.link.authenticated()
        r.take()
        var stale: String? = null
        try {
            r.link.sendData(before, bytes("01000100cc"))
        } catch (e: IOException) {
            stale = e.message
        }
        check(stale == "The watch link restarted during the upload" && r.take().isEmpty(), "Nor does it send in the new session")
        r.link.reset(IOException("Watch disconnected"))
        check(r.logs.count { it.startsWith("Link reset during an upload") } == 1, "A reset with nothing in flight logs no summary")
        var unauthenticated: String? = null
        try {
            r.link.sendData(r.link.epoch, bytes("01000100cc"))
        } catch (e: IOException) {
            unauthenticated = e.message
        }
        check(unauthenticated == "Watch is not connected" && r.take().isEmpty(), "No upload data before authentication")
        check(r.link.uploadWindow() == 3, "The session config's limits survive a reset; the next config replaces them")

        r = Rig()
        r.link.configure(bytes("02" + "030200" + "2000"))
        check(r.link.uploadWindow() == SppLink.UPLOAD_WINDOW_CAP, "A tx window of 32 is capped at ${SppLink.UPLOAD_WINDOW_CAP}")

        // Paced commands (health acks and requests) wait like upload data; ordinary commands do not.
        r = Rig()
        r.link.configure(bytes(WATCH_CONFIG))
        check(r.link.sendPaced(bytes("0801")) && r.link.sendPaced(bytes("0802")) && r.link.sendPaced(bytes("0803")), "Three paced commands")
        check(!r.link.sendPaced(bytes("0804")) && r.take().map { seq(it) } == listOf(0, 1, 2), "A fourth is held back while three await an ack")
        r.link.send(bytes("0805"))
        check(r.take().map { seq(it) } == listOf(3), "An ordinary command still goes")
        r.receive("a5a5010100000000")
        check(r.link.sendPaced(bytes("0804")) && r.take().map { seq(it) } == listOf(4), "An ack of 0-1 frees room for one")

        // An upload's handshake is traced before any chunk, and a reset then says so.
        r = Rig()
        r.link.uploadPhase(true)
        r.receive(HealthCapture.hex(Frames.encode(3, 4, byteArrayOf(1, 1) + Proto.command(4, 4, Proto.text(3, "secret")))))
        check(r.traces.last() == "During upload: data 4 channel 1 opcode 1 command 4/4", "The 4/4 answer is traced with no upload frame in flight: ${r.traces}")
        r.link.reset(IOException("Watch disconnected"))
        check(
            r.logs.last() == "Link reset during an upload (Watch disconnected): before its first chunk, 1 frames received meanwhile, last: data 4 channel 1 opcode 1 command 4/4",
            "A reset before the first chunk logs where the upload stood: ${r.logs.last()}",
        )
        check(r.traces.none { "secret" in it } && r.logs.none { "secret" in it }, "No payload in that trace or summary")
        r.link.uploadPhase(false)
        r.link.authenticated()
        r.receive(HealthCapture.hex(Frames.encode(3, 5, byteArrayOf(1, 1) + Proto.command(4, 4))))
        r.link.reset(IOException("Watch disconnected"))
        check(r.traces.count { it.startsWith("During upload") } == 1 && r.logs.count { it.startsWith("Link reset during an upload") } == 1, "Once it ends, nothing is traced or summarized")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Fire-and-forget: written once, no reply awaited, a missing reply never drops the link.
        var r = Rig()
        r.link.send(HealthCapture.fetchPast())
        check(r.take() == listOf("a5a50300060077b2010208081002"), "8/2 is framed with seq 0, channel 1, opcode 2")
        r.link.send(HealthCapture.requestFile(bytes("01020304000301")))
        check(r.take().map { seq(it) } == listOf(1), "8/3 returns once written, with seq 1")
        r.receive("a5a5010100000000")
        r.timer.fire()
        check(r.take().isEmpty() && r.drops.isEmpty(), "Acked fire-and-forget command with no reply keeps the link")
        r.receive("a5a503080600f272010108081003")
        check(r.take() == listOf("a5a5010800000000"), "Late unsolicited reply is link-acked")
        check(r.logs.last() == "Unhandled watch command 8/3 with fields {1=8, 2=3}" && r.drops.isEmpty(), "Unknown command is logged, link stays up")

        // Dispatcher routes watch-initiated commands by (type, subtype).
        r = Rig()
        var listed = ""
        r.link.on(8, 1) { listed = HealthCapture.hex(Proto.blob(Proto.message(it, 10), 2)) }
        r.receive("a5a50305180069100101080810015210120e010203040003010a0b0c0d000401")
        check(listed == "010203040003010a0b0c0d000401", "8/1 reaches its handler with the id blob")
        check(r.take() == listOf("a5a5010500000000"), "Dispatched frame is acked with its sequence number")
        r.receive("a5a50306060043b0010108071001")
        check(r.logs.last() == "Unhandled watch command 7/1 with fields {1=7, 2=1}", "7/1 without a handler is logged")

        // request() takes only its own reply; an unsolicited command in between goes to its handler.
        r = Rig()
        listed = ""
        r.link.on(8, 1) { listed = "handler" }
        val ex = Executors.newSingleThreadExecutor()
        val reply = ex.submit(Callable { r.link.request(Proto.command(17, 14), 17, 14, 5000) })
        val until = System.currentTimeMillis() + 5000
        while (r.written.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(5)
        }
        r.receive("a5a50305180069100101080810015210120e010203040003010a0b0c0d000401")
        check(listed == "handler" && !reply.isDone, "Unsolicited 8/1 does not complete a pending 17/14")
        r.receive("a5a503070d003e4b01010811100e9a010452021032")
        val list = reply.get(5, TimeUnit.SECONDS)
        check(Proto.number(Proto.message(Proto.message(list, 19), 10), 2, 0) == 50L, "17/14 reply completes request()")
        var failed: String? = null
        try {
            ex.submit(Callable { r.link.request(Proto.command(17, 14), 17, 14, 50) }).get(5, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            failed = (e.cause as? IOException)?.message
        }
        ex.shutdown()
        check(
            failed == "Watch response timed out; disconnected to avoid matching a late response to another request" &&
                r.drops == listOf("No reply to command 17/14"),
            "Only a request/response timeout drops the link",
        )

        // Encrypted commands need an authenticated session; authentication frames do not.
        r = Rig()
        r.link.reset(IOException("Watch restarted the session"))
        var unauthenticated: String? = null
        try {
            r.link.send(HealthCapture.fetchPast())
        } catch (e: IOException) {
            unauthenticated = e.message
        }
        r.link.send(Proto.command(1, 26), false)
        check(unauthenticated == "Watch is not connected" && r.take().map { it.substring(16, 20) } == listOf("0101"), "Only the unencrypted frame goes out after a reset")

        // Channel 5 opcode 2 is decrypted and handed to the chunk handler.
        r = Rig()
        var chunk = ""
        r.link.decrypt = { it.reversedArray() }
        r.link.onChunk = { chunk = HealthCapture.hex(it) }
        r.receive("a5a503090a005e5405020100010061626364")
        check(chunk == "6463626100010001", "Channel 5 chunk is decrypted and routed")
        check(r.take() == listOf("a5a5010900000000"), "Channel 5 frame is acked")

        // Gadgetbridge vector (Redmi Watch 6): ack 8, ack 9, nack 17 resends 17..20; then ack 17, nack 18.
        r = Rig()
        var sent = r.sendNumbered(21)
        check(sent.map { seq(it) } == (0..20).toList(), "Sequence numbers count from 0")
        r.receive("a5a5010800000000a5a5010900000000a5a5001100000000")
        var resent = r.take()
        check(resent == sent.subList(17, 21) && resent.map { seq(it) } == listOf(17, 18, 19, 20), "Nack resends from the expected frame")
        r.receive("a5a5011100000000a5a5001200000000")
        resent = r.take()
        check(resent == sent.subList(18, 21) && resent.map { seq(it) } == listOf(18, 19, 20), "Second nack resends from 18 only")

        r = Rig()
        r.sendNumbered(3)
        r.receive("a5a5000300000000")
        check(r.take().isEmpty() && r.logs.last() == "Watch expects frame 3, which is not pending", "Nack past the window resends nothing")

        // No ack: resend at each 10 s timeout, drop after the third.
        r = Rig()
        sent = r.sendNumbered(2)
        check(r.timer.delays() == listOf(10_000L), "One resend timer of 10 s")
        r.timer.fire()
        check(r.take() == sent, "First timeout resends both frames")
        r.timer.fire()
        check(r.take() == sent && r.drops.isEmpty(), "Second timeout resends again")
        r.timer.fire()
        check(r.drops == listOf("No link acknowledgement for frame 0"), "Third timeout drops the link")

        r = Rig()
        r.sendNumbered(3)
        r.timer.fire()
        r.timer.fire()
        r.receive("a5a5010000000000")
        r.timer.fire()
        r.timer.fire()
        check(r.drops.isEmpty(), "An ack restarts the timeout count")

        r = Rig()
        r.sendNumbered(3)
        r.receive("a5a5010200000000")
        check(r.timer.delays().isEmpty() && r.take().isEmpty(), "Fully acked window stops the timer")

        val w = SendWindow()
        repeat(100) { w.add { s -> byteArrayOf(s.toByte()) } }
        check(w.onAck(99) == 100, "Ack releases 100 frames")
        repeat(100) { w.add { s -> byteArrayOf(s.toByte()) } }
        check(w.onAck(199) == 100 && w.isEmpty(), "Next 100 frames released")
        repeat(128) { w.add { s -> byteArrayOf(s.toByte()) } }
        check(w.add { byteArrayOf() } == null && w.frames().size == 128 && w.oldest() == 200, "Full window refuses a frame instead of evicting one")
        check(w.onAck(71) == 128 && w.isEmpty(), "Cumulative ack across the wrap releases all")

        // A burst beyond the window is refused; frame 0 stays until acked, so its nack is answered.
        r = Rig()
        sent = r.sendNumbered(128)
        var refused: String? = null
        try {
            r.link.send(byteArrayOf(1))
        } catch (e: IOException) {
            refused = e.message
        }
        check(refused == "Watch has not acknowledged 128 frames" && r.take().isEmpty(), "129th unacked frame is refused, nothing written")
        r.receive("a5a5000000000000")
        check(r.take() == sent && seq(sent[0]) == 0, "Nack for frame 0 resends all 128 frames")
        r.receive("a5a5010000000000")
        r.link.send(byteArrayOf(1))
        check(r.take().map { seq(it) } == listOf(128), "An ack makes room again")

        // A frame the writer refused is taken back with its sequence number and never resent.
        r = Rig()
        val first = r.sendNumbered(1)
        r.refuse = true
        var writeFailed: String? = null
        try {
            r.link.send(HealthCapture.fetchPast())
        } catch (e: IOException) {
            writeFailed = e.message
        }
        r.refuse = false
        val second = r.sendNumbered(1)
        check(writeFailed == "Watch is not accepting data" && second.map { seq(it) } == listOf(1), "Refused frame gives back sequence number 1")
        r.timer.fire()
        check(r.take() == first + second, "Resend carries only the frames that were written")

        // A frame with a bad checksum is neither acked nor fatal; the watch resends it.
        r = Rig()
        listed = ""
        r.link.on(8, 1) { listed = "handler" }
        r.receive("a5a50305180069110101080810015210120e010203040003010a0b0c0d000401")
        check(r.take().isEmpty() && r.drops.isEmpty() && listed == "" && r.logs.last() == "Dropped frame 5 with a bad checksum", "Corrupt frame dropped unacked")
        r.receive("a5a50305180069100101080810015210120e010203040003010a0b0c0d000401")
        check(r.take() == listOf("a5a5010500000000") && listed == "handler", "Resent frame is acked and dispatched")

        // A data frame resent because our ack was lost is acked again but handled once.
        r = Rig()
        var lists = 0
        r.link.on(8, 1) { lists++ }
        r.receive("a5a50305180069100101080810015210120e010203040003010a0b0c0d000401")
        r.receive("a5a50305180069100101080810015210120e010203040003010a0b0c0d000401")
        check(lists == 1 && r.take() == listOf("a5a5010500000000", "a5a5010500000000"), "Repeated frame 5 acked twice, dispatched once")
        check(r.logs.last() == "Ignored repeated frame 5 on channel 1", "Repeat is logged")
        // Frame 7 is a 17/14 reply (capacity 50) to an earlier request; its repeat must not answer the next one.
        r.receive("a5a503070d003e4b01010811100e9a010452021032")
        r.take()
        val pending = Executors.newSingleThreadExecutor()
        val reply2 = pending.submit(Callable { r.link.request(Proto.command(17, 14), 17, 14, 5000) })
        val until3 = System.currentTimeMillis() + 5000
        while (r.written.isEmpty() && System.currentTimeMillis() < until3) {
            Thread.sleep(5)
        }
        r.receive("a5a503070d003e4b01010811100e9a010452021032")
        check(!reply2.isDone, "Repeated old 17/14 reply does not complete a new request")
        r.receive(HealthCapture.hex(Frames.encode(3, 8, Proto.join(byteArrayOf(1, 1), Proto.command(17, 14, Proto.bytes(19, Proto.bytes(10, Proto.num(2, 30))))))))
        val answer = reply2.get(5, TimeUnit.SECONDS)
        pending.shutdown()
        check(Proto.number(Proto.message(Proto.message(answer, 19), 10), 2, 0) == 30L, "The new reply completes it")
        // Same number, other content: a new frame (separate numbering per channel, or a restart), not a resend.
        r.receive(HealthCapture.hex(Frames.encode(3, 5, Proto.join(byteArrayOf(1, 1), Proto.command(8, 1, Proto.bytes(10, Proto.bytes(2, bytes("0a0b"))))))))
        check(lists == 2, "Frame 5 with new content is dispatched")
        check(r.traces.take(3) == listOf("Received frame 5 on channel 1", "Received frame 5 on channel 1", "Received frame 7 on channel 1"), "First frames of a session are traced")
        r.link.reset(IOException("Watch disconnected"))
        r.receive("a5a50305180069100101080810015210120e010203040003010a0b0c0d000401")
        check(lists == 3, "A new session (connect or repeated session config) starts a fresh history")
        r = Rig()
        repeat(SppLink.TRACE_FRAMES + 5) { r.receive(HealthCapture.hex(Frames.encode(3, it, byteArrayOf(9, 1)))) }
        check(r.traces.size == SppLink.TRACE_FRAMES && r.traces.last() == "Received frame 63 on channel 9", "Tracing stops after ${SppLink.TRACE_FRAMES} frames")
        r.link.reset(IOException("Watch disconnected"))
        r.receive(HealthCapture.hex(Frames.encode(3, 0, byteArrayOf(9, 1))))
        check(r.traces.size == SppLink.TRACE_FRAMES + 1, "A new session is traced again")

        val h = ReceiveHistory()
        check((0 until 300).all { h.add(it and 255, 7L) && !h.add(it and 255, 7L) }, "300 sequential frames with equal content: each accepted once")
        h.clear()
        check(h.add(5, 1L) && h.add(6, 2L) && !h.add(5, 1L) && !h.add(6, 2L), "Resend within the history rejected")
        check(h.add(5, 3L) && !h.add(5, 3L), "Same number, other content accepted")
        h.clear()
        check(h.add(0, 1L) && (129..255).all { h.add(it, 1L) } && h.add(0, 1L), "Wrap with a gap: 0 is new again 129 numbers later")
        h.clear()
        check(h.add(0, 1L) && (1..127).all { h.add(it, 1L) } && !h.add(0, 1L), "0 is still a resend 127 numbers later")
        h.add(200, 1L)
        h.clear()
        check(h.add(200, 1L), "Cleared history accepts any frame")

        check(SppLink.REPLY_TIMEOUT_MS == 15_000L && SppLink.REPLY_TIMEOUT_MS > SppLink.RESEND_TIMEOUT_MS, "Reply timeout outlasts one resend cycle")

        // A command still queued when its caller gives up is cancelled and never runs.
        val busy = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        busy.execute { release.await() }
        val ran = AtomicBoolean(false)
        var timedOut: String? = null
        try {
            callWithin(busy, 50, Callable { ran.set(true) })
        } catch (e: IOException) {
            timedOut = e.message
        }
        release.countDown()
        check(callWithin(busy, 5000, Callable { 42 }) == 42, "A command that runs in time returns its result")
        busy.shutdown()
        busy.awaitTermination(5, TimeUnit.SECONDS)
        check(timedOut == "Watch command timed out" && !ran.get(), "Timed-out queued command never runs")

        // A stalled socket write blocks neither send() nor the resend timer, which drops the link.
        val stall = CountDownLatch(1)
        val stalled = object : OutputStream() {
            override fun write(b: Int) = stall.await()
        }
        val writer = FrameWriter(stalled) {}
        val timer = ManualTimer()
        val drops: MutableList<String> = Collections.synchronizedList(ArrayList())
        val link = SppLink(writer::write, timer, {}, { drops.add(it) }).apply { authenticated() }
        fun finishes(f: () -> Unit): Boolean {
            val t = Thread(f)
            t.isDaemon = true
            t.start()
            t.join(2000)
            return !t.isAlive
        }
        check(finishes { repeat(3) { link.send(byteArrayOf(it.toByte())) } }, "send() returns while the socket write is stalled")
        check(finishes { repeat(3) { timer.fire() } } && drops == listOf("No link acknowledgement for frame 0"), "Resend timer drops a stalled link")
        stall.countDown()
        writer.close()

        val failures: MutableList<String> = Collections.synchronizedList(ArrayList())
        val broken = FrameWriter(
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("Broken pipe")
            },
        ) { failures.add(it.message ?: "") }
        broken.write(byteArrayOf(1))
        val until2 = System.currentTimeMillis() + 2000
        while (failures.isEmpty() && System.currentTimeMillis() < until2) {
            Thread.sleep(5)
        }
        broken.close()
        var closed: String? = null
        try {
            broken.write(byteArrayOf(2))
        } catch (e: IOException) {
            closed = e.message
        }
        check(failures == listOf("Broken pipe") && closed == "Watch is disconnected", "Write failure is reported once; a closed writer refuses frames")

        uploadData()

        println("SPP link dispatch, fire-and-forget, channel 5, channel 2 uploads, ack/nack/resend, writer, cancellation: $assertions assertions passed")
    }
}
