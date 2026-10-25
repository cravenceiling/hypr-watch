package local.hyprwatch

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.CRC32

/** Bounded SPP v1 discovery and v2 framing. Protocol from Gadgetbridge, AGPL-3.0. */
object Frames {
    val VERSION = bytes(0xba, 0xdc, 0xfe, 0, 0xc0, 3, 0, 0, 0, 0, 0xef)
    val SESSION = bytes(1, 1, 3, 0, 1, 0, 0, 2, 2, 0, 0, 0xfc, 3, 2, 0, 32, 0, 4, 2, 0, 16, 39)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** CRC-16/ARC over unsigned bytes. */
    fun crc(b: ByteArray): Int {
        var crc = 0
        for (x in b) {
            crc = crc xor (x.toInt() and 255)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xa001 else crc ushr 1
            }
        }
        return crc
    }

    /** Bytes before a v2 frame's payload: magic, type, sequence, length, CRC. */
    const val HEADER = 8

    fun encode(type: Int, seq: Int, p: ByteArray): ByteArray {
        if (p.size > 65535) throw IllegalArgumentException("Frame too big")
        val c = crc(p)
        val header = bytes(0xa5, 0xa5, type, seq, p.size, p.size ushr 8, c, c ushr 8)
        return Proto.join(header, p)
    }

    fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8)

    /** Session-config entries by key: [opcode] then key u8, length u16 LE, value; a truncated entry ends the list. */
    fun sessionEntries(p: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 1
        while (i + 3 <= p.size) {
            val key = p[i].toInt() and 255
            val len = u16(p, i + 1)
            i += 3
            if (i + len > p.size) break
            out.add(key to p.copyOfRange(i, i + len))
            i += len
        }
        return out
    }

    /** A u16 session-config value ([MAX_PACKET], [TX_WINDOW]), or null when absent. */
    fun sessionValue(p: ByteArray, key: Int): Int? = sessionEntries(p).firstOrNull { it.first == key && it.second.size == 2 }?.let { u16(it.second, 0) }

    const val MAX_PACKET = 2
    const val TX_WINDOW = 3

    /** Readable session-config limits. */
    fun sessionConfig(p: ByteArray): String {
        if (p.isEmpty()) return "empty"
        val parts = ArrayList<String>()
        for ((key, v) in sessionEntries(p)) {
            val len = v.size
            parts.add(
                when {
                    key == 1 && len == 3 -> "version ${v[0]}.${v[1]}.${v[2]}"
                    key == 2 && len == 2 -> "max packet ${u16(v, 0)} bytes"
                    key == 3 && len == 2 -> "tx window ${u16(v, 0)} frames"
                    key == 4 && len == 2 -> "send timeout ${u16(v, 0)} ms"
                    else -> "key $key = ${v.joinToString("") { "%02x".format(it.toInt() and 255) }}"
                },
            )
        }
        return "opcode ${p[0].toInt() and 255}: " + parts.joinToString(", ")
    }

    class Packet {
        var type = 0
        var seq = 0
        var channel = 0
        var opcode = 0
        var payload = ByteArray(0)
    }

    /** [log] hears about v2 frames dropped for a bad checksum; they are not acked, so the watch resends them. */
    class Reader(private val log: (String) -> Unit = {}) {
        private var buffer = ByteArray(0)

        fun add(b: ByteArray, n: Int) {
            if (n + buffer.size > 131072) throw IllegalArgumentException("Receive buffer overflow")
            buffer = Proto.join(buffer, b.copyOf(n))
        }

        @Throws(IOException::class)
        fun next(v2: Boolean): Packet? {
            val header = if (v2) 8 else 11
            while (buffer.size >= header) {
                val magic = if (v2) {
                    (buffer[0].toInt() and 255) == 0xa5 && (buffer[1].toInt() and 255) == 0xa5
                } else {
                    (buffer[0].toInt() and 255) == 0xba && (buffer[1].toInt() and 255) == 0xdc && (buffer[2].toInt() and 255) == 0xfe
                }
                if (!magic) {
                    buffer = buffer.copyOfRange(1, buffer.size)
                    continue
                }
                val len = if (v2) u16(buffer, 4) else u16(buffer, 5) - 3
                if (len < 0 || len > 65535) throw IOException("Invalid Bluetooth frame size")
                val total = header + len
                if (buffer.size < total) return null
                val raw = buffer.copyOf(total)
                buffer = buffer.copyOfRange(total, buffer.size)
                val p = Packet()
                if (v2) {
                    p.type = raw[2].toInt() and 15
                    p.seq = raw[3].toInt() and 255
                    val payload = raw.copyOfRange(8, total)
                    if (crc(payload) != u16(raw, 6)) {
                        log("Dropped frame ${p.seq} with a bad checksum")
                        continue
                    }
                    p.payload = payload
                    if (p.type == 3) {
                        if (payload.size < 2) throw IOException("Truncated data frame")
                        p.channel = payload[0].toInt() and 15
                        p.opcode = payload[1].toInt() and 255
                        p.payload = payload.copyOfRange(2, payload.size)
                    } else if (p.type == 2) {
                        p.opcode = if (payload.isNotEmpty()) payload[0].toInt() and 255 else -1
                    }
                } else {
                    if ((raw[total - 1].toInt() and 255) != 0xef) throw IOException("Invalid frame ending")
                    p.channel = raw[3].toInt() and 15
                    p.payload = raw.copyOfRange(10, total - 1)
                }
                return p
            }
            return null
        }
    }
}

