package local.hyprwatch

import org.json.JSONObject
import java.io.IOException

/**
 * Phone notifications and calls on the watch (type 7), the watch's actions on them, the
 * /api/notifications routes, and the apps' icons, which the watch asks for when it lacks one.
 * Titles and bodies go only to the watch.
 *
 * Icons (with [uploads]), ported from Gadgetbridge's XiaomiNotificationService
 * handleNotificationIconQuery and handleNotificationIconRequest (AGPL-3.0-or-later): the watch
 * queries a package name, possibly truncated (7/16); for an app whose notification this side
 * forwarded lately the phone answers with the watch's own name (7/15 reply); the watch then asks
 * for a size and pixel format (7/15 request), and the icon goes up as a [DataUpload] of type 50.
 * Requests carry no package, so they pair with the answered queries in order.
 */
class NotificationsFeature(
    private val listener: ListenerSettings,
    private val notifications: WatchNotifications = WatchNotifications(),
    private val uploads: DataUpload? = null,
) : WatchFeature {
    /** The notification listener's Android side. */
    interface ListenerSettings {
        /** GET /api/notifications. */
        fun get(): JSONObject

        /** Validates and saves [input] as the filter; returns [get]. */
        fun put(input: JSONObject): JSONObject

        /** Asks Android to open the notification access screen; false when it has none. */
        fun openAccess(): Boolean
    }

    override val name = "notifications"
    private lateinit var host: FeatureHost

    private class Icon(val pkg: String, val bytes: ByteArray)

    private val iconLock = Any()

    // Guarded by [iconLock].
    private val recentPackages = LinkedHashSet<String>()
    private val answered = ArrayDeque<String>()
    private val waiting = ArrayDeque<Icon>()
    private var session = 0L

    override fun install(host: FeatureHost) {
        this.host = host
        for (sub in WatchNotifications.WATCH_SUBTYPES) {
            host.on(WatchNotifications.TYPE, sub) { cmd -> notifications.decode(sub, cmd)?.let(NotificationBridge::act) }
        }
        if (uploads != null) {
            host.on(WatchNotifications.TYPE, ICON_QUERY) { cmd -> onIconQuery(cmd) }
            host.on(WatchNotifications.TYPE, ICON) { cmd -> onIconRequest(cmd) }
            uploads.onIdle { nextIcon() }
        }
        // A removal refused by a full window goes out as soon as there is room.
        host.onAcked { notifications.flush(::send) }
        host.route("GET", "/api/notifications") { listener.get() }
        host.route("PUT", "/api/notifications") { r -> listener.put(r.body) }
        host.route("POST", "/api/notifications/access") { JSONObject().put("requested", listener.openAccess()) }
        NotificationBridge.sink = ::forward
    }

    override fun onConnected() {
        newIconSession()
        notifications.flush(::send)
        NotificationBridge.watchConnected()
    }

    override fun onDisconnected() {
        newIconSession()
        notifications.disconnected()
    }

    override fun close() {
        NotificationBridge.sink = null
    }

    /** The bridge sink, on the listener's thread. */
    fun forward(e: NotificationEvent) {
        try {
            if (e is NotificationEvent.Posted && !e.notification.isCall) remember(e.notification.pkg)
            notifications.forward(e, ::send)
        } catch (x: Exception) {
            host.log("notification failed: ${x.message ?: x.javaClass.simpleName}")
        }
    }

    private fun send(cmd: ByteArray): Boolean = host.send(cmd, "notification")

    private fun remember(pkg: String) {
        synchronized(iconLock) {
            recentPackages.remove(pkg)
            recentPackages.add(pkg)
            if (recentPackages.size > RECENT_PACKAGES) recentPackages.remove(recentPackages.first())
        }
    }

    private fun newIconSession() {
        synchronized(iconLock) {
            session++
            answered.clear()
            waiting.clear()
        }
    }

    /** 7/16 on the reader thread: answers only for an app this side forwarded lately, so it never waits on Android. */
    private fun onIconQuery(cmd: Map<Int, List<Any>>) {
        val query = Proto.string(Proto.message(Proto.message(cmd, 9), 16), 1)
        synchronized(iconLock) {
            val pkg = if (query in recentPackages) query else recentPackages.lastOrNull { query.isNotEmpty() && it.startsWith(query) }
            if (pkg == null) {
                host.log("Notification icon query for '$query': no app forwarded lately matches")
                return
            }
            val reply = Proto.command(WatchNotifications.TYPE, ICON, Proto.bytes(9, Proto.bytes(14, Proto.text(1, query))))
            if (!host.send(reply, "notification icon reply")) return
            answered.addLast(pkg)
            if (answered.size > MAX_ANSWERED) answered.removeFirst()
            host.log("Notification icon query for '$query': offered $pkg")
        }
    }

    /** 7/15 from the watch: the size and pixel format for the oldest answered query. Renders on the commands thread. */
    private fun onIconRequest(cmd: Map<Int, List<Any>>) {
        val request = Proto.message(Proto.message(cmd, 9), 15)
        val status = Proto.number(request, 1, 0)
        val format = Proto.number(request, 2, -1)
        val size = Proto.number(request, 3, 0)
        val (pkg, s) = synchronized(iconLock) { (answered.removeFirstOrNull() ?: return host.log("Notification icon request without a query")) to session }
        when {
            status != 0L -> host.log("The watch declined the icon for $pkg (status $status)")
            format !in IconPixels.FORMATS.map { it.toLong() } -> host.log("The watch asked for the icon of $pkg in unknown pixel format $format")
            size < 1 || size > IconPixels.MAX_SIZE -> host.log("The watch asked for the icon of $pkg at unsupported size $size")
            else -> host.submit { render(pkg, size.toInt(), format.toInt(), s) }
        }
    }

    private fun render(pkg: String, size: Int, format: Int, s: Long) {
        val pixels = host.phone.appIcon(pkg, size) ?: return host.log("No icon for $pkg")
        val icon = Icon(pkg, IconPixels.convert(pixels, format))
        synchronized(iconLock) {
            if (session != s) return
            waiting.addLast(icon)
            if (waiting.size > MAX_WAITING) waiting.removeFirst()
        }
        host.log("Notification icon for $pkg: ${size}x$size in pixel format $format, ${icon.bytes.size} bytes")
        nextIcon()
    }

    /**
     * Starts the oldest waiting icon when the uploader is free; the uploader calls again whenever it
     * frees up. An icon leaves the queue only under a reservation, so it is either started or, when
     * the session ended meanwhile, dropped: never out of the queue with no wakeup to come. Never blocks.
     */
    private fun nextIcon() {
        val up = uploads ?: return
        if (!host.connected || synchronized(iconLock) { waiting.isEmpty() }) return
        val r = up.reserve() ?: return
        // Only a reservation holder takes icons, so the queue is empty here only after a new session cleared it.
        val icon = synchronized(iconLock) { waiting.removeFirstOrNull() } ?: return up.release(r)
        try {
            up.start(DataUpload.TYPE_NOTIFICATION_ICON, icon.bytes, r) { error ->
                host.log(if (error == null) "Uploaded the icon for ${icon.pkg}" else "Icon for ${icon.pkg} not uploaded: $error")
            }
        } catch (e: IOException) {
            up.release(r)
            host.log("Icon for ${icon.pkg} not uploaded: ${e.message}")
        }
    }

    companion object {
        /** 7/15: the phone's icon reply and the watch's icon request share the subtype. */
        const val ICON = 15
        const val ICON_QUERY = 16

        /** Gadgetbridge keeps the last 32 packages for truncated names. */
        private const val RECENT_PACKAGES = 32
        private const val MAX_ANSWERED = 8
        private const val MAX_WAITING = 4
    }
}

