/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo
    Copyright (C) 2026 Gadgetbridge contributors

    Ported from Gadgetbridge's XiaomiNotificationService.java (requestCannedMessages,
    handleCannedMessages, onSetCannedMessages) and wear_notification.proto. Android code
    removed; command encoding and decoding kept.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * The watch's quick replies for rejected calls (type 7 canned messages). Reads them (7/9) on connect
 * and after each write (7/12), which is how a write is verified. The watch's 7/9 reply sets the
 * min/max slot counts, so a write waits for a read on the same connection. Reply texts are the
 * user's: never log them. NotificationsFeature owns the other type-7 subtypes.
 */
class QuickRepliesFeature(
    private val clock: () -> Long = System::currentTimeMillis,
    private val replyTimeoutMs: Long = 10_000,
) : WatchFeature {
    override val name = "quick_replies"
    private lateinit var host: FeatureHost
    private val lock = Any()

    // Last list the watch reported; kept across disconnects so GET shows it with its read_at.
    private var replies: List<String> = emptyList()
    private var min = 0
    private var max = 0
    private var readAt: Long? = null
    private var supported: Boolean? = null

    /** min/max came from a 7/9 reply on this connection. */
    private var readThisSession = false
    private var reading: (() -> Unit)? = null

    private var written: List<String>? = null
    private var writeState: String? = null
    private var writeAt: Long? = null

    override fun install(host: FeatureHost) {
        this.host = host
        host.on(TYPE, GET) { cmd -> onList(cmd) }
        // Gadgetbridge has no 7/12 reply case; only a rejection is acted on.
        host.on(TYPE, SET) { cmd ->
            val status = Proto.number(cmd, 100, 0)
            if (status != 0L) synchronized(lock) {
                if (writeState == PENDING) finishWrite(REJECTED)
                host.log("Watch rejected the quick replies with status $status")
            }
        }
        host.route("GET", "/api/quick-replies") { synchronized(lock) { body() } }
        host.route("PUT", "/api/quick-replies") { r -> synchronized(lock) { write(r.body) } }
    }

    override fun onConnected() {
        synchronized(lock) { read() }
    }

    override fun onDisconnected() {
        synchronized(lock) {
            readThisSession = false
            reading?.invoke()
            reading = null
            if (writeState == PENDING) finishWrite(DISCONNECTED)
        }
    }

    override fun status(): JSONObject = synchronized(lock) { summary() }

    private fun summary(): JSONObject = JSONObject()
        .put("count", replies.size)
        .put("read_at", readAt ?: JSONObject.NULL)
        .put("supported", supported ?: JSONObject.NULL)
        .put("min", min)
        .put("max", max)
        .put("writable", writable())
        .put("write", writeState?.let { JSONObject().put("state", it).put("at", writeAt) } ?: JSONObject.NULL)

    private fun body(): JSONObject = summary().put("replies", JSONArray(replies)).put("reply_max_chars", REPLY_MAX)

    private fun writable(): Boolean = host.connected && readThisSession && supported == true

    private fun write(input: JSONObject): JSONObject {
        require(host.connected) { "Connect the watch first" }
        require(readThisSession) { "The watch has not reported its quick replies yet; try again in a few seconds" }
        require(supported == true) { "This watch does not accept quick replies" }
        val list = parse(input, maxCount())
        if (!host.send(setList(list, min, max), "quick replies")) throw IOException("The watch did not take the quick replies; try again")
        written = padded(list, min)
        writeState = PENDING
        writeAt = clock()
        host.log("Sent ${list.size} quick replies")
        read()
        return body()
    }

    private fun maxCount(): Int = minOf(max, CAP)

    /** Sends 7/9; a missing reply only marks the read or the pending write as unanswered. */
    private fun read() {
        if (!host.send(GET_CMD, "quick replies read")) {
            if (writeState == PENDING) finishWrite(NO_REPLY)
            return
        }
        reading?.invoke()
        reading = host.after(replyTimeoutMs) {
            synchronized(lock) {
                reading = null
                if (writeState == PENDING) finishWrite(NO_REPLY)
                host.log("Watch did not report its quick replies")
            }
        }
    }

    private fun onList(cmd: Map<Int, List<Any>>) = synchronized(lock) {
        reading?.invoke()
        reading = null
        val status = Proto.number(cmd, 100, 0)
        val n = cmd[NOTIFICATION]?.let { Proto.message(cmd, NOTIFICATION) }
        val msg = n?.get(CANNED)?.let { Proto.message(n, CANNED) }
        if (status != 0L || msg == null) {
            supported = false
            if (writeState == PENDING) finishWrite(REJECTED)
            host.log("Watch answered the quick replies read without a list (status $status)")
            return@synchronized
        }
        min = Proto.number(msg, 1, 0).toInt()
        max = Proto.number(msg, 3, 0).toInt()
        replies = (msg[2] ?: emptyList()).filterIsInstance<ByteArray>().map { String(it, Charsets.UTF_8) }
        readAt = clock()
        readThisSession = true
        supported = max > 0
        if (writeState == PENDING) finishWrite(if (replies == written) VERIFIED else MISMATCH)
        host.log("Watch reported ${replies.size} quick replies (min $min, max $max)")
    }

    private fun finishWrite(state: String) {
        writeState = state
        written = null
    }

    companion object {
        const val TYPE = 7
        const val GET = 9
        const val SET = 12
        private const val NOTIFICATION = 9
        private const val CANNED = 9

        /** Gadgetbridge's settings UI cap on slots. */
        const val CAP = 16

        // No known watch limit; the spec's guess, verified by reading the list back.
        const val REPLY_MAX = 30

        const val PENDING = "pending"
        const val VERIFIED = "verified"
        const val MISMATCH = "mismatch"
        const val NO_REPLY = "no_reply"
        const val REJECTED = "rejected"
        const val DISCONNECTED = "disconnected"

        /** 7/9 with Notification.unknown8 = 1, as Gadgetbridge sends it. */
        val GET_CMD: ByteArray = Proto.command(TYPE, GET, Proto.bytes(NOTIFICATION, Proto.num(8, 1)))

        /** 7/12: min and max as the watch reported them, then the replies padded with "-" up to min. */
        fun setList(list: List<String>, min: Int, max: Int): ByteArray {
            val msg = Proto.join(Proto.num(1, min.toLong()), *padded(list, min).map { Proto.text(2, it) }.toTypedArray(), Proto.num(3, max.toLong()))
            return Proto.command(TYPE, SET, Proto.bytes(NOTIFICATION, Proto.bytes(CANNED, msg)))
        }

        fun padded(list: List<String>, min: Int): List<String> = list + List(maxOf(0, min - list.size)) { "-" }

        /** Validates PUT /api/quick-replies against [maxCount] slots. */
        fun parse(body: JSONObject, maxCount: Int): List<String> {
            val a = body.opt("replies") as? JSONArray ?: throw IllegalArgumentException("Send replies as a list of strings")
            require(a.length() <= maxCount) { "The watch holds at most $maxCount quick replies" }
            return (0 until a.length()).map { i ->
                val s = (a.opt(i) as? String)?.trim()
                require(s != null && s.codePointCount(0, s.length) in 1..REPLY_MAX && s.none { it.isISOControl() }) {
                    "Reply ${i + 1}: use 1 to $REPLY_MAX characters on one line"
                }
                s
            }
        }
    }
}
