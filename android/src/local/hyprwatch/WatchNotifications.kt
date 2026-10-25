/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo
    Copyright (C) 2026 Gadgetbridge contributors

    Ported from Gadgetbridge's XiaomiNotificationService.java (onNotification,
    onDeleteNotification, onSetCallState, handleCommand) and wear_notification.proto.
    Android code removed; command encoding and decoding kept.

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

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A phone notification as forwarded to the watch. [key] and [postTime] come from the Android StatusBarNotification. */
data class PhoneNotification(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val body: String,
    val whenMs: Long,
    val isCall: Boolean = false,
    val postTime: Long = 0,
)

/** One posting of a call notification; an update reposts it with a new post time. */
data class CallRef(val key: String, val postTime: Long)

/** Phone-side change, produced by the notification listener. */
sealed interface NotificationEvent {
    data class Posted(val notification: PhoneNotification) : NotificationEvent

    /** Also sent for a call notification that turned into an answered call, to stop the ringing UI. */
    data class Removed(val key: String) : NotificationEvent
}

/** Watch-initiated action, already resolved from watch ids to Android keys. */
sealed interface WatchAction {
    data class Dismiss(val keys: List<String>) : WatchAction

    data class Open(val key: String) : WatchAction

    /** [call] is the ringing call last sent, or null if the phone sent none. */
    data class RejectCall(val call: CallRef?) : WatchAction

    /** Muting the ringer needs the dialer role, which this app does not hold. */
    object IgnoreCall : WatchAction
}

/** Narrow seam between the listener service and the watch connection. Either side may be absent. */
object NotificationBridge {
    /** Set by the watch side; the listener calls it for every accepted event. */
    @Volatile var sink: ((NotificationEvent) -> Unit)? = null

    /** Set by the listener while it is bound; the watch side calls it for decoded actions. */
    @Volatile var actions: ((WatchAction) -> Unit)? = null

    /** Set by the listener while it is bound: posts the current ringing call again. */
    @Volatile var repost: (() -> Unit)? = null

    fun post(e: NotificationEvent) {
        sink?.invoke(e)
    }

    fun act(a: WatchAction) {
        actions?.invoke(a)
    }

    /** After the watch reconnects, so a call that rang meanwhile reaches it. */
    fun watchConnected() {
        repost?.invoke()
    }
}

/**
 * Android key <-> watch uint32 id. Id 0 is never handed out because the watch uses it for the
 * call. Least recently used entries are evicted beyond [capacity].
 */
class NotificationIds(private val capacity: Int = 128, start: Int = 1) {
    class Entry(val id: Int, val key: String, val pkg: String)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val ids = HashMap<Int, Entry>()
    private var next = start

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun idFor(key: String, pkg: String): Int {
        entries[key]?.let { return it.id }
        val e = Entry(free(), key, pkg)
        next++
        entries[key] = e
        ids[e.id] = e
        if (entries.size > capacity) forget(entries.keys.first())
        return e.id
    }

    /** The id [idFor] would return for [key], without allocating it. */
    @Synchronized
    fun peek(key: String): Int = entries[key]?.id ?: free()

    private fun free(): Int {
        while (next == 0 || ids.containsKey(next)) next++
        return next
    }

    /** Lookup by watch id; counts as a use for eviction. */
    @Synchronized
    fun byId(id: Int): Entry? = ids[id]?.also { entries[it.key] }

    @Synchronized
    fun byKey(key: String): Entry? = entries[key]

    @Synchronized
    fun forget(key: String): Entry? = entries.remove(key)?.also { ids.remove(it.id) }

    @Synchronized
    fun size(): Int = entries.size
}

/**
 * Type 7 command codec plus the state the protocol needs (ids, the ringing call, unsent removals).
 * [forward] hands complete commands to a send function and changes state only once one is sent.
 */