/**
 * ARGB pixels to the watch's icon pixel formats (Gadgetbridge's XiaomiBitmapUtils numbering,
 * AGPL-3.0-or-later). Formats without alpha get the icon over black, the watch's background.
 * Gadgetbridge's RGB565 takes blue from the wrong bits and writes its big-endian variant little
 * endian; this follows the format names instead.
 */
object IconPixels {
    const val RGB565_LE = 0
    const val RGB565_BE = 1
    const val XRGB8888_LE = 2
    const val ARGB8888_LE = 3
    const val ARGB8565_LE = 7
    const val ABGR8565_LE = 8
    val FORMATS = setOf(RGB565_LE, RGB565_BE, XRGB8888_LE, ARGB8888_LE, ARGB8565_LE, ABGR8565_LE)

    /** Larger than any icon the watch should ask for, and small enough for one short upload. */
    const val MAX_SIZE = 256

    /** [argb] (row by row, unpremultiplied, as Android's Bitmap.getPixels gives them) in [format]. */
    fun convert(argb: IntArray, format: Int): ByteArray {
        require(format in FORMATS) { "Unknown pixel format $format" }
        val bpp = when (format) {
            RGB565_LE, RGB565_BE -> 2
            ARGB8565_LE, ABGR8565_LE -> 3
            else -> 4
        }
        val out = ByteArray(argb.size * bpp)
        for ((i, p) in argb.withIndex()) {
            val a = (p ushr 24) and 255
            var r = (p ushr 16) and 255
            var g = (p ushr 8) and 255
            var b = p and 255
            if (format == RGB565_LE || format == RGB565_BE || format == XRGB8888_LE) {
                r = over(r, a)
                g = over(g, a)
                b = over(b, a)
            }
            val o = i * bpp
            when (format) {
                RGB565_LE, RGB565_BE -> {
                    val v = rgb565(r, g, b)
                    val big = format == RGB565_BE
                    out[o] = (if (big) v ushr 8 else v).toByte()
                    out[o + 1] = (if (big) v else v ushr 8).toByte()
                }
                ARGB8565_LE, ABGR8565_LE -> {
                    val v = if (format == ARGB8565_LE) rgb565(r, g, b) else rgb565(b, g, r)
                    out[o] = v.toByte()
                    out[o + 1] = (v ushr 8).toByte()
                    out[o + 2] = a.toByte()
                }
                else -> {
                    out[o] = b.toByte()
                    out[o + 1] = g.toByte()
                    out[o + 2] = r.toByte()
                    out[o + 3] = (if (format == XRGB8888_LE) 255 else a).toByte()
                }
            }
        }
        return out
    }

    private fun rgb565(r: Int, g: Int, b: Int): Int = ((r ushr 3) shl 11) or ((g ushr 2) shl 5) or (b ushr 3)

    /** [c] at alpha [a] over black, rounded. */
    private fun over(c: Int, a: Int): Int = (c * a + 127) / 255
}
