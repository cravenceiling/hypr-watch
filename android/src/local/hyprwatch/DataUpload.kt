/*  Data upload (type 22) and its channel-2 transfer.

    Ported in part from Gadgetbridge's XiaomiDataUploadService.java (requestUpload, handleCommand,
    doUpload), Copyright (C) 2023-2024 José Rebelo. Gadgetbridge is free software: you can
    redistribute it and/or modify it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the License, or (at your option)
    any later version. Pacing by link acks and completion on the last ack are this app's. */
package local.hyprwatch

import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

/**
 * Uploads one file at a time to the watch, for the notification icons and watch faces. 22/0 asks
 * the watch to take a file of a type with its MD5 and size; the watch's 22/0 ack names the chunk
 * size and resume position; the payload ([payload]) then goes out in parts on channel 2, as many
 * in flight as the link's upload window allows, the next ones on each link ack. The upload is done
 * when the watch has link-acked the last part of this link epoch: it sends no protobuf
 * confirmation. It fails, calling back once, when the watch refuses (status or errno), does not
 * answer the request within [ackTimeoutMs], acks nothing for [stallMs], or the session or link
 * epoch ends. A caller that must ask the watch first (a face's 4/4) [reserve]s the uploader.
 *
 * Never blocks: the reader thread runs the 22/0 handler and the ack pump. Callbacks run without
 * this object's lock, on the reader or timer thread or the thread that saw the disconnect, and must
 * not block either.
 */