class WatchNotifications(
    private val ids: NotificationIds = NotificationIds(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private var activeCall: CallRef? = null

    /** A ringing call the link has not taken yet; it follows the owed removals. */
    private var owedCall: PhoneNotification? = null

    /** Keys removed on the phone whose delete the watch has not taken yet; ids map them to the watch copy. */
    private val unsent = LinkedHashSet<String>()

    /** Sends what [e] needs via [out], which returns false when nothing was sent. */
    @Synchronized
    fun forward(e: NotificationEvent, out: (ByteArray) -> Boolean) {
        when (e) {
            is NotificationEvent.Posted -> {
                val n = e.notification
                if (activeCall?.key != n.key) {
                    unsent.remove(n.key)
                } else if (n.key !in unsent) {
                    activeCall = CallRef(n.key, n.postTime)
                    return
                }
                // Otherwise a new call reuses the key of one whose end is still owed: the end goes first.
                // A call waits in owedCall until the link takes it, so a refusal never loses it.
                if (n.isCall) owedCall = n
            }
            is NotificationEvent.Removed -> {
                if (owedCall?.key == e.key) owedCall = null
                if (activeCall?.key != e.key && ids.byKey(e.key) == null) return
                unsent.add(e.key)
                if (unsent.size > MAX_UNSENT) unsent.remove(unsent.first())
            }
        }
        if (!flush(out) || e !is NotificationEvent.Posted) return
        val n = e.notification
        if (n.isCall) return
        val cmd = send(n, ids.peek(n.key), zone())
        if (cmd != null && out(cmd)) ids.idFor(n.key, n.pkg)
    }

    /**
     * Sends the removals still owed to the watch, oldest first, then the owed call; false once one
     * is refused. A key leaves [unsent] when the link takes its frame, not when the watch acks it,
     * so a disconnect before that ack loses the removal.
     */
    @Synchronized
    fun flush(out: (ByteArray) -> Boolean): Boolean {
        val keys = unsent.iterator()
        while (keys.hasNext()) {
            val key = keys.next()
            if (activeCall?.key == key) {
                if (!out(endCall())) return false
                activeCall = null
            } else {
                val cmd = ids.byKey(key)?.let { delete(it.id, it.pkg, it.key) }
                if (cmd != null && !out(cmd)) return false
                ids.forget(key)
            }
            keys.remove()
        }
        val call = owedCall ?: return true
        if (!out(incomingCall(call.title, call.body, call.whenMs, zone()))) return false
        activeCall = CallRef(call.key, call.postTime)
        owedCall = null
        return true
    }

    /** The watch link dropped, taking its call screen with it; ids and owed deletes stay, as the watch keeps its notifications. */
    @Synchronized
    fun disconnected() {
        activeCall?.let { unsent.remove(it.key) }
        activeCall = null
        owedCall = null
    }

    /** Decodes a watch-initiated 7/[sub] command; null for subtypes and ids this side does not handle. */
    @Synchronized
    fun decode(sub: Int, cmd: Map<Int, List<Any>>): WatchAction? {
        val n = Proto.message(cmd, 9)
        return when (sub) {
            DISMISS -> {
                val dismiss = Proto.message(n, 4)
                // Forgotten here so the phone-side removal that follows does not echo a delete back.
                val keys = (dismiss[1] ?: emptyList()).mapNotNull { v ->
                    val id = Proto.number(Proto.decode(v as? ByteArray ?: ByteArray(0)), 1, -1)
                    if (id < 0) null else ids.byId(id.toInt())?.key?.also { ids.forget(it) }
                }
                if (keys.isEmpty()) null else WatchAction.Dismiss(keys)
            }
            CALL_REJECT -> WatchAction.RejectCall(activeCall).also { activeCall = null }
            CALL_IGNORE -> WatchAction.IgnoreCall
            OPEN_ON_PHONE -> {
                val id = Proto.number(Proto.message(n, 2), 1, -1)
                if (id < 0) null else ids.byId(id.toInt())?.let { WatchAction.Open(it.key) }
            }
            else -> null
        }
    }

    companion object {
        const val TYPE = 7
        const val SEND = 0
        const val DISMISS = 1
        const val CALL_REJECT = 2
        const val CALL_IGNORE = 5
        const val OPEN_ON_PHONE = 8

        /** Subtypes to register with SppLink.on(TYPE, sub). */
        val WATCH_SUBTYPES = intArrayOf(DISMISS, CALL_REJECT, CALL_IGNORE, OPEN_ON_PHONE)

        // Gadgetbridge sends Xiaomi notifications untruncated. These bounds keep one notification
        // well inside a single SPP frame: limits count code points, up to 4 UTF-8 bytes each, so
        // the body is cut further to keep the whole command within MAX_COMMAND_BYTES.
        const val APP_NAME_MAX = 32
        const val TITLE_MAX = 64
        const val BODY_MAX = 512
        const val KEY_MAX_BYTES = 256
        const val MAX_COMMAND_BYTES = 1024
        private const val MAX_UNSENT = 128

        private const val CALL_PACKAGE = "phone"
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)

        // Same set as Gadgetbridge's NotificationListener.sanitizeUnicode: control, format,
        // private-use and unpaired surrogate code points, but not whitespace such as newlines.
        private val CONTROL = Regex("[\\p{C}&&\\S]")

        fun clean(s: String, max: Int): String {
            val t = CONTROL.replace(s, "").trim()
            if (t.codePointCount(0, t.length) <= max) return t
            return t.substring(0, t.offsetByCodePoints(0, max - 1)).trimEnd() + "…"
        }

        /** [s] cut at a code point boundary, with an ellipsis, so its UTF-8 form fits [maxBytes]. */
        fun fit(s: String, maxBytes: Int): String {
            if (utf8(s) <= maxBytes) return s
            if (maxBytes < 3) return ""
            var end = s.length
            while (end > 0 && utf8(s.substring(0, end)) + 3 > maxBytes) end = s.offsetByCodePoints(end, -1)
            return s.substring(0, end).trimEnd() + "…"
        }

        private fun utf8(s: String): Int = s.toByteArray(Charsets.UTF_8).size

        fun timestamp(ms: Long, zone: ZoneId): String = TIMESTAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

        /**
         * 7/0 for an app notification, or null if even an empty body would not fit
         * [MAX_COMMAND_BYTES]. The watch drops notifications without a body or app name.
         */
        fun send(n: PhoneNotification, id: Int, zone: ZoneId): ByteArray? {
            require(id != 0) { "Notification id 0 is reserved for the call" }
            val appName = clean(n.appName, APP_NAME_MAX).ifEmpty { "UNKNOWN" }
            val title = clean(n.title, TITLE_MAX).ifEmpty { appName }
            val key = key(12, n.key)
            fun build(body: String) = notification3(
                Proto.text(1, n.pkg),
                Proto.text(2, appName),
                Proto.text(3, title),
                Proto.text(4, ""),
                Proto.text(5, body),
                Proto.text(6, timestamp(n.whenMs, zone)),
                Proto.u32(7, id),
                // Key plus openOnPhone=true makes the watch offer "Open on phone".
                key,
                Proto.num(13, 1),
            )
            val body = clean(n.body, BODY_MAX)
            val full = build(body)
            if (full.size <= MAX_COMMAND_BYTES) return full
            // The body's length and those of its three enclosing messages may each need one more byte.
            val budget = MAX_COMMAND_BYTES - build("").size - 4
            return if (budget < 0) null else build(fit(body, budget))
        }

        /** 7/0 with id 0 and isCall; replies are never allowed because the app cannot send SMS. */
        fun incomingCall(name: String, number: String, whenMs: Long, zone: ZoneId): ByteArray =
            notification3(
                Proto.text(1, CALL_PACKAGE),
                Proto.text(2, CALL_PACKAGE),
                Proto.text(3, clean(name, TITLE_MAX).ifEmpty { "?" }),
                Proto.text(4, ""),
                Proto.text(5, clean(number, TITLE_MAX).ifEmpty { "?" }),
                Proto.text(6, timestamp(whenMs, zone)),
                Proto.u32(7, 0),
                Proto.num(8, 1),
                Proto.num(11, 0),
            )

        /** 7/1 phone-side removal of one notification, or null if it would not fit [MAX_COMMAND_BYTES]. */
        fun delete(id: Int, pkg: String, key: String): ByteArray? =
            dismiss(Proto.join(Proto.u32(1, id), Proto.text(2, pkg), key(4, key))).takeIf { it.size <= MAX_COMMAND_BYTES }

        // The key holds the app's own tag, which has no length limit; the watch needs only the id.
        private fun key(field: Int, key: String): ByteArray = if (utf8(key) <= KEY_MAX_BYTES) Proto.text(field, key) else ByteArray(0)

        /** 7/1 for id 0 / "phone": the call was answered or ended. */
        fun endCall(): ByteArray = dismiss(Proto.join(Proto.u32(1, 0), Proto.text(2, CALL_PACKAGE)))

        private fun notification3(vararg fields: ByteArray): ByteArray =
            Proto.command(TYPE, SEND, Proto.bytes(9, Proto.bytes(3, Proto.bytes(1, Proto.join(*fields)))))

        private fun dismiss(notificationId: ByteArray): ByteArray =
            Proto.command(TYPE, DISMISS, Proto.bytes(9, Proto.bytes(4, Proto.bytes(1, notificationId))))
    }
}
