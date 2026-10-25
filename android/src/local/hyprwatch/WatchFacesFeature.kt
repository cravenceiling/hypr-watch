/*  Watch face list, set active, delete and install (type 4).

    Ported in part from Gadgetbridge's XiaomiWatchfaceService.java and XiaomiFWHelper.java
    (parseAsWatchface), Copyright (C) 2023-2024 José Rebelo. Gadgetbridge is free software: you can
    redistribute it and/or modify it under the terms of the GNU Affero General
    Public License as published by the Free Software Foundation, either version 3
    of the License, or (at your option) any later version. */
package local.hyprwatch

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64

/**
 * The watch's installed faces (4/0), switching (4/1), deleting (4/2) and installing (4/4 then a
 * [DataUpload] of the .bin), and the /api/watch-faces routes. Every command is fire-and-forget with
 * a handler for the reply, since none of them is confirmed to answer on this watch. Routes wait for
 * those replies themselves, for at most [replyTimeoutMs] each, and re-read the list after a write
 * so they report what the watch holds. An install returns once the upload has started; its
 * progress is under `install` in the status. When the watch has acked the last part, the install
 * waits up to [RESULT_MS] for the watch's install result (4/5), as AstroBox does. A failure result
 * ends it; success, or no result in time, switches to the face (4/1) and re-reads the list, which
 * must show the face before the install counts as done.
 *
 * Replies carry no request id. The watch answers in order, so per subtype the n-th reply of a
 * session answers the n-th send; a reply that times out stays owed, and when it arrives late it
 * only updates the cache, never a later route's answer.
 */
