package local.hyprwatch

import org.json.JSONObject
import java.net.UnknownHostException
import java.util.concurrent.Executor

/** Weather over FeatureRig, fed hand-written Open-Meteo answers; never touches the network. */
object WeatherFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    // 2025-10-24T12:00:00Z; Berlin is UTC+2, local midnight 1761256800.
    private const val NOW = 1761307200000L

    // Expected bytes derived from wear_weather.proto with an independent encoder, not this app's Proto.
    private const val PREFS_C = "080a100a620432020801"
    private const val PREFS_F = "080a100a620432020802"
    private const val LOCATIONS = "080a1005"
    private const val ADD_BERLIN = "080a100762172a150a0b616363753a33303239313412064265726c696e"
    private const val CURRENT =
        "080a1000626e0a6c0a340a19323032352d31302d32345431343a30303a30302b30323a303012001a064265726c696e220b616363753a333032393134280010021a070a03e28483101a" +
            "22060a012510a2012a070a03323435100632040a0010023a0b0a07556e6b6e6f776e100042004d00f9c447"
    private const val DAILY =
        "080a1001629202128f020a340a19323032352d31302d32345431343a30303a30302b30323a303012001a064265726c696e220b616363753a333032393134280012d6010a560a0b0a07556e6b6e6f776e10001204080410041a04081c10102203e284832a360a19" +
            "323032352d31302d32345430373a35303a30302b30323a30301219323032352d31302d32345431383a30303a30302b30323a30300a240a0b0a07556e6b6e6f776e10001204080810081a04080110052203e284832a040a0012000a560a0b0a07556e6b6e6f776e10001204080010001a04080a10022203e284832a360a19" +
            "323032352d31302d32365430373a30303a30302b30323a30301219323032352d31302d32365431383a30303a30302b30323a3030"
    // One hour: the fixture's next hour has no temperature, and the list ends there.
    private const val HOURLY =
        "080a1002626312610a340a19323032352d31302d32345431343a30303a30302b30323a303012001a064265726c696e220b616363753a333032393134280012290a270a0b0a07556e6b6e6f776e10001204080010061a040800101a2203e2848332070a033138301002"
    private val PUSH = listOf(ADD_BERLIN, CURRENT, DAILY, HOURLY)

    // Multi-location watches (GB): 10/6 sets the order, 10/8 removes, and 10/5 re-reads after a push.
    private const val ORDER_BERLIN = "080a1006621922170a150a0b616363753a33303239313412064265726c696e"
    private const val REMOVE_PARIS = "080a1008621822160a140a0b616363753a38383433333112055061726973"

    // timeformat=unixtime; day 1 has no sunrise/sunset, hour 2 has a missing temperature, hour 0 is in the past.
    private val FIXTURE = """
        {"latitude":52.52,"longitude":13.4,"generationtime_ms":0.1,"utc_offset_seconds":7200,"timezone":"Europe/Berlin","timezone_abbreviation":"GMT+2","elevation":38.0,
         "current_units":{"time":"unixtime","temperature_2m":"°C"},
         "current":{"time":1761307200,"interval":900,"temperature_2m":12.6,"relative_humidity_2m":81,"weather_code":3,"wind_speed_10m":14.8,"wind_direction_10m":245,"pressure_msl":1008.5,"uv_index":1.35},
         "hourly_units":{"time":"unixtime"},
         "hourly":{"time":[1761303600,1761310800,1761314400,1761318000],
                   "temperature_2m":[11.0,13.4,null,-0.4],"weather_code":[2,80,3,77],
                   "wind_speed_10m":[1.0,5.0,7.0,2.0],"wind_direction_10m":[90,180,200,0]},
         "daily_units":{"time":"unixtime"},
         "daily":{"time":[1761256800,1761343200,1761429600],"weather_code":[61,95,42],
                  "temperature_2m_max":[14.2,-0.6,5.2],"temperature_2m_min":[8.4,-3.4,1.1],
                  "sunrise":[1761285000,null,1761454800],"sunset":[1761321600,null,1761494400]}}
    """.trimIndent()

    private class Net {
        val urls = ArrayList<String>()
        var answer: () -> String = { FIXTURE }

        fun get(url: String): String {
            urls.add(url)
            return answer()
        }
    }

    private fun rig(net: Net, now: () -> Long = { NOW }, asked: () -> Boolean = { true }): FeatureRig =
        FeatureRig(WeatherFeature(asked, net::get, Executor { it.run() }, now))

    private fun berlin(rig: FeatureRig): Pair<Int, JSONObject> =
        rig.call("PUT", "/api/weather/settings", JSONObject().put("enabled", true).put("location", JSONObject().put("mode", "manual").put("lat", 52.5168).put("lon", 13.4049).put("name", " Berlin ")))

    private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun weather(rig: FeatureRig): JSONObject = rig.status().getJSONObject("weather")

    private fun noCoordinates(rig: FeatureRig) = check(rig.logs.none { it.contains("52.5") || it.contains("13.4") }, "Logs never hold coordinates")

    @JvmStatic
    fun main(args: Array<String>) {
        conditions()
        parsing()
        connectFlow()
        multiLocation()
        failures()
        routes()
        deviceLocation()
        freshFix()
        coalescedFixes()
        disconnects()
        println("Weather feature: $assertions assertions passed")
    }

    private fun conditions() {
        check((-10..300).all { WeatherFeature.condition(it) in 0..33 }, "Never a condition above 33")
        check(WeatherFeature.condition(3) == 2 && WeatherFeature.condition(77) == 33 && WeatherFeature.condition(99) == 5 && WeatherFeature.condition(42) == 0, "WMO table")
        check(WeatherFeature.beaufort(2.0) == 0 && WeatherFeature.beaufort(2.1) == 1 && WeatherFeature.beaufort(14.8) == 3 && WeatherFeature.beaufort(200.0) == 12, "Beaufort thresholds")
        check(WeatherFeature.locationKey("Berlin") == "accu:302914", "Gadgetbridge's location key")
    }

    /** Watch entries carry no timestamps, so a gap ends the list instead of shifting later entries into it. */
    private fun parsing() {
        val place = WeatherPlace(52.52, 13.4, "Berlin", false)
        val f = OpenMeteo.parse(FIXTURE, place, NOW)
        check(f.hours.map { it.time } == listOf(1761303600L, 1761310800L), "Hours end at the first missing value: ${f.hours}")
        check(f.days.size == 3 && f.current.pressureHpa == 1008.5, "Days without gaps are kept; pressure is sea level")
        val gap = OpenMeteo.parse(FIXTURE.replace("\"temperature_2m_max\":[14.2,-0.6,5.2]", "\"temperature_2m_max\":[14.2,null,5.2]"), place, NOW)
        check(gap.days.map { it.time } == listOf(1761256800L), "Days end at the first missing value: ${gap.days}")
        check(OpenMeteo.url(52.52, 13.4).contains(",pressure_msl,") && !OpenMeteo.url(52.52, 13.4).contains("surface_pressure"), "Requests sea-level pressure, as GB sends")
    }

    private fun connectFlow() {
        val net = Net()
        val rig = rig(net)
        check(!weather(rig).getBoolean("enabled"), "Off by default")
        val manualOnly = rig.call("PUT", "/api/weather/settings", JSONObject().put("location", JSONObject().put("mode", "manual").put("lat", 1).put("lon", 2).put("name", "X")))
        check(!manualOnly.second.getJSONObject("status").getBoolean("enabled") && net.urls.isEmpty(), "A location alone is not consent: nothing fetched")
        val (code, body) = berlin(rig)
        check(code == 200 && body.getJSONObject("settings").getJSONObject("location").similar(JSONObject("""{"mode":"manual","lat":52.52,"lon":13.4,"name":"Berlin"}""")), "Manual location stored rounded and trimmed")
        check(net.urls.size == 1 && net.urls[0].startsWith("https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.40&"), "Fetched over HTTPS with rounded coordinates")
        check(rig.sent().isEmpty(), "Nothing sent while disconnected")
        val data = body.getJSONObject("data")
        check(data.getJSONObject("current").getInt("condition") == 2 && data.getJSONObject("current").getDouble("temperature") == 12.6, "Current conditions parsed")
        check(data.getJSONArray("hourly").length() == 1 && data.getJSONArray("daily").getJSONObject(1).isNull("sunrise"), "Past hours skipped, the list ends at the gap, missing sunrise is null")
        check(weather(rig).isNull("supported") && weather(rig).getBoolean("enabled"), "Unknown support until the watch answers")

        rig.connect()
        check(rig.take() == listOf(PREFS_C, LOCATIONS), "Connect: units, then the location list")
        check(rig.watch.delays() == listOf(WeatherFeature.FALLBACK_MS) && rig.phone.periodic.single().first == WeatherFeature.PERIOD_MS, "Fallback timer and 30-minute refresh armed")
        rig.receive(Proto.command(10, 5, Proto.num(100, 1)))
        check(rig.take() == PUSH, "Single-location watch: add location, current, daily, hourly")
        check(net.urls.size == 1, "A fresh forecast is not fetched again")
        check(rig.watch.delays().isEmpty(), "The answer cancels the fallback")
        check(weather(rig).getString("watch_locations") == "single", "Single-location watch reported")
        rig.receive(Proto.command(10, 5, Proto.num(100, 1)))
        check(rig.take().isEmpty(), "One push per connect")

        rig.receive(Proto.command(10, 1, Proto.num(100, 0)))
        check(weather(rig).getBoolean("supported"), "Status 0 means supported")

        rig.receive(Proto.command(10, 3, Proto.bytes(12, Proto.bytes(5, Proto.join(Proto.text(1, ""), Proto.text(2, ""))))))
        check(rig.take() == PUSH, "The watch's request gets the forecast")

        rig.phone.tick()
        check(net.urls.size == 2 && rig.take() == PUSH, "The 30-minute task fetches and pushes")

        // Multi-location watch that already knows the place: no 10/7.
        rig.disconnect()
        rig.connect()
        rig.take()
        val known = Proto.bytes(12, Proto.bytes(4, Proto.bytes(1, Proto.join(Proto.text(1, "accu:302914"), Proto.text(2, "Berlin")))))
        rig.receive(Proto.command(10, 5, known))
        check(rig.take() == listOf(ORDER_BERLIN, CURRENT, DAILY, HOURLY, LOCATIONS) && weather(rig).getString("watch_locations") == "multiple", "Known location is not added again")

        // No answer to 10/5: the fallback pushes.
        rig.disconnect()
        rig.connect()
        rig.take()
        rig.fire()
        check(rig.take() == PUSH, "Fallback pushes without a 10/5 answer")

        val rejected = rig(Net())
        rejected.receive(Proto.command(10, 1, Proto.num(100, 1)))
        check(!weather(rejected).getBoolean("supported"), "Status 1 on the forecast means unsupported")
        rejected.receive(Proto.command(10, 0, Proto.num(100, 0)))
        check(weather(rejected).getBoolean("supported"), "A later status 0 wins")
        noCoordinates(rig)
    }

    /** As GB: entries other than ours are removed, so a renamed location never lingers on the watch. */
    private fun multiLocation() {
        val rig = rig(Net())
        berlin(rig)
        rig.connect()
        rig.take()
        val paris = Proto.join(Proto.text(1, "accu:884331"), Proto.text(2, "Paris"))
        val berlin = Proto.join(Proto.text(1, "accu:302914"), Proto.text(2, "Berlin"))
        rig.receive(Proto.command(10, 5, Proto.bytes(12, Proto.bytes(4, Proto.join(Proto.bytes(1, berlin), Proto.bytes(1, paris))))))
        check(rig.take() == listOf(REMOVE_PARIS, ORDER_BERLIN, CURRENT, DAILY, HOURLY, LOCATIONS), "Stale location removed before the push")
        rig.receive(Proto.command(10, 5, Proto.bytes(12, Proto.bytes(4, Proto.bytes(1, berlin)))))
        check(rig.take().isEmpty(), "The re-read finds only ours: nothing more")

        // Replies to 10/6 and 10/8, as the watch sent them on firmware 2.2.105: type, subtype, status (fields 1, 2, 100).
        check(weather(rig).getJSONObject("command_errors").length() == 0, "No command errors yet")
        rig.receive(bytes("080a1006a00600"))
        check(weather(rig).getJSONObject("command_errors").length() == 0 && rig.take().isEmpty(), "10/6 status 0 is fine and sends nothing")
        val logged = rig.logs.size
        rig.receive(bytes("080a1006a00602"))
        check(weather(rig).getJSONObject("command_errors").toString() == """{"10/6":2}""", "A refused location order shows in status: ${weather(rig)}")
        check(rig.logs.drop(logged) == listOf("Watch answered weather command 10/6 with status 2"), "and is logged")
        rig.receive(bytes("080a1008a00601"))
        check(weather(rig).getJSONObject("command_errors").toString() == """{"10/6":2,"10/8":1}""", "10/8 is handled the same way")
        rig.receive(bytes("080a1006a00600"))
        rig.receive(bytes("080a1007a00603"))
        check(weather(rig).getJSONObject("command_errors").toString() == """{"10/8":1}""", "A later status 0 clears it; 10/7 status 3 (may already exist) is no error")
        check(rig.take().isEmpty(), "Replies send nothing")
        val handled = rig.watch.handlers.keys.filter { it.first == 10 }.map { it.second }.toSortedSet()
        check(handled == sortedSetOf(0, 1, 2, 3, 5, 6, 7, 8, 10), "Every weather command the app sends has a reply handler: $handled")

        rig.disconnect()
        rig.connect()
        rig.take()
        rig.receive(Proto.command(10, 5, Proto.bytes(12, Proto.bytes(4, ByteArray(0)))))
        check(rig.take() == listOf(ADD_BERLIN, ORDER_BERLIN, CURRENT, DAILY, HOURLY, LOCATIONS) && weather(rig).getString("watch_locations") == "multiple", "An empty multi-location list: add, order, push, re-read")
    }

    private fun failures() {
        val net = Net()
        val rig = rig(net)
        berlin(rig)
        rig.connect()
        rig.receive(Proto.command(10, 5, Proto.num(100, 1)))
        rig.take()
        net.answer = { throw UnknownHostException("api.open-meteo.com") }
        rig.phone.tick()
        val s = weather(rig)
        check(s.getString("last_error") == "No internet connection" && s.getString("last_update") == "2025-10-24T12:00:00+00:00", "Failure reported, last good data kept")
        check(rig.take() == PUSH, "The last good forecast still goes out")
        val (code, body) = rig.call("POST", "/api/weather/refresh")
        check(code == 503 && body.getString("error") == "No internet connection", "Refresh failure is a 503")
        net.answer = { """{"error":true,"reason":"Latitude must be in range of -90 to 90°. Given: 52.52."}""" }
        check(rig.call("POST", "/api/weather/refresh").second.getString("error") == "Open-Meteo refused the request", "Open-Meteo errors are not echoed")
        net.answer = { "<html>" }
        check(rig.call("POST", "/api/weather/refresh").second.getString("error") == "Open-Meteo sent an unexpected answer", "Garbage is reported plainly")
        net.answer = { FIXTURE }
        val ok = rig.call("POST", "/api/weather/refresh")
        check(ok.first == 200 && ok.second.getJSONObject("status").isNull("last_error"), "A good refresh clears the error")
        noCoordinates(rig)
    }

    private fun routes() {
        val net = Net()
        var asks = 0
        val rig = rig(net, asked = { asks++; true })
        val bad = listOf(
            JSONObject().put("units", "kelvin") to "Units must be metric or imperial",
            JSONObject().put("enabled", "yes") to "Enabled must be true or false",
            JSONObject().put("colour", 1) to "Unknown weather setting 'colour'",
            JSONObject().put("location", "Berlin") to "Location must be an object",
            JSONObject().put("location", JSONObject().put("mode", "gps")) to "Location mode must be device or manual",
            JSONObject().put("location", JSONObject().put("mode", "manual").put("lat", 91).put("lon", 0).put("name", "X")) to "Location lat must be between -90.0 and 90.0",
            JSONObject().put("location", JSONObject().put("mode", "manual").put("lat", 1).put("lon", "2").put("name", "X")) to "Location lon must be a number",
            JSONObject().put("location", JSONObject().put("mode", "manual").put("lat", 1).put("lon", 2)) to "A manual location needs a name",
            JSONObject().put("location", JSONObject().put("mode", "manual").put("lat", 1).put("lon", 2).put("name", "x".repeat(33))) to "Use a location name of 1 to 32 characters",
            JSONObject().put("location", JSONObject().put("mode", "device").put("lat", 1)) to "Device location takes no coordinates",
            JSONObject().put("location", JSONObject().put("mode", "device").put("alt", 1)) to "Unknown location field 'alt'",
        )
        for ((input, message) in bad) {
            val (code, body) = rig.call("PUT", "/api/weather/settings", input)
            check(code == 400 && body.getString("error") == message, "400: $message")
        }
        check(rig.settings.values.isEmpty(), "Rejected settings are not stored")
        check(rig.call("POST", "/api/weather/refresh").let { it.first == 400 && it.second.getString("error") == "Weather is off" }, "Refresh needs weather on")

        berlin(rig)
        rig.connect()
        rig.receive(Proto.command(10, 5, Proto.num(100, 1)))
        rig.take()
        val (code, body) = rig.call("PUT", "/api/weather/settings", JSONObject().put("units", "imperial"))
        check(code == 200 && rig.take() == listOf(PREFS_F) + PUSH && net.urls.size == 1, "Units change: new scale, push without a fetch")
        val data = body.getJSONObject("data")
        check(data.getString("temperature_unit") == "°F" && data.getJSONObject("current").getDouble("temperature") == 54.68, "API data in the user's units")
        check(JSONObject(rig.settings.values.getValue("feature.weather.config")).similar(JSONObject("""{"units":"imperial","enabled":true,"location":{"mode":"manual","lat":52.52,"lon":13.4,"name":"Berlin"}}""")), "Settings stored under the feature's key")

        val get = rig.call("GET", "/api/weather").second
        check(get.getJSONObject("status").getString("location_name") == "Berlin" && !get.isNull("data"), "GET returns data, settings and status")

        check(rig.call("PUT", "/api/weather/settings", JSONObject().put("enabled", false)).first == 200 && rig.take().isEmpty(), "Turning off sends nothing")
        check(rig.phone.periodic.isEmpty(), "Turning off cancels the refresh")
        rig.phone.tick()
        rig.receive(Proto.command(10, 3))
        check(rig.take().isEmpty() && net.urls.size == 1, "Off: no fetches, no pushes")
        rig.disconnect()
        rig.connect()
        check(rig.take().isEmpty(), "Off: connect sends nothing")
        rig.call("PUT", "/api/weather/settings", JSONObject().put("enabled", true))
        check(rig.take() == listOf(PREFS_F, LOCATIONS), "Turning on while connected starts the connect flow")

        val perm = rig.call("POST", "/api/weather/permission")
        check(perm.first == 200 && perm.second.getBoolean("requested") && !perm.second.getBoolean("location_available") && asks == 1, "Permission route asks the Activity")
    }

    private fun deviceLocation() {
        val net = Net()
        val rig = rig(net)
        rig.phone.location = PhoneLocation(-33.868819, 151.209295, 2000f, NOW)
        check(!weather(rig).getBoolean("enabled") && rig.call("POST", "/api/weather/refresh").first == 400 && net.urls.isEmpty(), "A fix alone does not turn weather on")
        rig.phone.location = null

        // On while connected, before the permission: the flow runs and waits for a location.
        rig.call("PUT", "/api/weather/settings", JSONObject().put("enabled", true))
        rig.connect()
        rig.receive(Proto.command(10, 5, Proto.num(100, 1)))
        check(rig.take() == listOf(PREFS_C, LOCATIONS) && weather(rig).getString("last_error") == "No location: allow location access or set a manual location", "No location yet: nothing pushed")
        check(rig.phone.periodic.single().first == WeatherFeature.PERIOD_MS, "The refresh still runs")

        // Permission granted: the next GET sees the fix and pushes.
        rig.phone.location = PhoneLocation(-33.868819, 151.209295, 2000f, NOW)
        val got = rig.call("GET", "/api/weather")
        check(got.first == 200 && net.urls.single().contains("latitude=-33.87&longitude=151.21&"), "Device location rounded to 2 decimals")
        val pushed = rig.take()
        val current = pushed.single { it.startsWith("080a1000") }
        check(pushed.size == 4 && current.contains(HealthCapture.hex("Current location".toByteArray())) && current.contains("28011002"), "Pushed once a location exists, current-location flag set")
        check(rig.call("GET", "/api/weather").first == 200 && rig.take().isEmpty() && net.urls.size == 1, "Only once")
        check(rig.logs.none { it.contains("33.8") || it.contains("151.2") }, "Device coordinates never logged")
        rig.phone.location = null
        val off = rig.call("POST", "/api/weather/refresh")
        check(off.first == 400 && off.second.getString("error") == "No location: allow location access or set a manual location", "No fix: refresh is a 400")

        // Access allowed but Location off in the phone's settings: say so, not "allow access".
        rig.phone.locationState = LocationAccess.TURNED_OFF
        val turnedOff = rig.call("POST", "/api/weather/refresh")
        val offText = "Location is off: turn on Location in the phone's settings or set a manual location"
        check(turnedOff.first == 400 && turnedOff.second.getString("error") == offText, "Location off: refresh says to turn it on, got ${turnedOff.second}")
        rig.receive(Proto.command(10, 3))
        check(weather(rig).getString("last_error") == offText, "Location off: the update's error says so too, got ${weather(rig)}")
        rig.take()

        // Turned on again with a fix: a fresh fix pushes, as after a missing location.
        rig.phone.locationState = LocationAccess.ALLOWED
        rig.phone.location = PhoneLocation(-33.868819, 151.209295, 2000f, NOW)
        rig.phone.fixListeners.single()()
        check(weather(rig).isNull("last_error"), "Location back: the error clears, got ${weather(rig)}")
    }

    /** After a reboot Android has no last-known fix until the app opens: that fix pushes as soon as it lands. */
    private fun freshFix() {
        val net = Net()
        val rig = rig(net)
        rig.call("PUT", "/api/weather/settings", JSONObject().put("enabled", true))
        rig.connect()
        rig.receive(Proto.command(10, 5, Proto.num(100, 1)))
        rig.take()
        check(net.urls.isEmpty() && weather(rig).getString("last_error").startsWith("No location"), "No fix: nothing fetched")
        val fix = rig.phone.fixListeners.single()
        rig.phone.location = PhoneLocation(-33.868819, 151.209295, 2000f, NOW)
        fix()
        check(net.urls.single().contains("latitude=-33.87&longitude=151.21&") && rig.take().size == 4, "The fix fetches and pushes")
        check(weather(rig).isNull("last_error"), "The error clears: ${weather(rig)}")
        fix()
        check(net.urls.size == 1 && rig.take().isEmpty(), "The same place again: nothing")
        rig.phone.location = PhoneLocation(-33.9, 151.209295, 2000f, NOW)
        fix()
        check(net.urls.size == 2 && rig.take().size == 4, "A move of a rounded step fetches and pushes")

        rig.call("PUT", "/api/weather/settings", JSONObject().put("enabled", false))
        rig.phone.location = PhoneLocation(-34.5, 151.209295, 2000f, NOW)
        fix()
        check(net.urls.size == 2 && rig.take().isEmpty(), "Weather off: a fix does nothing")
        rig.call("PUT", "/api/weather/settings", JSONObject().put("enabled", true).put("location", JSONObject().put("mode", "manual").put("lat", 52.5168).put("lon", 13.4049).put("name", "Berlin")))
        val manual = net.urls.size
        rig.take()
        fix()
        check(net.urls.size == manual && rig.take().isEmpty(), "Manual location: a fix does nothing")
        rig.features.close()
        check(rig.phone.fixListeners.isEmpty(), "Close cancels the listener")
    }

    /** Opening the app saves Android's last-known fix, then a fresh one seconds later: one rounded place pushes once. */
    private fun coalescedFixes() {
        val net = Net()
        val queue = ArrayList<Runnable>()
        val rig = FeatureRig(WeatherFeature({ true }, net::get, Executor { queue.add(it) }, { NOW }))
        rig.call("PUT", "/api/weather/settings", JSONObject().put("enabled", true))
        rig.connect()
        rig.receive(Proto.command(10, 5, Proto.num(100, 1)))
        queue.removeAt(0).run()
        rig.take()
        val fix = rig.phone.fixListeners.single()
        rig.phone.location = PhoneLocation(-33.868819, 151.209295, 2000f, NOW - 600_000)
        fix()
        rig.phone.location = PhoneLocation(-33.871, 151.2071, 30f, NOW)
        fix()
        check(queue.size == 1, "The second fix of the same rounded place queues nothing: ${queue.size}")
        queue.removeAt(0).run()
        check(net.urls.size == 1 && rig.take().size == 4, "One fetch, one push")

        // A fix that lands while that place's update runs queues nothing either.
        rig.phone.location = PhoneLocation(-33.9, 151.21, 30f, NOW)
        net.answer = {
            fix()
            FIXTURE
        }
        fix()
        queue.removeAt(0).run()
        check(queue.isEmpty() && net.urls.size == 2 && rig.take().size == 4, "Running update: the same place again queues nothing")

        // Another place still updates, and once an update ended its place may queue again.
        rig.phone.location = PhoneLocation(-34.5, 151.21, 30f, NOW)
        net.answer = { throw UnknownHostException("offline") }
        fix()
        queue.removeAt(0).run()
        rig.take()
        fix()
        check(queue.size == 1, "After a failed update the same place queues again")
        net.answer = { FIXTURE }
        queue.removeAt(0).run()
        check(net.urls.size == 4 && rig.take().size == 4, "and pushes")
    }

    private fun disconnects() {
        val net = Net()
        val rig = rig(net)
        berlin(rig)
        rig.connect()
        rig.take()
        rig.disconnect()
        check(rig.watch.delays().isEmpty() && rig.phone.periodic.isEmpty(), "Disconnect cancels the fallback and the refresh")
        rig.receive(Proto.command(10, 5, Proto.num(100, 1)))
        check(rig.take().isEmpty(), "A late answer after disconnect pushes nothing")
        rig.disconnect()
        check(rig.phone.periodic.isEmpty(), "Disconnect is idempotent")

        // The link drops while a fetch is in flight: the result is kept, nothing is sent.
        val slow = Net()
        lateinit var r2: FeatureRig
        slow.answer = { r2.watch.connected = false; FIXTURE }
        r2 = rig(slow)
        berlin(r2)
        r2.connect()
        r2.take()
        r2.phone.tick()
        check(r2.take().isEmpty() && !weather(r2).isNull("last_update"), "No sends after the link dropped mid-fetch")
        r2.features.close()
        check(r2.phone.periodic.isEmpty(), "Close cancels the refresh")

        // Weather is turned off while a fetch is in flight: its result is dropped, not cached or sent.
        val net3 = Net()
        lateinit var r3: FeatureRig
        r3 = rig(net3, now = { NOW + 60_000 })
        berlin(r3)
        r3.connect()
        r3.receive(Proto.command(10, 5, Proto.num(100, 1)))
        r3.take()
        net3.answer = {
            net3.answer = { FIXTURE }
            r3.call("PUT", "/api/weather/settings", JSONObject().put("enabled", false))
            FIXTURE
        }
        r3.phone.tick()
        check(r3.take().isEmpty() && net3.urls.size == 2, "Nothing sent from the overtaken fetch")
        check(weather(r3).getString("last_update") == "2025-10-24T12:01:00+00:00", "The cache keeps the earlier fetch")
    }
}
