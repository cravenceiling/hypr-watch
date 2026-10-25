// Weather encoding ported from Gadgetbridge (AGPL-3.0-or-later): XiaomiWeatherService,
// XiaomiWeatherConditions and WeatherSpec.toBeaufort. The WMO table and the Open-Meteo client are this app's.
package local.hyprwatch

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Weather on the watch (type 10) from Open-Meteo, and the /api/weather routes. Off until the user
 * turns it on. Fetches run on a worker thread; a failed fetch keeps the last good forecast, and one
 * that a settings change overtook is dropped. Coordinates are coarse, rounded to 2 decimals before
 * they leave the phone, and never logged.
 *
 * [requestLocation] asks the Activity for the coarse location permission; false when there was
 * nothing to ask. [http] is an HTTPS GET returning the body. [worker] runs fetches (tests pass a
 * direct executor); null makes the feature own a thread, shut down in [close].
 */
class WeatherFeature(
    private val requestLocation: () -> Boolean = { false },
    private val http: (String) -> String = OpenMeteo::get,
    worker: Executor? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : WatchFeature {
    override val name = "weather"

    private lateinit var host: FeatureHost
    private val owned: ExecutorService? = if (worker == null) Executors.newSingleThreadExecutor { r -> Thread(r, "weather").apply { isDaemon = true } } else null
    private val worker: Executor = worker ?: owned!!
    private val lock = Any()

    // All below under [lock].
    private var data: WeatherForecast? = null
    private var lastError: String? = null
    private var replied = false
    private var rejected = false

    /** The last non-zero status per command the watch answered this session ("10/6" to 1); a later 0 clears it. */
    private val errors = sortedMapOf<Int, Long>()

    /** From the watch's 10/5 answer: null before one, false for a single-location watch. */
    private var multi: Boolean? = null

    /** The locations a multi-location watch holds, as far as this session knows. */
    private var locations = emptyList<WatchLocation>()
    private var session = 0

    /** Bumped by each settings change, so a fetch started before it is dropped. */
    private var configGen = 0
    private var pushed = true

    /** The place of an update a fix queued or of the update running, so a second fix of it queues nothing. */
    private var pending: WeatherPlace? = null
    private var fallback: (() -> Unit)? = null
    private var periodic: (() -> Unit)? = null
    private var cancelFix: (() -> Unit)? = null

    override fun install(host: FeatureHost) {
        this.host = host
        cancelFix = host.phone.onLocation { onFix() }
        host.on(TYPE, CMD_LOCATIONS) { cmd -> onLocations(cmd) }
        host.on(TYPE, CMD_REQUEST) { if (config().enabled) enqueue(force = false, push = true) }
        // Every command this feature sends that the watch answers with a status; 10/6 and 10/8 replies
        // were logged as unhandled on the watch (firmware 2.2.105) before they had a handler.
        for (sub in intArrayOf(CMD_CURRENT, CMD_DAILY, CMD_HOURLY, CMD_SET_LOCATIONS, CMD_ADD_LOCATION, CMD_REMOVE_LOCATIONS, CMD_PREFS)) {
            host.on(TYPE, sub) { cmd -> onReply(sub, cmd) }
        }
        host.route("GET", "/api/weather") {
            // A location that appeared since the last update (permission granted, first fix) is pushed now.
            val cfg = config()
            if (cfg.enabled && host.connected && isNoLocation(synchronized(lock) { lastError }) && target(cfg) != null) enqueue(force = false, push = true)
            body()
        }
        host.route("PUT", "/api/weather/settings") { r -> putSettings(r.body) }
        host.route("POST", "/api/weather/refresh") {
            val cfg = config()
            require(cfg.enabled) { "Weather is off" }
            require(cfg.manual != null || host.phone.coarseLocation() != null) { noLocation() }
            val error = host.operation { runAndWait() }
            if (error != null) throw IOException(error)
            body()
        }
        host.route("POST", "/api/weather/permission") {
            JSONObject().put("requested", requestLocation()).put("location_available", host.phone.coarseLocation() != null)
        }
    }

    override fun onConnected() {
        if (config().enabled) begin()
    }

    override fun onDisconnected() = stop()

    /**
     * A fresh device fix: update when the watch has no weather yet or the rounded place moved, unless
     * an update for that place is already queued or running (opening the app can give two fixes).
     */
    private fun onFix() {
        val cfg = config()
        if (!cfg.enabled || cfg.manual != null || !host.connected) return
        val place = target(cfg)
        val due = synchronized(lock) {
            (isNoLocation(lastError) || data?.place != place) && place != pending && run {
                pending = place
                true
            }
        }
        if (due) enqueue(force = false, push = true)
    }

    /** Ends this session's timer and periodic refresh. */
    private fun stop() {
        val cancel = synchronized(lock) {
            session++
            pushed = true
            listOfNotNull(fallback, periodic).also {
                fallback = null
                periodic = null
            }
        }
        cancel.forEach { it() }
    }

    override fun status(): JSONObject {
        val cfg = config()
        synchronized(lock) {
            return JSONObject()
                .put("enabled", cfg.enabled)
                .put("last_update", data?.let { iso(it.fetchedMs / 1000, 0) } ?: JSONObject.NULL)
                .put("last_error", lastError ?: JSONObject.NULL)
                .put("location_name", cfg.manual?.name ?: cfg.deviceName ?: CURRENT_LOCATION)
                .put("supported", if (replied) true else if (rejected) false else JSONObject.NULL)
                .put("watch_locations", multi?.let { if (it) "multiple" else "single" } ?: JSONObject.NULL)
                .put("command_errors", JSONObject().also { o -> errors.forEach { (sub, st) -> o.put("$TYPE/$sub", st) } })
        }
    }

    override fun close() {
        cancelFix?.invoke()
        stop()
        owned?.shutdownNow()
    }

    /** Units, then the location list; weather follows its answer, or [FALLBACK_MS] without one. */
    private fun begin() {
        stop()
        val gen = synchronized(lock) {
            session++
            pushed = false
            multi = null
            locations = emptyList()
            errors.clear()
            session
        }
        host.send(prefs(config().metric), "weather units")
        host.send(Proto.command(TYPE, CMD_LOCATIONS), "weather locations request")
        val timer = host.after(FALLBACK_MS) { pushOnce(gen) }
        // Blocks until the update ends, so the phone stays awake for the fetch.
        val every = host.phone.every(PERIOD_MS) { if (host.connected) runAndWait() }
        val stale = synchronized(lock) {
            if (gen == session) {
                fallback = timer
                periodic = every
                false
            } else {
                true
            }
        }
        if (stale) {
            timer()
            every()
        }
    }

    /** 10/5: status 1 is a single-location watch; otherwise its locations, pruned to ours as GB does. */
    private fun onLocations(cmd: Map<Int, List<Any>>) {
        val status = Proto.number(cmd, 100, 0)
        val enabled = config().enabled
        val gen = synchronized(lock) {
            when (status) {
                1L -> multi = false
                0L -> {
                    multi = true
                    locations = Proto.message(Proto.message(cmd, 12), 4)[1].orEmpty().mapNotNull { (it as? ByteArray)?.let(Proto::decode) }
                        .map { WatchLocation(Proto.string(it, 1), Proto.string(it, 2)) }
                    data?.takeIf { enabled }?.let { prune(WatchLocation.of(it.place)) }
                }
                else -> host.log("Watch answered the weather locations request with status $status")
            }
            session
        }
        pushOnce(gen)
    }

    /** Removes (10/8) every location but [ours], and [ours] too when it is listed twice; it is added again on the next push. Under [lock]. */
    private fun prune(ours: WatchLocation) {
        val stale = locations.filter { it != ours || locations.count { l -> l == ours } > 1 }.distinct()
        if (stale.isEmpty()) return
        if (host.send(Proto.command(TYPE, CMD_REMOVE_LOCATIONS, weather(locationList(stale))), "weather location removal")) locations = locations - stale.toSet()
    }

    private fun onReply(sub: Int, cmd: Map<Int, List<Any>>) {
        val status = Proto.number(cmd, 100, 0)
        synchronized(lock) {
            if (status == 0L) replied = true else if (sub == CMD_DAILY || sub == CMD_HOURLY) rejected = true
            // 10/7 status 3: the location may already exist, which is no error (GB).
            if (status == 0L || (sub == CMD_ADD_LOCATION && status == 3L)) errors.remove(sub) else errors[sub] = status
        }
        if (status != 0L) host.log("Watch answered weather command $TYPE/$sub with status $status")
    }

    private fun pushOnce(gen: Int) {
        val cancel = synchronized(lock) {
            if (gen != session || pushed) return
            pushed = true
            fallback.also { fallback = null }
        }
        cancel?.invoke()
        if (config().enabled) enqueue(force = false, push = true)
    }

    private fun enqueue(force: Boolean, push: Boolean) {
        try {
            worker.execute { guarded { update(force, push) } }
        } catch (e: RejectedExecutionException) {
            host.log("Weather update skipped: the service is stopping")
        }
    }

    /** A refresh on the worker, for a route; the error message, or null. */
    private fun runAndWait(): String? {
        val task = FutureTask { update(force = true, push = true) }
        try {
            worker.execute(task)
            return task.get(WAIT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IOException("The weather update is still running")
        } catch (e: RejectedExecutionException) {
            throw IOException("The service is stopping")
        }
    }

    /**
     * Fetches unless the forecast for this place is fresh, then pushes it if connected; the error
     * message, or null. A settings change during the fetch drops its result. Marks its place
     * [pending] while it runs.
     */
    private fun update(force: Boolean, push: Boolean): String? {
        val (gen, cfg) = synchronized(lock) { configGen to config() }
        val place = if (cfg.enabled) target(cfg) else null
        synchronized(lock) { pending = place }
        try {
            return if (cfg.enabled) fetchAndPush(gen, place, force, push) else null
        } finally {
            synchronized(lock) { if (pending == place) pending = null }
        }
    }

    private fun fetchAndPush(gen: Int, place: WeatherPlace?, force: Boolean, push: Boolean): String? {
        var error: String? = null
        var fetched: WeatherForecast? = null
        if (place == null) {
            error = noLocation()
        } else {
            val cached = synchronized(lock) { data }
            val fresh = cached != null && cached.place == place && now() - cached.fetchedMs < FRESH_MS
            if (force || !fresh) {
                try {
                    fetched = OpenMeteo.parse(http(OpenMeteo.url(place.lat, place.lon)), place, now())
                } catch (e: Exception) {
                    error = describe(e)
                }
            }
        }
        // Checked and sent under the lock, so a settings change lands before or after all of it.
        synchronized(lock) {
            if (gen != configGen) return null
            fetched?.let { data = it }
            lastError = error
            if (push && host.connected) data?.let(::send)
        }
        if (error != null) host.log("Weather update failed: $error")
        return error
    }

    /** As GB: add the location if the watch lacks it; on a multi-location watch also set the order and re-read the list to prune it. Under [lock]. */
    private fun send(f: WeatherForecast) {
        val ours = WatchLocation.of(f.place)
        val at = now()
        if (ours !in locations && host.send(Proto.command(TYPE, CMD_ADD_LOCATION, weather(Proto.bytes(5, ours.encode()))), "weather location") && multi == true) {
            locations = locations + ours
        }
        if (multi == true) host.send(Proto.command(TYPE, CMD_SET_LOCATIONS, weather(locationList(listOf(ours)))), "weather location order")
        host.send(current(f), "current weather")
        host.send(daily(f, at), "daily forecast")
        host.send(hourly(f, at), "hourly forecast")
        if (multi == true) host.send(Proto.command(TYPE, CMD_LOCATIONS), "weather locations request")
    }

    private fun putSettings(input: JSONObject): JSONObject {
        val old = config()
        var cfg = old
        for (key in input.keys().asSequence().toList()) {
            when (key) {
                "units" -> cfg = cfg.copy(
                    metric = when (input.opt(key)) {
                        "metric" -> true
                        "imperial" -> false
                        else -> throw IllegalArgumentException("Units must be metric or imperial")
                    },
                )
                "enabled" -> cfg = cfg.copy(enabled = input.opt(key) as? Boolean ?: throw IllegalArgumentException("Enabled must be true or false"))
                "location" -> cfg = location(input.optJSONObject(key) ?: throw IllegalArgumentException("Location must be an object"), cfg)
                else -> throw IllegalArgumentException("Unknown weather setting '$key'")
            }
        }
        synchronized(lock) {
            host.settings.put(SETTINGS, cfg.json())
            configGen++
        }
        val on = cfg.enabled
        if (!on) stop()
        if (host.connected && on) {
            if (!old.enabled) {
                begin()
            } else {
                host.send(prefs(cfg.metric), "weather units")
                enqueue(force = cfg.manual != old.manual, push = true)
            }
        } else if (on && cfg.manual != old.manual) {
            enqueue(force = true, push = false)
        }
        return body()
    }

    private fun location(o: JSONObject, cfg: Config): Config {
        for (k in o.keys().asSequence().toList()) require(k in setOf("mode", "lat", "lon", "name")) { "Unknown location field '$k'" }
        val name = if (o.has("name") && !o.isNull("name")) {
            val n = (o.get("name") as? String ?: throw IllegalArgumentException("Location name must be text")).trim()
            require(n.isNotEmpty() && n.length <= MAX_NAME && n.none { it.isISOControl() }) { "Use a location name of 1 to $MAX_NAME characters" }
            n
        } else {
            null
        }
        return when (o.opt("mode")) {
            "device" -> {
                require(!o.has("lat") && !o.has("lon")) { "Device location takes no coordinates" }
                cfg.copy(manual = null, deviceName = name)
            }
            "manual" -> {
                val lat = coordinate(o, "lat", 90.0)
                val lon = coordinate(o, "lon", 180.0)
                cfg.copy(manual = WeatherPlace(round2(lat), round2(lon), name ?: throw IllegalArgumentException("A manual location needs a name"), false))
            }
            else -> throw IllegalArgumentException("Location mode must be device or manual")
        }
    }

    private fun coordinate(o: JSONObject, key: String, limit: Double): Double {
        val v = (o.opt(key) as? Number)?.toDouble() ?: throw IllegalArgumentException("Location $key must be a number")
        require(v.isFinite() && v >= -limit && v <= limit) { "Location $key must be between -$limit and $limit" }
        return v
    }

    private fun body(): JSONObject {
        val cfg = config()
        val f = synchronized(lock) { data }
        return JSONObject().put("settings", cfg.json()).put("status", status()).put("data", f?.json(cfg.metric, now()) ?: JSONObject.NULL)
    }

    /** Why there is no place: Location off in the phone's settings needs other advice than a missing permission. */
    private fun noLocation(): String = if (host.phone.locationAccess() == LocationAccess.TURNED_OFF) LOCATION_OFF else NO_LOCATION

    private fun isNoLocation(error: String?): Boolean = error == NO_LOCATION || error == LOCATION_OFF

    private fun config(): Config = Config.from(host.settings.get(SETTINGS))

    private fun target(cfg: Config): WeatherPlace? =
        cfg.manual ?: host.phone.coarseLocation()?.let { WeatherPlace(round2(it.latitude), round2(it.longitude), cfg.deviceName ?: CURRENT_LOCATION, true) }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            host.log("Weather task failed: ${e.javaClass.simpleName}")
        }
    }

    /** [enabled] only by the user's choice: a location alone never sends coordinates to Open-Meteo. */
    private data class Config(val metric: Boolean = true, val manual: WeatherPlace? = null, val deviceName: String? = null, val enabled: Boolean = false) {
        fun json(): JSONObject {
            val loc = if (manual != null) {
                JSONObject().put("mode", "manual").put("lat", manual.lat).put("lon", manual.lon).put("name", manual.name)
            } else {
                JSONObject().put("mode", "device").put("name", deviceName ?: JSONObject.NULL)
            }
            return JSONObject().put("units", if (metric) "metric" else "imperial").put("location", loc).put("enabled", enabled)
        }

        companion object {
            fun from(o: JSONObject?): Config {
                if (o == null) return Config()
                val loc = o.optJSONObject("location")
                val name = loc?.optString("name")?.takeIf { !loc.isNull("name") && it.isNotEmpty() }
                val manual = if (loc?.optString("mode") == "manual" && name != null) WeatherPlace(loc.getDouble("lat"), loc.getDouble("lon"), name, false) else null
                return Config(
                    metric = o.optString("units") != "imperial",
                    manual = manual,
                    deviceName = if (manual == null) name else null,
                    enabled = o.optBoolean("enabled", false),
                )
            }
        }
    }

    companion object {
        const val TYPE = 10
        const val CMD_CURRENT = 0
        const val CMD_DAILY = 1
        const val CMD_HOURLY = 2
        const val CMD_REQUEST = 3
        const val CMD_LOCATIONS = 5
        const val CMD_SET_LOCATIONS = 6
        const val CMD_ADD_LOCATION = 7
        const val CMD_REMOVE_LOCATIONS = 8
        const val CMD_PREFS = 10
        const val FALLBACK_MS = 5_000L
        const val PERIOD_MS = 30 * 60_000L
        const val FRESH_MS = 10 * 60_000L
        private const val WAIT_S = 25L
        private const val MAX_NAME = 32
        private const val SETTINGS = "config"
        private const val CURRENT_LOCATION = "Current location"
        private const val NO_LOCATION = "No location: allow location access or set a manual location"
        private const val LOCATION_OFF = "Location is off: turn on Location in the phone's settings or set a manual location"
        private const val CELSIUS = "℃"
        private val BEAUFORT = floatArrayOf(2f, 6f, 12f, 20f, 29f, 39f, 50f, 62f, 75f, 89f, 103f, 118f)
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx", Locale.ROOT)

        fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

        /** Open-Meteo's WMO weather code as a watch condition; anything unmapped is clear sky, never above 33. */
        fun condition(wmo: Int): Int = when (wmo) {
            0 -> 0
            1, 2 -> 1
            3 -> 2
            45, 48 -> 18
            51, 53, 55, 61 -> 7
            56, 57, 66, 67 -> 19
            63 -> 8
            65 -> 10
            71 -> 14
            73 -> 15
            75 -> 16
            77 -> 33
            80, 81 -> 3
            82 -> 9
            85 -> 13
            86 -> 17
            95 -> 4
            96, 99 -> 5
            else -> 0
        }.coerceIn(0, 33)

        fun beaufort(kmh: Double): Int {
            var level = 0
            while (level < BEAUFORT.size && BEAUFORT[level] < kmh) level++
            return level
        }

        /** Gadgetbridge's placeholder key: the same name always gives the same code. */
        fun locationKey(name: String): String = "accu:" + (Math.abs(name.hashCode().toLong()) % 1_000_000)

        fun iso(epochS: Long, offsetS: Int): String = OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochS), ZoneOffset.ofTotalSeconds(offsetS)).format(ISO)

        fun prefs(metric: Boolean): ByteArray = Proto.command(TYPE, CMD_PREFS, weather(Proto.bytes(6, Proto.num(1, if (metric) 1L else 2L))))

        fun current(f: WeatherForecast): ByteArray {
            val c = f.current
            val body = Proto.join(
                metadata(f),
                Proto.num(2, condition(c.wmo).toLong()),
                unit(3, CELSIUS, Math.round(c.tempC).toInt()),
                unit(4, "%", c.humidity),
                unit(5, c.windDir.toString(), beaufort(c.windKmh)),
                unit(6, "", Math.round(c.uv).toInt()),
                unit(7, "Unknown", 0),
                Proto.bytes(8, ByteArray(0)),
                Proto.float(9, (c.pressureHpa * 100).toFloat()),
            )
            return Proto.command(TYPE, CMD_CURRENT, weather(Proto.bytes(1, body)))
        }

        /** Today rebuilt from current conditions, then up to 6 more days. */
        fun daily(f: WeatherForecast, nowMs: Long): ByteArray {
            val nowS = nowMs / 1000
            val start = f.days.indexOfFirst { nowS < it.time + 86_400 }.takeIf { it >= 0 } ?: f.days.size
            val days = f.days.subList(start, minOf(start + 7, f.days.size))
            val entries = days.mapIndexed { i, d ->
                val cond = condition(if (i == 0 && d.time <= nowS) f.current.wmo else d.wmo)
                entry(
                    unit(1, "Unknown", 0),
                    range(2, cond, cond),
                    range(3, Math.round(d.maxC).toInt(), Math.round(d.minC).toInt()),
                    Proto.text(4, CELSIUS),
                    Proto.bytes(5, Proto.join(Proto.text(1, sun(d.sunrise, f.offsetS)), Proto.text(2, sun(d.sunset, f.offsetS)))),
                )
            }
            return forecast(CMD_DAILY, f, entries)
        }

        /** Up to 23 hours, starting with the first one after now. */
        fun hourly(f: WeatherForecast, nowMs: Long): ByteArray {
            val entries = f.next(nowMs).map { h ->
                entry(
                    unit(1, "Unknown", 0),
                    range(2, 0, condition(h.wmo)),
                    range(3, 0, Math.round(h.tempC).toInt()),
                    Proto.text(4, CELSIUS),
                    unit(6, h.windDir.toString(), beaufort(h.windKmh)),
                )
            }
            return forecast(CMD_HOURLY, f, entries)
        }

        private fun sun(t: Long, offsetS: Int): String = if (t == 0L) "" else iso(t, offsetS)

        private fun forecast(sub: Int, f: WeatherForecast, entries: List<ByteArray>): ByteArray =
            Proto.command(TYPE, sub, weather(Proto.bytes(2, Proto.join(metadata(f), Proto.bytes(2, Proto.join(*entries.toTypedArray()))))))

        private fun entry(vararg fields: ByteArray): ByteArray = Proto.bytes(1, Proto.join(*fields))

        private fun metadata(f: WeatherForecast): ByteArray = Proto.bytes(
            1,
            Proto.join(
                Proto.text(1, iso(f.fetchedMs / 1000, f.offsetS)),
                Proto.text(2, ""),
                Proto.text(3, f.place.name),
                Proto.text(4, locationKey(f.place.name)),
                Proto.num(5, if (f.place.current) 1L else 0L),
            ),
        )

        private fun weather(payload: ByteArray): ByteArray = Proto.bytes(12, payload)

        /** Weather.locations: WeatherLocations { repeated WeatherLocation location = 1 }. */
        private fun locationList(l: List<WatchLocation>): ByteArray = Proto.bytes(4, Proto.join(*l.map { Proto.bytes(1, it.encode()) }.toTypedArray()))

        private fun unit(field: Int, unit: String, value: Int): ByteArray = Proto.bytes(field, Proto.join(Proto.text(1, unit), Proto.s32(2, value)))

        private fun range(field: Int, from: Int, to: Int): ByteArray = Proto.bytes(field, Proto.join(Proto.s32(1, from), Proto.s32(2, to)))

        /** A message safe for logs and status: never the URL, which holds the coordinates. */
        private fun describe(e: Exception): String = when (e) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Open-Meteo did not answer in time"
            is OpenMeteo.Failure -> e.message ?: "Open-Meteo failed"
            is JSONException, is IllegalArgumentException -> "Open-Meteo sent an unexpected answer"
            else -> "Network error (${e.javaClass.simpleName})"
        }
    }
}

