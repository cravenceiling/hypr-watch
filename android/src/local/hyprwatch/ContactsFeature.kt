/*  Copyright (C) 2023-2024 Andreas Shimokawa, Yoran Vulker
    Copyright (C) 2026 Gadgetbridge contributors

    Ported from Gadgetbridge's XiaomiPhonebookService.java (setContacts, handleCommand,
    handleContactRequest) and wear_contact.proto. Android code removed; command encoding kept.

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

/**
 * Favourite contacts on the watch (type 21). The watch has no known read command, so the phone's
 * list in settings is the only copy: it replaces the watch's list (21/7) on connect and after each
 * change. Also answers the watch's caller-name lookup (21/2) from that list. Names and numbers are
 * personal data: never log them.
 */
class ContactsFeature(private val clock: () -> Long = System::currentTimeMillis) : WatchFeature {
    data class Contact(val name: String, val number: String)

    override val name = "contacts"
    private lateinit var host: FeatureHost
    private val lock = Any()

    @Volatile private var syncedAt: Long? = null

    // 21/7 has no reply in Gadgetbridge; null until the watch ever answers one.
    @Volatile private var supported: Boolean? = null

    @Volatile private var unsent = false

    override fun install(host: FeatureHost) {
        this.host = host
        host.on(TYPE, SET_LIST) { cmd ->
            val status = Proto.number(cmd, 100, 0)
            supported = status == 0L
            if (status != 0L) host.log("Watch rejected the favourite contacts with status $status")
        }
        host.on(TYPE, LOOKUP) { cmd -> lookup(cmd) }
        host.onAcked { if (unsent) push() }
        host.route("GET", "/api/contacts") { body() }
        host.route("PUT", "/api/contacts") { r ->
            val list = parse(r.body)
            val sent = synchronized(lock) {
                host.settings.put(KEY, JSONObject().put("contacts", toJson(list)))
                unsent = true
                pushLocked()
            }
            body().put("sent_to_watch", sent)
        }
    }

    override fun onConnected() {
        synchronized(lock) {
            // Never configured here: leave whatever the watch has.
            if (host.settings.get(KEY) == null) return
            unsent = true
            pushLocked()
        }
    }

    override fun status(): JSONObject = JSONObject()
        .put("count", stored().size)
        .put("synced_at", syncedAt ?: JSONObject.NULL)
        .put("supported", supported ?: JSONObject.NULL)

    private fun body(): JSONObject = status()
        .put("contacts", toJson(stored()))
        .put("max", MAX_CONTACTS)
        .put("configured", host.settings.get(KEY) != null)

    private fun push(): Boolean = synchronized(lock) { pushLocked() }

    private fun pushLocked(): Boolean {
        val list = stored()
        if (!host.connected || !host.send(setList(list), "favourite contacts")) return false
        unsent = false
        syncedAt = clock()
        host.log("Sent ${list.size} favourite contacts")
        return true
    }

    private fun lookup(cmd: Map<Int, List<Any>>) {
        val number = Proto.string(Proto.message(cmd, PAYLOAD), 2)
        if (number.isEmpty()) return
        val digits = digits(number)
        val match = stored().firstOrNull { sameNumber(digits(it.number), digits) }
        host.send(lookupAnswer(match?.name ?: "", number), "caller name")
    }

    private fun stored(): List<Contact> {
        val a = host.settings.get(KEY)?.optJSONArray("contacts") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Contact(o.getString("name"), o.getString("number")) } }
    }

    companion object {
        const val TYPE = 21
        const val LOOKUP = 2
        const val LOOKUP_ANSWER = 3
        const val SET_LIST = 7
        private const val PAYLOAD = 23
        private const val KEY = "list"

        /** RedmiWatch5ActiveCoordinator.getContactsSlotCount. */
        const val MAX_CONTACTS = 10

        // The watch's limits are unknown; these keep names readable on its screen and numbers dialable.
        const val NAME_MAX = 32
        const val NUMBER_MAX = 32
        const val DIGITS_MAX = 20
        private val NUMBER = Regex("\\+?[0-9][0-9 ]*")

        /** 21/7: replaces the watch's whole list. */
        fun setList(list: List<Contact>): ByteArray {
            val entries = list.map { Proto.bytes(1, Proto.join(Proto.text(1, it.name), Proto.text(2, it.number))) }
            return Proto.command(TYPE, SET_LIST, Proto.bytes(PAYLOAD, Proto.bytes(4, Proto.join(*entries.toTypedArray()))))
        }

        /** 21/3: an empty [name] makes the watch show the number. */
        fun lookupAnswer(name: String, number: String): ByteArray =
            Proto.command(TYPE, LOOKUP_ANSWER, Proto.bytes(PAYLOAD, Proto.bytes(3, Proto.join(Proto.text(1, name), Proto.text(2, number)))))

        /** Validates PUT /api/contacts. Messages name the entry by position, never by its content. */
        fun parse(body: JSONObject): List<Contact> {
            val a = body.opt("contacts") as? JSONArray ?: throw IllegalArgumentException("Send contacts as a list of {name, number}")
            require(a.length() <= MAX_CONTACTS) { "The watch holds at most $MAX_CONTACTS favourite contacts" }
            val seen = HashSet<String>()
            return (0 until a.length()).map { i ->
                val o = a.opt(i) as? JSONObject ?: throw IllegalArgumentException("Contact ${i + 1}: send {name, number}")
                val name = (o.opt("name") as? String)?.trim()
                val number = (o.opt("number") as? String)?.trim()
                require(name != null && name.codePointCount(0, name.length) in 1..NAME_MAX && name.none { it.isISOControl() }) {
                    "Contact ${i + 1}: use a name of 1 to $NAME_MAX characters"
                }
                require(number != null && number.length <= NUMBER_MAX && NUMBER.matches(number) && digits(number).length <= DIGITS_MAX) {
                    "Contact ${i + 1}: use a number of digits, spaces and an optional leading +, at most $DIGITS_MAX digits"
                }
                require(seen.add(digits(number))) { "Contact ${i + 1}: that number is already in the list" }
                Contact(name, number)
            }
        }

        private fun toJson(list: List<Contact>): JSONArray = JSONArray().also { a -> list.forEach { a.put(JSONObject().put("name", it.name).put("number", it.number)) } }

        private fun digits(s: String): String = s.filter { it in '0'..'9' }

        // A caller may arrive with a country code or a national 0 prefix: match on the shorter number's tail.
        private fun sameNumber(a: String, b: String): Boolean {
            val x = a.trimStart('0')
            val y = b.trimStart('0')
            if (x.isEmpty() || y.isEmpty()) return false
            if (x == y) return true
            val (short, long) = if (x.length < y.length) x to y else y to x
            return short.length >= 7 && long.endsWith(short)
        }
    }
}
