/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker (Gadgetbridge XiaomiMusicService)

    Ported from Gadgetbridge, which is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option) any later version. */
package local.hyprwatch

import org.json.JSONObject

/**
 * Phone media on the watch (type 18): pushes now playing (18/1) when the watch asks (18/0), on
 * connect and on media changes, and turns the watch's media keys (18/2) into [Phone.media].
 * Track text goes only to the watch and to GET /api/music; never to logs, settings or status.
 */
class MusicFeature(private val clock: () -> Long = System::currentTimeMillis) : WatchFeature {
    override val name = "music"
    private lateinit var host: FeatureHost
    private val lock = Any()

    // Guarded by lock. [listening]: registered while notification access was granted.
    private var cancelListener: (() -> Unit)? = null
    private var listening = false
    private var pending: (() -> Unit)? = null

    @Volatile private var watchAsked = false

    @Volatile private var enabled = true

    @Volatile private var lastPush: Long? = null

    @Volatile private var pushed: Pushed? = null

    /** The last push: its info without position, and where the track stood then. */
    private class Pushed(val shape: Int, val positionMs: Long?, val playing: Boolean, val at: Long)

    override fun install(host: FeatureHost) {
        this.host = host
        enabled = host.settings.get(SETTINGS)?.optBoolean("enabled", true) ?: true
        host.on(TYPE, SUB_GET) {
            watchAsked = true
            push(force = true)
        }
        host.on(TYPE, SUB_KEY) { cmd -> key(cmd) }
        host.route("GET", "/api/music") { view() }
        host.route("PUT", "/api/music/settings") { r ->
            val on = parseSettings(r.body)
            host.settings.put(SETTINGS, JSONObject().put("enabled", on))
            enabled = on
            if (host.connected) push(force = true)
            view()
        }
        listen()
    }

    override fun onConnected() {
        listen()
        pushed = null
        push(force = true)
    }

    override fun onDisconnected() {
        synchronized(lock) {
            pending?.invoke()
            pending = null
        }
        pushed = null
    }

    override fun close() {
        synchronized(lock) {
            cancelListener?.invoke()
            cancelListener = null
            pending?.invoke()
            pending = null
        }
    }

    override fun status(): JSONObject =
        JSONObject()
            .put("enabled", enabled)
            .put("access", access())
            .put("supported", if (watchAsked) "yes" else "unknown")
            .put("last_push", lastPush ?: JSONObject.NULL)

    private fun access(): String = if (host.phone.mediaAccess()) "granted" else "denied"

    /** Registers the media listener again once access is granted: one registered without access never fires. */
    private fun listen() {
        val access = host.phone.mediaAccess()
        synchronized(lock) {
            if (access && !listening) {
                cancelListener?.invoke()
                cancelListener = host.phone.onMediaChanged { schedule() }
            }
            listening = access
        }
    }

    /** Debounces bursts of media callbacks (track change = metadata + state) into one push. */
    private fun schedule() {
        if (!host.connected || !enabled) return
        synchronized(lock) {
            pending?.invoke()
            pending = host.after(DEBOUNCE_MS) {
                synchronized(lock) { pending = null }
                push(force = false)
            }
        }
    }

    /**
     * Sends 18/1 when connected, unless only the position moved as playback would move it.
     * Disabled sends "nothing playing" so the watch drops a stale track.
     */
    private fun push(force: Boolean): Boolean {
        if (!host.connected) return false
        val np = if (enabled) host.phone.nowPlaying() else null
        val volume = if (enabled) host.phone.musicVolume() else null
        val now = clock()
        val shape = musicInfo(np?.copy(positionMs = 0), volume).contentHashCode()
        val last = pushed
        if (!force && last != null && shape == last.shape && !seeked(last, np, now)) return false
        if (!host.send(Proto.command(TYPE, SUB_NOW_PLAYING, Proto.bytes(FIELD_MUSIC, Proto.bytes(1, musicInfo(np, volume)))), "now playing")) return false
        pushed = Pushed(shape, np?.positionMs, np?.playing == true, now)
        lastPush = now
        return true
    }

    /** The position is further than [SEEK_MS] from where playback since [last] would have taken it. */
    private fun seeked(last: Pushed, np: NowPlaying?, now: Long): Boolean {
        val from = last.positionMs ?: return false
        val position = np?.positionMs ?: return false
        val expected = from + if (last.playing) now - last.at else 0
        return Math.abs(position - expected) > SEEK_MS
    }