class WatchFacesFeature(
    private val replyTimeoutMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uploads: DataUpload? = null,
) : WatchFeature {
    data class Face(val id: String, val name: String, val active: Boolean, val canDelete: Boolean)

    /** A v2 face file's header. [name] is null when it is localised; [preview] is null without one. */
    data class FaceFile(val id: String, val name: String?, val preview: Pair<Int, Int>?, val compatibility: String)

    /** One install, kept after it ends so the status can show how it went. */
    private class Install(val face: FaceFile, val size: Int, val session: Long, val startedAt: Long) {
        var state = UPLOADING
        var error: String? = null
        var verified = false
        var active = false
        var finishedAt: Long? = null

        /** The code of the watch's install result (4/5) once it came. */
        var result: Long? = null

        /** The list read sent after the switch; a list reply counted at or past it answers that read. */
        var listRead = Long.MAX_VALUE
        val running: Boolean get() = state == UPLOADING || state == INSTALLING || state == ACTIVATING
    }

    override val name = "watch_faces"
    private lateinit var host: FeatureHost

    private val lock = Object()

    // Everything below is guarded by [lock].
    private var faces: List<Face>? = null
    private var readAt: Long? = null

    /** null until the watch answers a list read on this connection. */
    private var supported: Boolean? = null
    private var listState = "idle"

    /** Sends and replies per subtype (LIST, SET, DELETE) in this session. */
    private val tickets = HashMap<Int, Tickets>()

    /** Bumped on connect and disconnect; a route's sends and waits belong to the session it began in. */
    private var session = 0L
    private var connectReadPending = false
    private var cancelNoReply: (() -> Unit)? = null
    private var install: Install? = null

    private class Tickets {
        var sent = 0L
        var received = 0L

        /** The send number a route waits on, and its reply once it came. */
        var waiting = 0L
        var reply: Map<Int, List<Any>>? = null
    }

    /** [cmd] null when the watch did not answer in time; [owed] replies to earlier sends were still due when it was sent. */
    private class Answer(val cmd: Map<Int, List<Any>>?, val owed: Long)

    override fun install(host: FeatureHost) {
        this.host = host
        clear()
        host.on(TYPE, LIST) { cmd -> onList(cmd) }
        host.on(TYPE, SET) { cmd -> onWriteReply(SET, cmd) }
        host.on(TYPE, DELETE) { cmd -> onWriteReply(DELETE, cmd) }
        if (uploads != null) {
            host.on(TYPE, INSTALL) { cmd -> onWriteReply(INSTALL, cmd) }
            host.on(TYPE, RESULT) { cmd -> onResult(cmd) }
        }
        host.onAcked { sendConnectRead() }
        host.route("GET", "/api/watch-faces") { r ->
            val refresh = when (r.query["refresh"]) {
                null, "0", "false" -> false
                "1", "true" -> true
                else -> throw IllegalArgumentException("Use refresh=true or refresh=false")
            }
            if (refresh) host.operation { readList(begin()) }
            listing()
        }
        host.route("POST", "/api/watch-faces/{id}/activate") { r -> activate(checkedId(r.params.getValue("id"))) }
        host.route("DELETE", "/api/watch-faces/{id}") { r -> delete(checkedId(r.params.getValue("id"))) }
        if (uploads != null) host.route("POST", "/api/watch-faces/upload") { r -> install(uploads, r.raw) }
    }

    override fun onConnected() {
        synchronized(lock) {
            session++
            clear()
            supported = null
            listState = "waiting"
            connectReadPending = true
        }
        sendConnectRead()
        val cancel = host.after(NO_REPLY_MS) {
            synchronized(lock) { if (listState == "waiting") listState = "no_reply" }
        }
        synchronized(lock) { cancelNoReply = cancel }
    }

    override fun onDisconnected() {
        val cancel = synchronized(lock) {
            session++
            clear()
            listState = "idle"
            connectReadPending = false
            install?.let { job ->
                // An upload usually ended first (DataUpload's hook runs before this one).
                if (job.state == UPLOADING) end(job, FAILED, DISCONNECTED)
                if (job.running) end(job, FAILED, UNCONFIRMED)
            }
            lock.notifyAll()
            cancelNoReply.also { cancelNoReply = null }
        }
        cancel?.invoke()
    }

    override fun status(): JSONObject {
        val upload = uploads?.status()
        return synchronized(lock) {
            JSONObject()
                .put("count", faces?.size ?: JSONObject.NULL)
                .put("active_id", faces?.firstOrNull { it.active }?.id ?: JSONObject.NULL)
                .put("read_at", readAt ?: JSONObject.NULL)
                .put("supported", supported ?: JSONObject.NULL)
                .put("list_state", listState)
                .put("install", installJson(upload))
        }
    }

    /** Under [lock]; [upload] is the uploader's status, read before taking the lock. */
    private fun installJson(upload: JSONObject?): Any {
        val job = install ?: return JSONObject.NULL
        val j = JSONObject()
            .put("state", job.state)
            .put("id", job.face.id)
            .put("name", job.face.name ?: JSONObject.NULL)
            .put("size", job.size)
            .put("preview", job.face.preview?.let { "${it.first}x${it.second}" } ?: JSONObject.NULL)
            .put("compatibility", job.face.compatibility)
            .put("error", job.error ?: JSONObject.NULL)
            .put("verified", job.verified)
            .put("active", job.active)
            .put("started_at", job.startedAt)
            .put("finished_at", job.finishedAt ?: JSONObject.NULL)
        val percent = when (job.state) {
            UPLOADING -> upload?.takeIf { it.optString("kind") == DataUpload.kind(DataUpload.TYPE_WATCHFACE) }?.optInt("percent", 0) ?: 0
            FAILED -> JSONObject.NULL
            else -> 100
        }
        j.put("percent", percent)
        if (job.state == UPLOADING && upload != null) j.put("parts_acked", upload.opt("parts_acked")).put("parts_total", upload.opt("parts_total"))
        return j
    }

    /** Faces as last read; GET /api/watch-faces. Never waits for the watch. */
    fun listing(): JSONObject =
        synchronized(lock) {
            val list = faces
            JSONObject()
                .put("connected", host.connected)
                .put("supported", supported ?: JSONObject.NULL)
                .put("list_state", listState)
                .put("read_at", readAt ?: JSONObject.NULL)
                .put("active_id", list?.firstOrNull { it.active }?.id ?: JSONObject.NULL)
                .put("faces", list?.let { l -> JSONArray(l.map(::json)) } ?: JSONObject.NULL)
        }

    private fun activate(id: String): JSONObject =
        host.operation {
            val s = begin()
            notInstalling()
            val face = knownFace(id, s)
            if (face.active) return@operation result(id, "activate", null, true, false)
            val reply = write(SET, id, "watch face switch", s)
            val verified = readList(s).any { it.id == id && it.active }
            refused(reply, "switch to watch face $id")
            result(id, "activate", reply, verified, true)
        }

    private fun delete(id: String): JSONObject =
        host.operation {
            val s = begin()
            notInstalling()
            val face = knownFace(id, s)
            require(face.canDelete) { "Watch face $id is built in and cannot be deleted" }
            require(!face.active) { "Watch face $id is the active face; activate another face before deleting it" }
            val reply = write(DELETE, id, "watch face delete", s)
            val verified = readList(s).none { it.id == id }
            refused(reply, "delete watch face $id")
            result(id, "delete", reply, verified, true)
        }

    /**
     * POST /api/watch-faces/upload: `{"data": base64 of the .bin}` as [raw] bytes. Checks the file,
     * reserves the uploader, asks the watch (4/4) and starts the upload; returns while it runs.
     */
    private fun install(up: DataUpload, raw: ByteArray): JSONObject {
        val file = decodeFile(raw)
        val face = parseFace(file)
        return host.operation {
            val s = begin()
            synchronized(lock) {
                if (install?.running == true) throw Http.Conflict("A watch face is already being installed")
                if (faces?.any { it.id == face.id } == true) throw Http.Conflict("Watch face ${face.id} is already installed; delete it first")
            }
            // Held from before 4/4, so no icon takes the uploader while the watch prepares for the face.
            val reservation = up.reserve() ?: throw Http.Conflict("Another upload to the watch is in progress")
            try {
                val start = Proto.command(TYPE, INSTALL, Proto.bytes(6, Proto.bytes(6, Proto.join(Proto.text(1, face.id), Proto.num(2, file.size.toLong()), Proto.num(3, 65536)))))
                val reply = exchange(INSTALL, s, start, "watch face install start").cmd
                    ?: throw IOException("The watch did not answer the watch face install start")
                refused(reply, "start installing watch face ${face.id}")
                val status = prepareStatus(reply)?.second ?: throw IOException("The watch answered the watch face install start without a status")
                when (status) {
                    0L -> {}
                    2L -> throw Http.Conflict("Watch face ${face.id} is already installed; delete it first")
                    else -> throw IOException("The watch refused to install watch face ${face.id}: ${PREPARE[status] ?: "unknown reason"} (prepare status $status)")
                }
                val job = Install(face, file.size, s, clock())
                synchronized(lock) {
                    if (session != s) throw IOException("The watch disconnected")
                    install = job
                }
                try {
                    // Never false: the reservation holds the uploader.
                    up.start(DataUpload.TYPE_WATCHFACE, file, reservation) { error -> uploaded(job, error) }
                } catch (e: IOException) {
                    synchronized(lock) { end(job, FAILED, e.message) }
                    throw e
                }
            } finally {
                up.release(reservation)
            }
            host.log("Watch face ${face.id} install: uploading ${file.size} bytes")
            listing().put("install", status().get("install"))
        }
    }

    /** Switching or deleting faces while one installs could pull the face list out from under it. */
    private fun notInstalling() {
        synchronized(lock) { if (install?.running == true) throw Http.Conflict("A watch face is being installed; try again when it finishes") }
    }

    /** The upload of [job] ended; on success wait for the install result, unless it came first. Never blocks. */
    private fun uploaded(job: Install, error: String?) {
        val next = synchronized(lock) {
            if (install !== job || job.state != UPLOADING) return
            if (error != null || session != job.session) {
                end(job, FAILED, error ?: DISCONNECTED)
                return
            }
            job.state = INSTALLING
            job.result?.let { installed(job, it) } ?: INSTALLING
        }
        await(job, next)
    }

    /** 4/5: the watch's install result, `Watchface.installResult` (7) {1 id, 2 code}. */
    private fun onResult(cmd: Map<Int, List<Any>>) {
        val result = Proto.message(Proto.message(cmd, 6), 7)
        val id = Proto.string(result, 1)
        val code = Proto.number(result, 2, -1)
        host.log("Watch face $id install result: code $code")
        val (job, next) = synchronized(lock) {
            val job = install?.takeIf { it.face.id == id && it.session == session } ?: return
            when (job.state) {
                // The result came before the last part's link ack.
                UPLOADING -> job.result = code
                INSTALLING -> return@synchronized job to installed(job, code)
                // Past the 120 s wait: a success still counts if the list read goes unanswered.
                ACTIVATING -> if (code == INSTALL_SUCCESS || code == INSTALL_USED) job.result = code
            }
            return
        }
        await(job, next)
    }

    /** Under [lock]: [job] got result [code]; the state it moved to. */
    private fun installed(job: Install, code: Long): String {
        job.result = code
        if (code == INSTALL_SUCCESS || code == INSTALL_USED) return activating(job)
        val why = when (code) {
            VERIFY_FAILED -> "it failed the watch's verification"
            INSTALL_FAILED -> "the watch could not install it"
            else -> "the watch reported an unknown result"
        }
        end(job, FAILED, "Watch face ${job.face.id} was not installed: $why (install result $code)")
        return FAILED
    }

    /** Under [lock]: switches to the face and re-reads the list, which decides how the install ends. */
    private fun activating(job: Install): String {
        job.state = ACTIVATING
        if (host.send(Proto.command(TYPE, SET, Proto.bytes(6, Proto.text(2, job.face.id))), "watch face switch")) tickets.getValue(SET).sent++
        if (host.send(Proto.command(TYPE, LIST), "watch face list read")) job.listRead = ++tickets.getValue(LIST).sent
        return ACTIVATING
    }

    /** Gives [job] in [state] its time: then an install without a result is verified anyway, and an unanswered list read ends it. */
    private fun await(job: Install, state: String) {
        val ms = when (state) {
            INSTALLING -> RESULT_MS
            ACTIVATING -> NO_REPLY_MS
            else -> return
        }
        host.after(ms) {
            val next = synchronized(lock) {
                if (install !== job || job.state != state) return@after
                when {
                    session != job.session -> end(job, FAILED, UNCONFIRMED)
                    state == INSTALLING -> return@synchronized activating(job)
                    // The watch reported success; only the list read went unanswered.
                    job.result != null -> end(job, DONE, null)
                    else -> end(job, FAILED, "The watch did not confirm that watch face ${job.face.id} is installed")
                }
                return@after
            }
            await(job, next)
        }
    }

    /** Under [lock]. */
    private fun end(job: Install, state: String, error: String?) {
        job.state = state
        job.error = error
        job.finishedAt = clock()
        host.log(if (error == null) "Watch face ${job.face.id} install: $state, verified ${job.verified}" else "Watch face ${job.face.id} install failed: $error")
    }

    /** The session a route's operation runs in. */
    private fun begin(): Long =
        synchronized(lock) {
            if (!host.connected) throw IOException("Connect the watch first")
            session
        }

    /** The face with [id] in the cached list, reading the list first when there is none. */
    private fun knownFace(id: String, s: Long): Face {
        val list = synchronized(lock) { faces } ?: readList(s)
        return list.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Watch face $id is not installed on the watch")
    }

    /** Sends 4/[sub] for [id] and waits for its reply; null when none came in time. */
    private fun write(sub: Int, id: String, what: String, s: Long): Map<Int, List<Any>>? =
        exchange(sub, s, Proto.command(TYPE, sub, Proto.bytes(6, Proto.text(2, id))), what).cmd

    /** Sends 4/0 and waits for its own list; IOException when the watch is gone, refuses or does not answer. */
    private fun readList(s: Long): List<Face> {
        val a = exchange(LIST, s, Proto.command(TYPE, LIST), "watch face list read")
        val cmd = a.cmd ?: throw IOException(
            if (a.owed > 0) "The watch has not answered an earlier watch face list read; reconnect the watch" else "The watch did not answer the watch face list read",
        )
        return parsed(cmd) ?: throw IOException("The watch refused the watch face list read")
    }

    /**
     * Sends [cmd] in session [s], under the lock so a session change cannot slip between the check
     * and the send, then waits for the reply that answers this send.
     */
    private fun exchange(sub: Int, s: Long, cmd: ByteArray, what: String): Answer {
        val t: Tickets
        val owed: Long
        synchronized(lock) {
            if (session != s) throw IOException("The watch disconnected")
            if (!host.send(cmd, what)) throw IOException("Could not send the $what to the watch")
            t = tickets.getValue(sub)
            owed = t.sent - t.received
            t.waiting = ++t.sent
            t.reply = null
        }
        val deadline = System.nanoTime() + replyTimeoutMs * 1_000_000
        synchronized(lock) {
            try {
                while (true) {
                    if (session != s) throw IOException("The watch disconnected")
                    t.reply?.let { return Answer(it, owed) }
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) return Answer(null, owed)
                    lock.wait(left)
                }
            } finally {
                t.waiting = 0
                t.reply = null
            }
        }
    }

    /** Counts a reply on [sub]; more replies than sends are the watch's own pushes and answer nothing. Under [lock]. */
    private fun received(sub: Int, cmd: Map<Int, List<Any>>) {
        val t = tickets.getValue(sub)
        if (t.received == t.sent) return
        if (++t.received == t.waiting) t.reply = cmd
        lock.notifyAll()
    }

    private fun refused(reply: Map<Int, List<Any>>?, what: String) {
        val status = reply?.let { Proto.number(it, 100, 0) } ?: 0L
        if (status != 0L) throw IOException("The watch refused to $what (status $status)")
    }

    private fun result(id: String, action: String, reply: Map<Int, List<Any>>?, verified: Boolean, sent: Boolean): JSONObject =
        listing()
            .put("id", id)
            .put("action", action)
            .put("sent", sent)
            .put("acknowledged", if (!sent) JSONObject.NULL else reply != null)
            .put("ack", reply?.let(::ack) ?: JSONObject.NULL)
            .put("verified", verified)

    private fun sendConnectRead() {
        synchronized(lock) {
            if (!connectReadPending) return
            if (!host.send(Proto.command(TYPE, LIST), "watch face list read")) return
            connectReadPending = false
            tickets.getValue(LIST).sent++
        }
    }

    private fun onList(cmd: Map<Int, List<Any>>) {
        val list = parsed(cmd)
        synchronized(lock) {
            received(LIST, cmd)
            if (list != null) {
                faces = list
                readAt = clock()
                supported = true
                listState = "ok"
                val job = install
                if (job != null && job.state == ACTIVATING) {
                    val face = list.firstOrNull { it.id == job.face.id }
                    if (face != null) {
                        job.verified = true
                        job.active = face.active
                        end(job, DONE, null)
                    } else if (tickets.getValue(LIST).received >= job.listRead) {
                        end(job, FAILED, "Watch face ${job.face.id} is not on the watch after the install")
                    }
                }
            } else if (supported != true) {
                supported = false
                listState = "rejected"
            }
        }
        if (list == null) host.log("Watch face list reply without a list (status ${Proto.number(cmd, 100, 0)})")
    }

    private fun onWriteReply(sub: Int, cmd: Map<Int, List<Any>>) {
        synchronized(lock) { received(sub, cmd) }
        val what = when (sub) {
            SET -> "switch"
            DELETE -> "delete"
            else -> "install start"
        }
        val install = if (sub == INSTALL) ", " + (prepareStatus(cmd)?.let { (field, status) -> "prepare status $status in field $field" } ?: "no prepare status") else ""
        host.log("Watch face $what reply: status ${Proto.number(cmd, 100, 0)}, ack ${ack(cmd) ?: "none"}$install")
    }

    private fun clear() {
        faces = null
        readAt = null
        for (sub in listOf(LIST, SET, DELETE, INSTALL)) tickets[sub] = Tickets()
    }

    private fun json(f: Face): JSONObject =
        JSONObject().put("id", f.id).put("name", f.name).put("active", f.active).put("can_delete", f.canDelete)

    private fun checkedId(id: String): String = id.also { require(it.matches(ID)) { "Watch face ids are 1 to 20 decimal digits" } }

    companion object {
        const val TYPE = 4
        const val LIST = 0
        const val SET = 1
        const val DELETE = 2
        const val INSTALL = 4
        const val RESULT = 5
        private const val NO_REPLY_MS = 10_000L

        /** How long an uploaded face waits for the watch's install result (4/5); AstroBox waits as long. */
        private const val RESULT_MS = 120_000L

        // InstallResult.Code (wear_watch_face.proto).
        private const val VERIFY_FAILED = 0L
        private const val INSTALL_FAILED = 1L
        private const val INSTALL_SUCCESS = 2L
        private const val INSTALL_USED = 3L

        /** Why the watch refused a 4/4, by PrepareStatus (wear_common.proto); 0 READY and 2 DUPLICATED are handled apart. */
        private val PREPARE = mapOf(
            1L to "the watch is busy",
            3L to "the watch is low on storage; delete a face first",
            4L to "the watch battery is low",
            5L to "a newer version is installed",
            6L to "the watch does not support it",
            7L to "the watch holds as many faces as it can; delete one first",
            8L to "network error",
            255L to "it failed",
        )
        private val ID = Regex("[0-9]{1,20}")

        private const val UPLOADING = "uploading"
        private const val INSTALLING = "installing"
        private const val ACTIVATING = "activating"
        private const val DONE = "done"
        private const val FAILED = "failed"
        private const val DISCONNECTED = "The watch disconnected"
        private const val UNCONFIRMED = "The watch disconnected before the install was confirmed; check the face list"

        /** Watch face files are a few hundred KB; this bounds the upload route's memory (its body cap is Http.MAX_UPLOAD_BODY). */
        const val MAX_FILE = 6_000_000

        /** RW5A preview sizes Gadgetbridge accepts outright: its face preview and its screen. */
        private val PREVIEWS = setOf(180 to 216, 320 to 385)
        private val SCREEN = 320 to 385
        private val V3 = byteArrayOf(0, 0, 6, 7, 0, 2, 0, 1)

        /**
         * The upload route's body `{"data": "<base64, optionally as a data URL>"}`. Decoded straight
         * from the bytes when the body is just that object with an unescaped string; any other JSON
         * is parsed in full first.
         */
        fun decodeFile(raw: ByteArray): ByteArray {
            dataSpan(raw)?.let { (from, to) -> return decodeBase64(raw, from, to) }
            val body = try {
                if (raw.isEmpty()) JSONObject() else JSONObject(String(raw, Charsets.UTF_8))
            } catch (e: JSONException) {
                throw IllegalArgumentException("Invalid JSON")
            }
            val text = body.opt("data") as? String ?: throw IllegalArgumentException("Send the watch face file as base64 in data")
            val b = text.toByteArray(Charsets.UTF_8)
            return decodeBase64(b, 0, b.size)
        }

        private fun decodeBase64(b: ByteArray, start: Int, end: Int): ByteArray {
            var from = start
            if (startsWith(b, from, end, DATA_URL)) {
                val marker = indexOf(b, from, end, BASE64_MARKER)
                from = if (marker < 0) end else marker + BASE64_MARKER.size
            }
            if (end - from > (MAX_FILE + 2) / 3 * 4) throw IllegalArgumentException("Watch face files are limited to $MAX_FILE bytes")
            val decoded = try {
                Base64.getDecoder().decode(ByteBuffer.wrap(b, from, end - from))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("data is not valid base64")
            }
            if (!decoded.hasRemaining()) throw IllegalArgumentException("The watch face file is empty")
            val array = decoded.array()
            return if (decoded.arrayOffset() == 0 && decoded.remaining() == array.size) array else ByteArray(decoded.remaining()).also { decoded.get(it) }
        }

        /** Where the string lies in a body that is exactly `{"data": "..."}` (any whitespace, no escapes), or null. */
        private fun dataSpan(b: ByteArray): Pair<Int, Int>? {
            var i = 0
            fun skip() {
                while (i < b.size && b[i].toInt() in JSON_SPACE) i++
            }
            fun expect(token: ByteArray): Boolean {
                skip()
                return startsWith(b, i, b.size, token).also { if (it) i += token.size }
            }
            if (!expect(OPEN) || !expect(DATA_KEY) || !expect(COLON) || !expect(QUOTE)) return null
            val from = i
            while (i < b.size && b[i] != QUOTE[0]) {
                if (b[i] == BACKSLASH[0]) return null
                i++
            }
            if (i == b.size) return null
            val to = i++
            if (!expect(CLOSE)) return null
            skip()
            return if (i == b.size) from to to else null
        }

        private fun startsWith(b: ByteArray, at: Int, end: Int, prefix: ByteArray): Boolean =
            at + prefix.size <= end && prefix.indices.all { b[at + it] == prefix[it] }

        private fun indexOf(b: ByteArray, from: Int, end: Int, needle: ByteArray): Int {
            for (i in from..end - needle.size) if (startsWith(b, i, end, needle)) return i
            return -1
        }

        private val JSON_SPACE = setOf(0x20, 0x09, 0x0a, 0x0d)
        private val OPEN = "{".toByteArray()
        private val CLOSE = "}".toByteArray()
        private val COLON = ":".toByteArray()
        private val QUOTE = "\"".toByteArray()
        private val BACKSLASH = "\\".toByteArray()
        private val DATA_KEY = "\"data\"".toByteArray()
        private val DATA_URL = "data:".toByteArray()
        private val BASE64_MARKER = ";base64,".toByteArray()

        /**
         * A v2 face's header: magic `5A A5` at 0, ASCII decimal id at 0x28, name at 0x68 (`FF FF FF FF`
         * when localised), preview offset u32 LE at 0x20 with width and height u16 LE at +4 and +6.
         * Refuses v3 faces, which carry no id, and previews of another screen shape.
         */
        fun parseFace(b: ByteArray): FaceFile {
            if (b.size >= V3.size && b.copyOf(V3.size).contentEquals(V3)) throw IllegalArgumentException("This is a v3 watch face, which cannot be installed; only v2 faces (starting 5A A5) can")
            if (b.size < 0x6c || (b[0].toInt() and 255) != 0x5a || (b[1].toInt() and 255) != 0xa5) throw IllegalArgumentException("Not a watch face file: it does not start with 5A A5")
            val id = cString(b, 0x28, 0x68)?.let { String(it, Charsets.US_ASCII) }
            if (id == null || !id.matches(ID)) throw IllegalArgumentException("The watch face file has no numeric id")
            val localised = (0x68 until 0x6c).all { (b[it].toInt() and 255) == 0xff }
            val name = if (localised) null else cString(b, 0x68, minOf(b.size, 0x68 + 64))?.let { String(it, Charsets.UTF_8).filter { c -> !c.isISOControl() }.trim() }
            val offset = u32(b, 0x20)
            val preview = if (offset > 0 && offset + 12 <= b.size) Frames.u16(b, offset.toInt() + 4) to Frames.u16(b, offset.toInt() + 6) else null
            val compatibility = when {
                preview == null -> "unknown"
                preview in PREVIEWS -> "matches"
                sameAspect(preview) -> "aspect_ratio"
                else -> throw IllegalArgumentException("The watch face preview is ${preview.first}x${preview.second}: it was made for another watch (this one takes 180x216 or 320x385)")
            }
            return FaceFile(id, name?.ifEmpty { null }, preview, compatibility)
        }

        /** Bytes from [from] up to a NUL before [end]; null when there is none. */
        private fun cString(b: ByteArray, from: Int, end: Int): ByteArray? {
            for (i in from until minOf(end, b.size)) if (b[i].toInt() == 0) return b.copyOfRange(from, i)
            return null
        }

        private fun u32(b: ByteArray, i: Int): Long = Frames.u16(b, i).toLong() or (Frames.u16(b, i + 2).toLong() shl 16)

        /** Gadgetbridge's XiaomiInstallHandler.sameAspectRatio: no larger than the screen, ratio within 2 %. */
        private fun sameAspect(p: Pair<Int, Int>): Boolean {
            if (p.first > SCREEN.first || p.second > SCREEN.second || p.second == 0) return false
            val ratio = p.first.toDouble() / p.second / (SCREEN.first.toDouble() / SCREEN.second)
            return Math.abs(ratio - 1) <= 0.02
        }

        /** The faces in an accepted 4/0 reply, or null for a refusal or a reply without a list. */
        private fun parsed(cmd: Map<Int, List<Any>>): List<Face>? {
            val payload = cmd[6]?.firstOrNull() as? ByteArray
            return if (Proto.number(cmd, 100, 0) == 0L && payload != null) parseList(Proto.decode(payload)) else null
        }

        /**
         * A 4/4 reply's PrepareStatus and the `Watchface` field that carried it: `prepareStatus` (5),
         * or `prepareReply` (9) {1 id, 2 prepareStatus}. null when neither holds one.
         */
        private fun prepareStatus(cmd: Map<Int, List<Any>>): Pair<Int, Long>? {
            val watchface = Proto.message(cmd, 6)
            (watchface[5]?.firstOrNull() as? Long)?.let { return 5 to it }
            return (Proto.message(watchface, 9)[2]?.firstOrNull() as? Long)?.let { 9 to it }
        }

        private fun ack(cmd: Map<Int, List<Any>>): Long? = (cmd[6]?.firstOrNull() as? ByteArray)?.let { Proto.decode(it)[4]?.firstOrNull() as? Long }

        /** Decodes a `Watchface` message's `watchfaceList` (1) of `WatchfaceInfo` (1, repeated). */
        fun parseList(watchface: Map<Int, List<Any>>): List<Face> {
            val list = (watchface[1]?.firstOrNull() as? ByteArray)?.let(Proto::decode) ?: return emptyList()
            return list[1].orEmpty().filterIsInstance<ByteArray>().map { b ->
                val info = Proto.decode(b)
                Face(Proto.string(info, 1), Proto.string(info, 2), Proto.number(info, 3, 0) != 0L, Proto.number(info, 4, 0) != 0L)
            }
        }
    }
}