/** [lat]/[lon] already rounded to 2 decimals; [current] means the phone's own location. */
data class WeatherPlace(val lat: Double, val lon: Double, val name: String, val current: Boolean)

/** A WeatherLocation { code = 1, name = 2 } as the watch lists it. */
data class WatchLocation(val code: String, val name: String) {
    fun encode(): ByteArray = Proto.join(Proto.text(1, code), Proto.text(2, name))

    companion object {
        fun of(p: WeatherPlace) = WatchLocation(WeatherFeature.locationKey(p.name), p.name)
    }
}

data class WeatherNow(val wmo: Int, val tempC: Double, val humidity: Int, val windKmh: Double, val windDir: Int, val pressureHpa: Double, val uv: Double)

/** [time] is local midnight; [sunrise]/[sunset] are 0 when unknown. Times are Unix seconds. */
data class WeatherDay(val time: Long, val wmo: Int, val maxC: Double, val minC: Double, val sunrise: Long, val sunset: Long)

data class WeatherHour(val time: Long, val wmo: Int, val tempC: Double, val windKmh: Double, val windDir: Int)

/** One Open-Meteo answer, in Celsius and km/h. [offsetS] is the place's UTC offset. */
data class WeatherForecast(
    val place: WeatherPlace,
    val fetchedMs: Long,
    val offsetS: Int,
    val current: WeatherNow,
    val days: List<WeatherDay>,
    val hours: List<WeatherHour>,
) {
    fun next(nowMs: Long): List<WeatherHour> = hours.filter { it.time * 1000 > nowMs }.take(23)

    /** For /api/weather, in the user's units; no coordinates. */
    fun json(metric: Boolean, nowMs: Long): JSONObject {
        fun t(c: Double): Double = WeatherFeature.round2(if (metric) c else c * 9 / 5 + 32)
        fun w(k: Double): Double = WeatherFeature.round2(if (metric) k else k / 1.609344)
        val days = JSONArray()
        for (d in this.days) {
            days.put(
                JSONObject().put("date", WeatherFeature.iso(d.time, offsetS).substring(0, 10)).put("wmo_code", d.wmo).put("condition", WeatherFeature.condition(d.wmo))
                    .put("max", t(d.maxC)).put("min", t(d.minC))
                    .put("sunrise", if (d.sunrise == 0L) JSONObject.NULL else WeatherFeature.iso(d.sunrise, offsetS))
                    .put("sunset", if (d.sunset == 0L) JSONObject.NULL else WeatherFeature.iso(d.sunset, offsetS)),
            )
        }
        val hours = JSONArray()
        for (h in next(nowMs)) {
            hours.put(
                JSONObject().put("time", WeatherFeature.iso(h.time, offsetS)).put("wmo_code", h.wmo).put("condition", WeatherFeature.condition(h.wmo))
                    .put("temperature", t(h.tempC)).put("wind_speed", w(h.windKmh)).put("wind_direction", h.windDir),
            )
        }
        val c = current
        return JSONObject()
            .put("location_name", place.name)
            .put("fetched_at", WeatherFeature.iso(fetchedMs / 1000, offsetS))
            .put("temperature_unit", if (metric) "°C" else "°F")
            .put("wind_unit", if (metric) "km/h" else "mph")
            .put(
                "current",
                JSONObject().put("wmo_code", c.wmo).put("condition", WeatherFeature.condition(c.wmo)).put("temperature", t(c.tempC))
                    .put("humidity", c.humidity).put("wind_speed", w(c.windKmh)).put("wind_direction", c.windDir)
                    .put("pressure_hpa", WeatherFeature.round2(c.pressureHpa)).put("uv_index", WeatherFeature.round2(c.uv)),
            )
            .put("daily", days)
            .put("hourly", hours)
    }
}