class DataUpload(
    private val ackTimeoutMs: Long = 10_000,
    private val stallMs: Long = 35_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : WatchFeature {
    override val name = "data_upload"
    private lateinit var host: FeatureHost

    private class Upload(
        val type: Int,
        var data: ByteArray?,
        val size: Int,
        val md5: ByteArray,
        val session: Long,
        val ticket: Long,
        val epoch: Long,
        val done: (String?) -> Unit,
        val startedAt: Long,
    ) {
        var state = REQUESTING
        var payload: ByteArray? = null
        var chunkSize = 0
        var resume = 0
        var partSize = 0
        var total = 0

        /** Parts handed to the link, and parts the watch acked. */
        var sent = 0
        var acked = 0

        /** Bumped on every send and ack, so the stall timer sees movement. */
        var progress = 0L
        var error: String? = null
        var finishedAt: Long? = null
    }

    private val lock = Object()

    // Guarded by [lock]. 22/0 requests and replies in this session: the n-th reply answers the n-th request.
    private var session = 0L
    private var requests = 0L
    private var replies = 0L
    private var current: Upload? = null

    /** Holds the uploader for one caller from [reserve] until its [start] or [release]; a new session voids it. */
    private var reserved: Reservation? = null

    private val idle = CopyOnWriteArrayList<() -> Unit>()

    /** A claim on the uploader; see [reserve]. */
    class Reservation internal constructor()

    override fun install(host: FeatureHost) {
        this.host = host
        host.on(TYPE, START) { cmd -> onReply(cmd) }
        host.onAcked { pump() }
    }

    override fun onConnected() = newSession("The watch reconnected")

    override fun onDisconnected() = newSession("The watch disconnected")

    private fun newSession(why: String) {
        val callback = synchronized(lock) {
            session++
            requests = 0
            replies = 0
            reserved = null
            traced()
            current?.takeIf { it.active }?.let { fail(it, why) }
        }
        callback?.invoke()
    }

    /** True while an upload is reserved, requested or sent. */
    val busy: Boolean get() = synchronized(lock) { busyLocked() }

    private fun busyLocked(): Boolean = reserved != null || current?.active == true

    /** Under [lock], after the uploader's state changed: the link traces frames while it is busy. */
    private fun traced() = host.traceUpload(busyLocked())

    /** Runs [listener] each time the uploader becomes free: an upload ended or a reservation was released. Without this object's lock; must not block. */
    fun onIdle(listener: () -> Unit) {
        idle.add(listener)
    }

    /** Holds the uploader so no other upload starts until [start] with it or [release]; null when busy. */
    fun reserve(): Reservation? =
        synchronized(lock) {
            if (busyLocked()) {
                null
            } else {
                Reservation().also {
                    reserved = it
                    traced()
                }
            }
        }

    /** Gives up [r] unless [start] took it or the session ended. */
    fun release(r: Reservation) {
        val freed = synchronized(lock) {
            (reserved === r).also {
                if (it) {
                    reserved = null
                    traced()
                }
            }
        }
        if (freed) for (l in idle) l()
    }

    override fun status(): JSONObject =
        synchronized(lock) {
            val u = current ?: return JSONObject().put("state", "idle")
            JSONObject()
                .put("state", u.state)
                .put("kind", kind(u.type))
                .put("size", u.size)
                .put("chunk_size", if (u.chunkSize > 0) u.chunkSize else JSONObject.NULL)
                .put("resume_position", u.resume)
                .put("parts_total", u.total)
                .put("parts_sent", u.sent)
                .put("parts_acked", u.acked)
                .put("percent", if (u.state == DONE) 100 else if (u.total == 0) 0 else u.acked * 100 / u.total)
                .put("error", u.error ?: JSONObject.NULL)
                .put("started_at", u.startedAt)
                .put("finished_at", u.finishedAt ?: JSONObject.NULL)
        }

    /**
     * Asks the watch to take [data] as upload [type]; [done] hears null on success or the error.
     * False, sending nothing, when another upload is running or reserved. With [reservation], the
     * caller's own, it starts in its place. Throws IOException when the watch is not connected, the
     * reservation was voided by a new session, or the request could not be sent; then [done] is never
     * called and a reservation is still held.
     */
    fun start(type: Int, data: ByteArray, reservation: Reservation? = null, done: (String?) -> Unit): Boolean {
        require(type in 1..255 && data.isNotEmpty()) { "Invalid upload" }
        val md5 = MessageDigest.getInstance("MD5").digest(data)
        val u: Upload
        synchronized(lock) {
            if (reservation != null && reserved !== reservation) throw IOException("The watch disconnected")
            if (reserved !== reservation || current?.active == true) return false
            if (!host.connected) throw IOException("Connect the watch first")
            u = Upload(type, data, data.size, md5, session, requests + 1, host.linkEpoch(), done, clock())
            val request = Proto.join(Proto.num(1, type.toLong()), Proto.bytes(2, md5), Proto.num(3, data.size.toLong()))
            if (!host.send(Proto.command(TYPE, START, Proto.bytes(FIELD, Proto.bytes(1, request))), "data upload request")) {
                throw IOException("Could not send the upload request to the watch")
            }
            requests++
            reserved = null
            current = u
            traced()
        }
        host.log("Data upload: requesting ${kind(type)}, ${data.size} bytes")
        host.after(ackTimeoutMs) { onAckTimeout(u) }
        return true
    }

    private fun onAckTimeout(u: Upload) {
        val callback = synchronized(lock) { if (current === u && u.state == REQUESTING) fail(u, "The watch did not answer the upload request") else null }
        callback?.invoke()
    }

    private fun onReply(cmd: Map<Int, List<Any>>) {
        val ack = Proto.message(Proto.message(cmd, FIELD), 2)
        val status = Proto.number(cmd, 100, 0)
        val errno = Proto.number(ack, 2, 0)
        val resume = Proto.number(ack, 4, 0)
        val chunk = Proto.number(ack, 5, DEFAULT_CHUNK)
        val md5 = ack[1]?.firstOrNull() as? ByteArray
        var started = false
        val callback = synchronized(lock) {
            val u = current
            val answers = if (replies < requests) ++replies else 0L
            if (u == null || answers == 0L || answers != u.ticket || u.session != session) {
                // A push, or the late answer to a request that already timed out.
                if (answers == 0L && u != null && u.state == SENDING && (status != 0L || errno != 0L)) {
                    return@synchronized fail(u, "The watch stopped the upload (status $status, error $errno)")
                }
                host.log("Data upload: ignored a 22/0 that answers no current request (status $status, error $errno)")
                return@synchronized null
            }
            if (u.state != REQUESTING) {
                host.log("Data upload: ignored a late 22/0 (status $status, error $errno)")
                return@synchronized null
            }
            when {
                md5 != null && !md5.contentEquals(u.md5) -> fail(u, "The watch answered the upload request for another file")
                status != 0L -> fail(u, "The watch refused the upload (status $status)")
                errno != 0L -> fail(u, "The watch refused the upload (error $errno)")
                chunk < 7 || chunk > MAX_CHUNK -> fail(u, "The watch asked for unusable $chunk-byte chunks")
                resume < 0 || resume > u.size -> fail(u, "The watch asked to resume at $resume of ${u.size} bytes")
                else -> {
                    val payload = payload(u.type, u.data!!, u.md5, resume.toInt())
                    // On SPPv2 the watch's chunk size also counts the channel and opcode bytes, so each part
                    // is chunk - 6, as AstroBox sends it; Gadgetbridge's chunk - 4 dates from SPPv1.
                    val partSize = chunk.toInt() - 6
                    val total = (payload.size + partSize - 1) / partSize
                    if (total > 65535) {
                        fail(u, "The file is too large for the watch's $chunk-byte chunks")
                    } else {
                        u.payload = payload
                        u.data = null
                        u.chunkSize = chunk.toInt()
                        u.resume = resume.toInt()
                        u.partSize = partSize
                        u.total = total
                        u.state = SENDING
                        armStall(u)
                        started = true
                        null
                    }
                }
            }
        }
        callback?.invoke()
        if (started) {
            host.log("Data upload ack: error $errno, resume at $resume, chunk size $chunk")
            pump()
        }
    }

    /** Sends the next parts while the link has room, then checks whether the last one was acked. On the reader thread. */
    private fun pump() {
        var log: String? = null
        val callback = synchronized(lock) {
            val u = current ?: return
            if (u.state != SENDING || u.session != session) return
            val inFlight = try {
                while (u.sent < u.total) {
                    if (!host.sendData(u.epoch, part(u, u.sent))) break
                    u.sent++
                    u.progress++
                    if (u.sent == 1) log = "Data upload: sending ${u.total} parts of up to ${u.chunkSize} bytes"
                }
                // A link reset empties the count without acks; the epoch tells the two apart.
                host.dataInFlight(u.epoch)
            } catch (e: IOException) {
                return@synchronized fail(u, e.message ?: "The watch link refused upload data")
            }
            // Upload frames left over from an abandoned upload in this session count as in flight.
            val acked = (u.sent - inFlight).coerceIn(0, u.sent)
            if (acked > u.acked) {
                u.acked = acked
                u.progress++
            }
            if (u.sent == u.total && inFlight == 0) {
                u.acked = u.total
                finish(u, null)
            } else {
                null
            }
        }
        log?.let(host::log)
        callback?.invoke()
    }

    private fun armStall(u: Upload) {
        val mark = u.progress
        host.after(stallMs) { onStall(u, mark) }
    }

    private fun onStall(u: Upload, mark: Long) {
        val callback = synchronized(lock) {
            if (current !== u || u.state != SENDING) return
            if (u.progress != mark) {
                armStall(u)
                return
            }
            fail(u, "The watch stopped acknowledging upload data (${u.acked} of ${u.total} parts)")
        }
        callback.invoke()
    }

    /** Under [lock]: ends [u] and returns its callback, to run after the lock is released. */
    private fun fail(u: Upload, error: String): () -> Unit = finish(u, error)

    private fun finish(u: Upload, error: String?): () -> Unit {
        u.state = if (error == null) DONE else FAILED
        u.error = error
        u.finishedAt = clock()
        u.payload = null
        u.data = null
        traced()
        val what = kind(u.type)
        val ms = u.finishedAt!! - u.startedAt
        val parts = u.total
        return {
            host.log(if (error == null) "Data upload done: $what, $parts parts acked in $ms ms" else "Data upload of $what failed: $error")
            u.done(error)
            for (l in idle) l()
        }
    }

    private val Upload.active: Boolean get() = state == REQUESTING || state == SENDING

    companion object {
        const val TYPE = 22
        const val START = 0
        const val TYPE_WATCHFACE = 16
        const val TYPE_NOTIFICATION_ICON = 50

        /** `Command.dataUpload`. */
        private const val FIELD = 24

        /** Gadgetbridge's chunk size when the ack names none. */
        const val DEFAULT_CHUNK = 2048L

        /**
         * The largest chunk size the watch may ask for. A frame's payload is the whole chunk: channel and
         * opcode, the 4-byte part header and chunk - 6 data bytes. With the 8-byte frame header it must
         * fit the largest packet a u16 can announce; this bound keeps 2 bytes in hand.
         */
        private const val MAX_CHUNK = 65535L - Frames.HEADER - 2

        private const val REQUESTING = "requesting"
        private const val SENDING = "sending"
        private const val DONE = "done"
        private const val FAILED = "failed"

        fun kind(type: Int): String =
            when (type) {
                TYPE_WATCHFACE -> "watch_face"
                TYPE_NOTIFICATION_ICON -> "notification_icon"
                else -> "type_$type"
            }

        /**
         * `[0][type][MD5 of all of data][size u32 LE][data from resume]` followed by the CRC-32 of
         * all of that, u32 LE, in one allocation. On a resume the MD5 still describes the whole file
         * and the size counts only the bytes that follow, as AstroBox sends it.
         */
        fun payload(type: Int, data: ByteArray, md5: ByteArray, resume: Int): ByteArray {
            val tail = data.size - resume
            val out = ByteArray(2 + md5.size + 4 + tail + 4)
            out[1] = type.toByte()
            System.arraycopy(md5, 0, out, 2, md5.size)
            var i = 2 + md5.size
            le32(out, i, tail.toLong())
            i += 4
            System.arraycopy(data, resume, out, i, tail)
            i += tail
            val crc = CRC32()
            crc.update(out, 0, i)
            le32(out, i, crc.value)
            return out
        }

        /** Part [i] (0-based) of [u]'s payload: `[total u16 LE][i + 1 u16 LE][bytes]`. */
        private fun part(u: Upload, i: Int): ByteArray {
            val p = u.payload!!
            val from = i * u.partSize
            val to = minOf(from + u.partSize, p.size)
            val out = ByteArray(4 + to - from)
            out[0] = u.total.toByte()
            out[1] = (u.total ushr 8).toByte()
            out[2] = (i + 1).toByte()
            out[3] = ((i + 1) ushr 8).toByte()
            System.arraycopy(p, from, out, 4, to - from)
            return out
        }

        private fun le32(out: ByteArray, i: Int, v: Long) {
            for (k in 0 until 4) out[i + k] = (v ushr (8 * k)).toByte()
        }
    }
}