/**
 * Writes frames in order on its own thread, so a stalled socket blocks neither the link lock, the
 * reader nor the timers; the resend timeout can then still drop the link. [failed] runs once if a
 * write fails before [close].
 */
class FrameWriter(private val out: OutputStream, private val failed: (IOException) -> Unit) {
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED)
    @Volatile private var closed = false
    private val thread = Thread({ run() }, "watch-writer")

    init {
        thread.isDaemon = true
        thread.start()
    }

    fun write(frame: ByteArray) {
        if (closed) throw IOException("Watch is disconnected")
        if (!queue.offer(frame)) throw IOException("Watch is not accepting data")
    }

    /** Stops the thread; a write blocked in the socket ends when the caller closes the socket. */
    fun close() {
        closed = true
        thread.interrupt()
    }

    private fun run() {
        try {
            while (true) {
                val f = queue.take()
                out.write(f)
                out.flush()
            }
        } catch (e: InterruptedException) {
        } catch (e: IOException) {
            if (!closed) failed(e)
        }
    }

    companion object {
        const val MAX_QUEUED = 1024
    }
}

/**
 * Runs [task] on [executor] and waits [ms]. On timeout the task is cancelled, so a command still
 * queued is never sent after its caller has reported failure.
 */
fun <T> callWithin(executor: ExecutorService, ms: Long, task: Callable<T>): T {
    val f = executor.submit(task)
    try {
        return f.get(ms, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        f.cancel(false)
        throw IOException("Watch command timed out")
    }
}

/** Runs [task] after [ms] on a timer thread; the returned function cancels it. */
fun interface WatchTimer {
    fun after(ms: Long, task: () -> Unit): () -> Unit
}

/*
 * SendWindow and SppLink's ack/nack/resend logic are ported from Gadgetbridge's
 * XiaomiSppV2SendWindow (Copyright (C) 2026 Dany Mestas) and XiaomiSppProtocolV2
 * (Copyright (C) 2024 Yoran Vulker), AGPL-3.0-or-later.
 */

/**
 * Unacked v2 data frames. Sequence numbers are 8 bit; acks are cumulative. A frame stays until it
 * is acked, so a nack can always be answered; when [MAX_TRACKED] frames are waiting, [add] refuses.
 * Channel-2 upload frames share the numbering and are also counted apart ([uploads]).
 */
class SendWindow {
    private class Frame(val seq: Int, val bytes: ByteArray, val upload: Boolean)

    private val unacked = ArrayDeque<Frame>()
    private var next = 0
    private var uploadFrames = 0
    private var uploadsAdded = 0

    /** Frames [encode]d with the next sequence number, or null when the window is full. */
    @Synchronized
    fun add(upload: Boolean = false, encode: (Int) -> ByteArray): ByteArray? {
        if (unacked.size >= MAX_TRACKED) return null
        val f = Frame(next, encode(next), upload)
        unacked.addLast(f)
        if (upload) {
            uploadFrames++
            uploadsAdded++
        }
        next = (next + 1) and 255
        return f.bytes
    }

    /** Takes back the frame [add] just returned, and its sequence number, after it could not be written. */
    @Synchronized
    fun unsend(frame: ByteArray) {
        check(unacked.lastOrNull()?.bytes === frame)
        val f = unacked.removeLast()
        if (f.upload) {
            uploadFrames--
            uploadsAdded--
        }
        next = f.seq
    }

    /** Frames released, 0 if [seq] matches no unacked frame. */
    @Synchronized
    fun onAck(seq: Int): Int {
        if (unacked.none { it.seq == seq }) return 0
        var released = 0
        do {
            val f = unacked.removeFirst()
            if (f.upload) uploadFrames--
            released++
        } while (f.seq != seq)
        return released
    }

    @Synchronized
    fun oldest(): Int = unacked.firstOrNull()?.seq ?: -1

    @Synchronized
    fun isEmpty(): Boolean = unacked.isEmpty()

    @Synchronized
    fun size(): Int = unacked.size

    /** Unacked channel-2 upload frames. */
    @Synchronized
    fun uploads(): Int = uploadFrames

    /** Channel-2 upload frames added since [clear]. */
    @Synchronized
    fun uploadsSent(): Int = uploadsAdded

    @Synchronized
    fun frames(): List<ByteArray> = unacked.map { it.bytes }

    @Synchronized
    fun clear() {
        unacked.clear()
        uploadFrames = 0
        uploadsAdded = 0
        next = 0
    }

    companion object {
        /** Half the sequence space, so a sequence number names at most one tracked frame. */
        const val MAX_TRACKED = 128
    }
}

/**
 * Recent v2 data frames from the watch: a CRC32 of each frame's content by sequence number. A frame
 * resent because our ack was lost is byte-identical, so it is acked again but not handled twice;
 * other content under a remembered number is a new frame. Accepting number s forgets s+1..s+127,
 * which the watch can only send next as new frames.
 */
class ReceiveHistory {
    private val hashes = LongArray(256) { NONE }

    /** False if [seq] with content [hash] was received recently. */
    @Synchronized
    fun add(seq: Int, hash: Long): Boolean {
        if (hashes[seq] == hash) return false
        hashes[seq] = hash
        for (k in 1 until SendWindow.MAX_TRACKED) hashes[(seq + k) and 255] = NONE
        return true
    }

    @Synchronized
    fun clear() {
        hashes.fill(NONE)
    }

    private companion object {
        const val NONE = -1L
    }
}

/**
 * SPP v2 data link after the version probe: go-back-N sending, link acks, channel routing and
 * command dispatch. [out] runs under the link lock and must not block (see [FrameWriter]);
 * [drop] must close the connection. [trace] hears the numbering of a session's first frames and,
 * during an upload ([uploadPhase], or upload frames in flight), every frame received (kind,
 * channel, opcode, command; never payload bytes). Encrypted sends and upload data go out only
 * between [authenticated] and the next [reset]. Each reset starts a new [epoch]; upload calls name
 * the epoch they belong to.
 */
class SppLink(
    private val out: (ByteArray) -> Unit,
    private val timer: WatchTimer,
    private val log: (String) -> Unit,
    private val drop: (String) -> Unit,
    private val trace: (String) -> Unit = {},
) {
    private class Pending(val type: Int, val sub: Int) {
        val reply = CompletableFuture<Map<Int, List<Any>>>()

        // Reminder writes answer with a different subtype than the request.
        fun matches(t: Int, s: Int): Boolean =
            t == type && (s == sub || t == 17 && sub == 15 && s == 16 || t == 17 && (sub == 17 || sub == 18) && s == 19)
    }

    @Volatile var encrypt: (ByteArray) -> ByteArray = { it }
    @Volatile var decrypt: (ByteArray) -> ByteArray = { it }
    @Volatile var onChunk: ((ByteArray) -> Unit)? = null

    /** Runs on the reader thread after an ack frees room in the send window. */
    @Volatile var onAcked: (() -> Unit)? = null
    @Volatile var lastCommandAt = 0L
        private set
    @Volatile var acks = 0
        private set

    /** Bumped by every [reset], under the link lock: upload frames of an earlier epoch were dropped, never acked. */
    @Volatile var epoch = 0L
        private set

    private val handlers = ConcurrentHashMap<Int, (Map<Int, List<Any>>) -> Unit>()
    private val window = SendWindow()
    private val received = ReceiveHistory()
    private val io = Any()
    private var cancelResend: (() -> Unit)? = null
    private var resendGen = 0
    private var timeouts = 0
    private var authed = false
    @Volatile private var traced = 0

    // Frames received during the current upload, for the line [reset] logs when it ends one. Guarded by [io].
    private var framesDuringUpload = 0
    private var lastDuringUpload = ""

    // Set by [uploadPhase] from the upload's reservation or 22/0 request until it ends; written under [io].
    @Volatile private var phase = false

    // From the watch's session config; until one arrives, one upload frame at a time.
    @Volatile private var txWindow = 1
    @Volatile private var maxPacket = 65535

    // Set under the link lock and failed by reset() under it, so a request is either failed by a reset or refused after one.
    @Volatile private var pending: Pending? = null

    /** Handles a watch-initiated command; replies to request() never reach handlers. */
    fun on(type: Int, sub: Int, handler: (Map<Int, List<Any>>) -> Unit) {
        handlers[key(type, sub)] = handler
    }

    /**
     * A new session: on connect, on disconnect, and when the watch sends its session config again.
     * The pending request fails with [cause]; encrypted sends are refused until [authenticated].
     */
    fun reset(cause: Exception) {
        synchronized(io) {
            val inFlight = window.uploads()
            val sent = window.uploadsSent()
            if (inFlight > 0 || phase) {
                val where = if (sent == 0) "before its first chunk" else "$inFlight of $sent upload frames unacked"
                log("Link reset during an upload (${cause.message}): $where, $framesDuringUpload frames received meanwhile, last: ${lastDuringUpload.ifEmpty { "none" }}")
            }
            framesDuringUpload = 0
            lastDuringUpload = ""
            epoch++
            authed = false
            pending?.reply?.completeExceptionally(cause)
            pending = null
            window.clear()
            received.clear()
            stopResendTimer()
            timeouts = 0
            traced = 0
        }
        acks = 0
    }

    /** The watch accepted this session's keys. */
    fun authenticated() {
        synchronized(io) { authed = true }
    }

    /** The watch's session config (type 2, opcode 2 payload): its tx window and max packet size bound [sendData]. */
    fun configure(sessionConfig: ByteArray) {
        Frames.sessionValue(sessionConfig, Frames.TX_WINDOW)?.let { if (it > 0) txWindow = it }
        Frames.sessionValue(sessionConfig, Frames.MAX_PACKET)?.let { if (it > 2) maxPacket = it }
    }

    /** How many frames may await an ack before [sendData] holds back: the watch's tx window, at most [UPLOAD_WINDOW_CAP]. */
    fun uploadWindow(): Int = txWindow.coerceIn(1, UPLOAD_WINDOW_CAP)

    /**
     * An upload is under way from [on] until it ends, including its handshake (a face's 4/4, the
     * 22/0 request and ack) before any chunk: frames received meanwhile are traced, and a [reset]
     * logs where it stood. The uploader sets it; a reset leaves it.
     */
    fun uploadPhase(on: Boolean) {
        synchronized(io) {
            if (on && !phase) {
                framesDuringUpload = 0
                lastDuringUpload = ""
            }
            phase = on
        }
    }

    /** Untracked frame: version probe, session config, link acks. */
    fun raw(frame: ByteArray) {
        synchronized(io) { out(frame) }
    }

    /**
     * Fire-and-forget on channel 1; only link acks are awaited, never a command reply. Throws
     * IOException, sending nothing, when the session is not authenticated (encrypted only),
     * [SendWindow.MAX_TRACKED] frames still wait for an ack, or the writer refuses the frame.
     */
    fun send(cmd: ByteArray, encrypted: Boolean = true) {
        synchronized(io) {
            if (encrypted && !authed) throw IOException("Watch is not connected")
            // Encrypted under the lock too, so a frame never carries an earlier session's keys.
            write(Proto.join(byteArrayOf(1, if (encrypted) 2 else 1), if (encrypted) encrypt(cmd) else cmd), false)
        }
    }

    /**
     * As [send], encrypted, but paced like [sendData]: false, sending nothing, while [uploadWindow]
     * frames of any kind await an ack. For a bulk sender (health file requests and acks), which goes
     * on from [onAcked] and so leaves the window to other commands.
     */
    fun sendPaced(cmd: ByteArray): Boolean {
        synchronized(io) {
            if (!authed) throw IOException("Watch is not connected")
            if (window.size() >= uploadWindow()) return false
            write(Proto.join(byteArrayOf(1, 2), encrypt(cmd)), false)
            return true
        }
    }

    /**
     * One upload chunk of [epoch], plaintext on channel 2 (`[2][1][chunk]`), numbered and resent in
     * the same window as commands. Paced: false, sending nothing, while [uploadWindow] frames of any
     * kind await an ack, so commands always find room and the watch sees at most its tx window of
     * upload frames; retry from [onAcked]. Throws IOException, sending nothing, when [epoch] has
     * ended or is not authenticated, the whole frame exceeds the watch's max packet (whether that
     * counts the header is unknown, so it does), or the window or writer refuses it.
     */
    fun sendData(epoch: Long, chunk: ByteArray): Boolean {
        val body = Proto.join(byteArrayOf(2, 1), chunk)
        val frame = Frames.HEADER + body.size
        if (frame > maxPacket) throw IOException("Upload chunk of ${chunk.size} bytes makes a $frame-byte frame, over the watch's $maxPacket-byte packets")
        synchronized(io) {
            current(epoch)
            if (!authed) throw IOException("Watch is not connected")
            if (window.size() >= uploadWindow()) return false
            write(body, true)
            return true
        }
    }

    /** Channel-2 upload frames of [epoch] the watch has not acknowledged yet. Throws IOException once [epoch] has ended: its frames were dropped, not acked. */
    fun uploadsInFlight(epoch: Long): Int =
        synchronized(io) {
            current(epoch)
            window.uploads()
        }

    private fun current(epoch: Long) {
        if (epoch != this.epoch) throw IOException("The watch link restarted during the upload")
    }

    private fun enqueue(cmd: ByteArray, request: Pending) {
        synchronized(io) {
            if (!authed) throw IOException("Watch is not connected")
            val body = Proto.join(byteArrayOf(1, 2), encrypt(cmd))
            pending = request
            try {
                write(body, false)
            } catch (e: IOException) {
                pending = null
                throw e
            }
        }
    }

    /** Numbers [body] and writes it, under the link lock so sequence order is wire order. */
    private fun write(body: ByteArray, upload: Boolean) {
        val frame = window.add(upload) { seq -> Frames.encode(3, seq, body) }
            ?: throw IOException("Watch has not acknowledged ${SendWindow.MAX_TRACKED} frames")
        try {
            out(frame)
            if (phase || window.uploads() > 0) trace("Sent during upload: seq ${frame[3].toInt() and 0xff}, channel ${body[0]}, opcode ${body[1]}, ${body.size} bytes")
        } catch (e: IOException) {
            // Otherwise a later resend would deliver a frame its caller saw fail.
            window.unsend(frame)
            throw e
        }
        armResendTimer()
    }

    /** Request/response for commands the watch answers; one at a time (callers use one thread). */
    fun request(cmd: ByteArray, type: Int, sub: Int, timeoutMs: Long = REPLY_TIMEOUT_MS): Map<Int, List<Any>> {
        val p = Pending(type, sub)
        try {
            enqueue(cmd, p)
            val r = p.reply.get(timeoutMs, TimeUnit.MILLISECONDS)
            val status = Proto.number(r, 100, 0)
            if (status != 0L) throw IOException("Watch rejected command $type/$sub with status $status")
            return r
        } catch (e: TimeoutException) {
            drop("No reply to command $type/$sub")
            throw IOException("Watch response timed out; disconnected to avoid matching a late response to another request")
        } finally {
            pending = null
        }
    }

    /** Frames of type 0 (nack), 1 (ack) and 3 (data); session config is handled by the caller. */
    fun receive(p: Frames.Packet) {
        val uploading = phase || window.uploads() > 0
        when (p.type) {
            0 -> {
                if (uploading) duringUpload("nack ${p.seq}")
                onNack(p.seq)
            }
            1 -> {
                if (uploading) duringUpload("ack ${p.seq}")
                onAck(p.seq)
            }
            3 -> {
                if (traced < TRACE_FRAMES) {
                    traced++
                    trace("Received frame ${p.seq} on channel ${p.channel}")
                }
                raw(Frames.encode(1, p.seq, ByteArray(0)))
                val crc = CRC32()
                crc.update(p.channel)
                crc.update(p.opcode)
                crc.update(p.payload)
                val frame = "data ${p.seq} channel ${p.channel} opcode ${p.opcode}"
                if (!received.add(p.seq, crc.value)) {
                    if (uploading) duringUpload("$frame (repeat)")
                    return log("Ignored repeated frame ${p.seq} on channel ${p.channel}")
                }
                when (p.channel) {
                    1 -> {
                        val cmd = Proto.decode(if (p.opcode == 2) decrypt(p.payload) else p.payload)
                        if (uploading) duringUpload("$frame command ${Proto.number(cmd, 1, -1)}/${Proto.number(cmd, 2, -1)}")
                        dispatch(cmd)
                    }
                    else -> {
                        if (uploading) duringUpload(frame)
                        if (p.channel == 5) chunk(p) else log("Ignored ${p.payload.size} bytes on channel ${p.channel}")
                    }
                }
            }
            else -> {
                if (uploading) duringUpload("type ${p.type}")
                log("Ignored frame type ${p.type}")
            }
        }
    }

    /** A frame received during an upload: traced, and remembered for [reset]'s summary. */
    private fun duringUpload(frame: String) {
        synchronized(io) {
            framesDuringUpload++
            lastDuringUpload = frame
        }
        trace("During upload: $frame")
    }

    private fun chunk(p: Frames.Packet) {
        try {
            val body = if (p.opcode == 2) decrypt(p.payload) else p.payload
            val handler = onChunk
            if (handler == null) log("Ignored activity chunk of ${body.size} bytes") else handler(body)
        } catch (e: Exception) {
            log("Activity chunk failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun dispatch(cmd: Map<Int, List<Any>>) {
        val type = Proto.number(cmd, 1, -1).toInt()
        val sub = Proto.number(cmd, 2, -1).toInt()
        if (type != 1) lastCommandAt = System.currentTimeMillis()
        val p = pending
        if (p != null && p.matches(type, sub)) {
            p.reply.complete(cmd)
            return
        }
        val h = handlers[key(type, sub)]
        if (h == null) log("Unhandled watch command $type/$sub with fields ${shape(cmd)}") else h(cmd)
    }

    // Numbers as sent; messages and strings only by size, since they can hold phone numbers or text.
    private fun shape(cmd: Map<Int, List<Any>>): String =
        cmd.entries.joinToString(", ", "{", "}") { (f, values) ->
            "$f=" + values.joinToString("|") { if (it is ByteArray) "${it.size} bytes" else it.toString() }
        }

    private fun onAck(seq: Int) {
        acks++
        if (window.onAck(seq) == 0) return
        synchronized(io) {
            timeouts = 0
            stopResendTimer()
            if (!window.isEmpty()) armResendTimer()
        }
        onAcked?.invoke()
    }

    /** A nack names the next sequence number the watch expects; everything before it is acked. */
    private fun onNack(expected: Int) {
        onAck((expected - 1) and 255)
        if (window.oldest() != expected) {
            log("Watch expects frame $expected, which is not pending")
            return
        }
        log("Watch expects frame $expected; resending")
        resend()
    }

    private fun resend() {
        synchronized(io) {
            for (f in window.frames()) {
                out(f)
            }
            armResendTimer()
        }
    }

    private fun armResendTimer() {
        if (cancelResend != null) return
        val gen = ++resendGen
        cancelResend = timer.after(RESEND_TIMEOUT_MS) { onResendTimeout(gen) }
    }

    private fun stopResendTimer() {
        cancelResend?.invoke()
        cancelResend = null
        resendGen++
    }

    private fun onResendTimeout(gen: Int) {
        val n: Int
        synchronized(io) {
            if (gen != resendGen) return
            cancelResend = null
            if (window.isEmpty()) {
                timeouts = 0
                return
            }
            n = ++timeouts
        }
        if (n >= MAX_TIMEOUTS) {
            drop("No link acknowledgement for frame ${window.oldest()}")
            return
        }
        log("No ack for frame ${window.oldest()}; resending")
        try {
            resend()
        } catch (e: IOException) {
            drop(e.message ?: "Watch write failed")
        }
    }

    companion object {
        /** Same value as the send timeout this app announces in its session config. */
        const val RESEND_TIMEOUT_MS = 10_000L
        const val MAX_TIMEOUTS = 3
        const val TRACE_FRAMES = 64

        /** Longer than one resend cycle, so a lost request frame can be resent before the link is dropped. */
        const val REPLY_TIMEOUT_MS = 15_000L

        /** Upload frames in flight never exceed this, whatever tx window the watch announces. */
        const val UPLOAD_WINDOW_CAP = 4

        private fun key(type: Int, sub: Int): Int = (type shl 16) or (sub and 0xffff)
    }
}