/** Open-Meteo's forecast API over HTTPS: no key, no account. Android-free. */
object OpenMeteo {
    /** A failure whose message is safe to log and show: no URL, no coordinates. */
    class Failure(message: String) : IOException(message)

    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 1 shl 20

    fun url(lat: Double, lon: Double): String =
        String.format(Locale.ROOT, "%s?latitude=%.2f&longitude=%.2f", BASE, lat, lon) +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,pressure_msl,uv_index" +
            "&hourly=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
            "&timezone=auto&timeformat=unixtime&temperature_unit=celsius&wind_speed_unit=kmh&forecast_days=7&forecast_hours=48"

    fun get(url: String): String {
        val u = URI(url).toURL()
        require(u.protocol == "https") { "Weather requests use HTTPS only" }
        val c = u.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code != 200) throw Failure("Open-Meteo answered HTTP $code")
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw Failure("Open-Meteo's answer is too large")
                }
                return out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Parses a `timeformat=unixtime` answer. Forecast entries reach the watch without timestamps, so
     * daily and hourly lists end at the first entry with a missing value instead of closing the gap.
     */
    fun parse(body: String, place: WeatherPlace, fetchedMs: Long): WeatherForecast {
        val o = JSONObject(body)
        if (o.optBoolean("error")) throw Failure("Open-Meteo refused the request")
        val c = o.getJSONObject("current")
        val current = WeatherNow(
            wmo = c.getInt("weather_code"),
            tempC = c.getDouble("temperature_2m"),
            humidity = c.optDouble("relative_humidity_2m", 0.0).let { if (it.isNaN()) 0 else Math.round(it).toInt() },
            windKmh = c.optDouble("wind_speed_10m", 0.0).orZero(),
            windDir = Math.round(c.optDouble("wind_direction_10m", 0.0).orZero()).toInt(),
            pressureHpa = c.optDouble("pressure_msl", 0.0).orZero(),
            uv = c.optDouble("uv_index", 0.0).orZero(),
        )
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = untilGap(dt.length()) { i ->
            val max = d.getJSONArray("temperature_2m_max").optDouble(i)
            val min = d.getJSONArray("temperature_2m_min").optDouble(i)
            val wmo = d.getJSONArray("weather_code").optDouble(i)
            if (max.isNaN() || min.isNaN() || wmo.isNaN()) null else WeatherDay(dt.getLong(i), wmo.toInt(), max, min, d.optJSONArray("sunrise")?.optLong(i, 0) ?: 0, d.optJSONArray("sunset")?.optLong(i, 0) ?: 0)
        }
        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hours = untilGap(ht.length()) { i ->
            val temp = h.getJSONArray("temperature_2m").optDouble(i)
            val wmo = h.getJSONArray("weather_code").optDouble(i)
            if (temp.isNaN() || wmo.isNaN()) null else WeatherHour(ht.getLong(i), wmo.toInt(), temp, h.optJSONArray("wind_speed_10m")?.optDouble(i)?.orZero() ?: 0.0, Math.round(h.optJSONArray("wind_direction_10m")?.optDouble(i)?.orZero() ?: 0.0).toInt())
        }
        return WeatherForecast(place, fetchedMs, o.optInt("utc_offset_seconds", 0), current, days, hours)
    }

    private fun Double.orZero(): Double = if (isNaN()) 0.0 else this

    private fun <T : Any> untilGap(n: Int, entry: (Int) -> T?): List<T> {
        val out = ArrayList<T>()
        for (i in 0 until n) out.add(entry(i) ?: break)
        return out
    }
}