    private fun key(cmd: Map<Int, List<Any>>) {
        watchAsked = true
        val mediaKey = Proto.message(Proto.message(cmd, FIELD_MUSIC), 2)
        val k = Proto.number(mediaKey, 1, -1)
        if (!enabled) {
            host.log("Music is off; ignored media key $k")
            return
        }
        val action = when (k) {
            KEY_PLAY -> MediaAction.PLAY
            KEY_PAUSE -> MediaAction.PAUSE
            KEY_PREVIOUS -> MediaAction.PREVIOUS
            KEY_NEXT -> MediaAction.NEXT
            // The watch sends 100 for up and 0 for down; GB compares with the phone's level.
            KEY_VOLUME -> if (Proto.number(mediaKey, 2, -1) > host.phone.musicVolume()) MediaAction.VOLUME_UP else MediaAction.VOLUME_DOWN
            else -> {
                host.log("Unknown media key $k")
                return
            }
        }
        if (!host.phone.media(action)) host.log("No player took media key $k")
    }

    private fun view(): JSONObject {
        listen()
        val now = host.phone.nowPlaying()?.let {
            JSONObject()
                .put("title", it.title)
                .put("artist", it.artist)
                .put("album", it.album)
                .put("state", if (it.playing) "playing" else "paused")
                .put("position_ms", it.positionMs.coerceAtLeast(0))
                .put("duration_ms", it.durationMs.coerceAtLeast(0))
                .put("volume", host.phone.musicVolume().coerceIn(0, 100))
        }
        return JSONObject()
            .put("enabled", enabled)
            .put("access", access())
            .put("supported", if (watchAsked) "yes" else "unknown")
            .put("connected", host.connected)
            .put("last_push", lastPush ?: JSONObject.NULL)
            .put("now_playing", now ?: JSONObject.NULL)
    }

    companion object {
        const val TYPE = 18
        const val SUB_GET = 0
        const val SUB_NOW_PLAYING = 1
        const val SUB_KEY = 2
        const val DEBOUNCE_MS = 500L

        /** More than playback drift: a seek. */
        const val SEEK_MS = 3_000L
        private const val FIELD_MUSIC = 20
        private const val SETTINGS = "settings"
        private const val TEXT_LIMIT = 64
        private const val KEY_PLAY = 0L
        private const val KEY_PAUSE = 1L
        private const val KEY_PREVIOUS = 3L
        private const val KEY_NEXT = 4L
        private const val KEY_VOLUME = 5L

        /**
         * MusicInfo (wear_media.proto): state 1 required, volume 2, track 4, artist 5, position 6
         * and duration 7 in seconds. No album field exists. With no session GB sends state 0 and volume.
         */
        fun musicInfo(np: NowPlaying?, volume: Int?): ByteArray {
            val vol = volume?.coerceIn(0, 100)
            val parts = ArrayList<ByteArray>()
            parts.add(Proto.num(1, if (np == null) 0L else if (np.playing) 1L else 2L))
            if (vol != null) parts.add(Proto.num(2, vol.toLong()))
            if (np != null) {
                parts.add(Proto.text(4, clip(np.title)))
                parts.add(Proto.text(5, clip(np.artist)))
                parts.add(Proto.num(6, seconds(np.positionMs)))
                parts.add(Proto.num(7, seconds(np.durationMs)))
            }
            return parts.fold(ByteArray(0)) { a, b -> a + b }
        }

        private fun seconds(ms: Long): Long = (ms.coerceAtLeast(0) / 1000).coerceAtMost(0xFFFFFFFFL)

        /** At most [TEXT_LIMIT] UTF-16 units without splitting a surrogate pair. */
        private fun clip(s: String): String {
            if (s.length <= TEXT_LIMIT) return s
            val end = if (Character.isHighSurrogate(s[TEXT_LIMIT - 1])) TEXT_LIMIT - 1 else TEXT_LIMIT
            return s.substring(0, end)
        }

        fun parseSettings(body: JSONObject): Boolean {
            for (k in body.keys()) require(k == "enabled") { "Unknown music setting '$k'" }
            val v = body.opt("enabled")
            require(v is Boolean) { "Set enabled to true or false" }
            return v
        }
    }
}
