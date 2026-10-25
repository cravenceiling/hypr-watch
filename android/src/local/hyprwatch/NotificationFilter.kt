package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject

/** What the filter needs to know about a posted notification beyond the forwarded model. */
data class NotificationFlags(
    val ongoing: Boolean = false,
    val foregroundService: Boolean = false,
    val groupSummary: Boolean = false,
    val localOnly: Boolean = false,
    val alertOnce: Boolean = false,
)

/**
 * Which posted notifications reach the watch. Nothing is forwarded until a package is allowed
 * (or [calls] is enabled). Removals are not filtered: the codec only deletes what it sent.
 */
class NotificationFilter(
    private val ownPackage: String,
    val allowed: Set<String> = emptySet(),
    val calls: Boolean = false,
) {
    private val lastText = LinkedHashMap<String, Pair<Int, Long>>(16, 0.75f, true)

    /** The settings check, cheap enough to run before the notification is read. */
    fun wants(pkg: String, isCall: Boolean): Boolean = pkg != ownPackage && if (isCall) calls else pkg in allowed

    @Synchronized
    fun accept(n: PhoneNotification, flags: NotificationFlags, now: Long): Boolean {
        if (!wants(n.pkg, n.isCall) || flags.localOnly) return false
        // Incoming-call notifications are ongoing foreground-service notifications by design.
        if (n.isCall) return true
        if (flags.ongoing || flags.foregroundService || flags.groupSummary) return false
        if (n.title.isBlank() && n.body.isBlank()) return false
        val text = (n.title + "\u0000" + n.body).hashCode()
        val last = lastText[n.key]
        lastText[n.key] = text to now
        if (lastText.size > RECENT_MAX) lastText.remove(lastText.keys.first())
        if (last == null || last.first != text) return true
        // An alert-once update with unchanged text only moved something else, such as progress.
        return !flags.alertOnce && now - last.second >= DUPLICATE_WINDOW_MS
    }

    fun toJson(): JSONObject = JSONObject().put("allowed", JSONArray(allowed.sorted())).put("calls", calls)

    /** [toJson] plus `allowed_apps`, each allowed package with the name [label] gives it. */
    fun toApi(label: (String) -> String): JSONObject =
        toJson().put("allowed_apps", JSONArray(allowed.sorted().map { JSONObject().put("package", it).put("app_name", label(it)) }))

    companion object {
        const val DUPLICATE_WINDOW_MS = 5_000L
        const val MAX_ALLOWED = 500
        private const val RECENT_MAX = 256
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        /** Unknown or malformed input yields the default (everything off). */
        fun fromJson(ownPackage: String, json: String?): NotificationFilter {
            val o = try {
                if (json.isNullOrBlank()) JSONObject() else JSONObject(json)
            } catch (e: Exception) {
                JSONObject()
            }
            val list = o.optJSONArray("allowed") ?: JSONArray()
            val pkgs = (0 until list.length()).mapNotNull { list.optString(it).ifBlank { null } }.toSet()
            return NotificationFilter(ownPackage, pkgs, o.optBoolean("calls", false))
        }

        /** Validates a PUT /api/notifications body: exactly `calls` (boolean) and `allowed` (package names). */
        fun fromApi(ownPackage: String, input: JSONObject): NotificationFilter {
            for (k in input.keys()) {
                require(k == "calls" || k == "allowed") { "Unknown notification setting $k" }
            }
            val calls = input.opt("calls")
            require(calls is Boolean) { "calls must be true or false" }
            val list = input.opt("allowed")
            require(list is JSONArray && list.length() <= MAX_ALLOWED) { "allowed must be a list of at most $MAX_ALLOWED package names" }
            val pkgs = (0 until list.length()).map { i ->
                val p = list.get(i)
                require(p is String && isPackage(p)) { "allowed[$i] is not a package name" }
                p
            }
            return NotificationFilter(ownPackage, pkgs.toSet(), calls)
        }

        /** What [fromApi] accepts in `allowed`. */
        fun isPackage(p: String): Boolean = p.length <= 255 && PACKAGE.matches(p)
    }
}

/**
 * Packages that posted notifications lately, so the user can pick what to allow; only names
 * [NotificationFilter.fromApi] accepts. Never their content.
 */
class RecentApps<T>(private val capacity: Int = 50) {
    class Entry<T>(val pkg: String, val lastSeen: Long, val info: T)

    private val entries = LinkedHashMap<String, Entry<T>>()

    @Synchronized
    fun add(pkg: String, at: Long, info: T) {
        if (!NotificationFilter.isPackage(pkg)) return
        entries.remove(pkg)
        entries[pkg] = Entry(pkg, at, info)
        if (entries.size > capacity) entries.remove(entries.keys.first())
    }

    /** Newest first. */
    @Synchronized
    fun list(): List<Entry<T>> = entries.values.toList().asReversed()
}
