package local.hyprwatch

import org.json.JSONObject
import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The seam's rules, and the built-in features' routes and hooks, through FeatureRig. */
object FeaturesTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun hex(b: ByteArray): String = HealthCapture.hex(b)

    /** A feature made of [install] and optional hooks. */
    private class Probe(
        override val name: String = "probe",
        private val connect: (FeatureHost) -> Unit = {},
        private val install: (FeatureHost) -> Unit = {},
        private val status: () -> JSONObject? = { null },
    ) : WatchFeature {
        lateinit var host: FeatureHost

        override fun install(host: FeatureHost) {
            this.host = host
            install.invoke(host)
        }

        override fun onConnected() = connect(host)

        override fun status(): JSONObject? = status.invoke()
    }

    private fun refuses(label: String, f: () -> Unit) {
        val failed = try {
            f()
            false
        } catch (e: IllegalArgumentException) {
            true
        } catch (e: IllegalStateException) {
            true
        }
        check(failed, label)
    }

    private fun error(r: Pair<Int, JSONObject>): String = "${r.first} ${r.second.optString("error")}"

    @JvmStatic
    fun main(args: Array<String>) {
        registration()
        routes()
        routeOrder()
        statusGuard()
        operations()
        appList()
        threads()
        health()
        healthConnect()
        notifications()
        println("Watch feature seam and built-in features: $assertions assertions passed")
    }

    private fun registration() {
        refuses("Uppercase name") { FeatureRig(Probe("Bad")) }
        refuses("Core status key") { FeatureRig(Probe("watch")) }
        refuses("Duplicate name") { FeatureRig(Probe("a"), Probe("a")) }
        refuses("Session commands are reserved") { FeatureRig(Probe(install = { it.on(1, 27) {} })) }
        refuses("One handler per command") { FeatureRig(Probe("a", install = { it.on(9, 1) {} }), Probe("b", install = { it.on(9, 1) {} })) }
        refuses("One handler per route") { FeatureRig(Probe(install = { it.route("GET", "/api/x") { JSONObject() }; it.route("GET", "/api/x") { JSONObject() } })) }
        refuses("Routes live under /api/") { FeatureRig(Probe(install = { it.route("GET", "/x") { JSONObject() } })) }
        refuses("Rest match only at the end") { FeatureRig(Probe(install = { it.route("GET", "/api/{a*}/b") { JSONObject() } })) }
        val late = Probe()
        FeatureRig(late)
        refuses("No registration after install") { late.host.on(9, 2) {} }

        val order = ArrayList<String>()
        val rig = FeatureRig(
            Probe("first", connect = { order.add("first"); throw IOException("boom") }),
            Probe("second", connect = { order.add("second") }),
        )
        rig.connect()
        check(order == listOf("first", "second") && rig.logs.contains("first connect hook failed: boom"), "Hooks run in registration order; a failure is logged and the next still runs")
        check(rig.status().length() == 0, "No status from features that return none")
    }

    private fun routes() {
        val rig = FeatureRig(
            Probe(install = { h ->
                h.route("GET", "/api/probe/{id}") { r -> JSONObject().put("id", r.params["id"]).put("query", JSONObject(r.query)) }
                h.route("GET", "/api/probe/{id}/files/{rest*}") { r -> JSONObject().put("rest", r.params["rest"]) }
                h.route("POST", "/api/probe/fail") { throw IOException("Watch is not connected", IOException("Link down")) }
                h.route("POST", "/api/probe/bad") { r -> require(r.body.has("x")) { "Missing x" }; JSONObject() }
            }),
        )
        val ok = rig.call("GET", "/api/probe/7?a=1&b=")
        val q = ok.second.getJSONObject("query")
        check(ok.first == 200 && ok.second.getString("id") == "7" && q.length() == 2 && q.getString("a") == "1" && q.getString("b") == "", "Path parameter and query")
        check(error(rig.call("GET", "/api/probe/7?a=1&a=2")) == "400 Repeated query parameter 'a'", "Query errors are 400s")
        check(rig.call("GET", "/api/probe/7/files/a/b").second.getString("rest") == "a/b", "Rest of the path")
        check(rig.call("GET", "/api/probe/7/files/").second.getString("rest") == "", "Empty rest")
        check(error(rig.call("GET", "/api/probe/7/x")) == "400 Unknown API operation", "Unmatched path")
        check(error(rig.call("GET", "/api/probe/")) == "400 Unknown API operation", "Empty segment does not match a parameter")
        check(error(rig.call("DELETE", "/api/probe/7")) == "400 Unknown API operation", "Unmatched method")
        check(error(rig.call("POST", "/api/probe/fail")) == "503 Link down", "Other failures are 503s with the root cause")
        check(error(rig.call("POST", "/api/probe/bad")) == "400 Missing x", "IllegalArgumentException is a 400")
    }

    /** A literal segment beats a parameter whatever the registration order; same-shaped routes are refused. */
    private fun routeOrder() {
        val rig = FeatureRig(
            Probe(install = { h ->
                h.route("GET", "/api/p/{id}") { r -> JSONObject().put("by", "id").put("id", r.params["id"]) }
                h.route("GET", "/api/p/{rest*}") { JSONObject().put("by", "rest") }
                h.route("GET", "/api/p/sleep") { JSONObject().put("by", "literal") }
                h.route("GET", "/api/{a}/x") { JSONObject().put("by", "a/x") }
                h.route("GET", "/api/q/{b}") { JSONObject().put("by", "q/b") }
            }),
        )
        check(rig.call("GET", "/api/p/sleep").second.getString("by") == "literal", "Literal registered last still wins")
        check(rig.call("GET", "/api/p/7").second.getString("id") == "7", "Parameter beats rest")
        check(rig.call("GET", "/api/p/7/8").second.getString("by") == "rest", "Rest takes the longer path")
        check(rig.call("GET", "/api/q/x").second.getString("by") == "q/b", "The first differing segment decides")
        refuses("Same shape, other parameter name") {
            FeatureRig(Probe(install = { h -> h.route("PUT", "/api/z/{a}") { JSONObject() }; h.route("PUT", "/api/z/{b}") { JSONObject() } }))
        }
        refuses("Same shape across features") {
            FeatureRig(Probe("a", install = { it.route("GET", "/api/z/{a*}") { JSONObject() } }), Probe("b", install = { it.route("GET", "/api/z/{b*}") { JSONObject() } }))
        }
    }

    /** One feature's failing status does not take /api/status down. */
    private fun statusGuard() {
        val rig = FeatureRig(
            Probe("broken", status = { JSONObject("{\"lat\": 52.5") }),
            Probe("fine", status = { JSONObject().put("ok", true) }),
        )
        val s = rig.status()
        check(s.getJSONObject("broken").getString("error") == "Status unavailable (JSONException)" && s.getJSONObject("fine").getBoolean("ok"), "Guarded per feature: $s")
        check(rig.logs.last() == "broken status failed: Status unavailable (JSONException)" && rig.logs.none { it.contains("52.5") }, "Logged without the stored text")
    }

    /** At most one slow operation per feature and two across features; ordinary routes keep answering. */
    private fun operations() {
        val release = CountDownLatch(1)
        val inside = CountDownLatch(2)
        fun slow(name: String) = Probe(name, install = { h ->
            h.route("POST", "/api/$name/slow") {
                h.operation {
                    inside.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    JSONObject().put("done", name)
                }
            }
            h.route("GET", "/api/$name") { JSONObject().put("quick", name) }
        })
        val rig = FeatureRig(slow("a"), slow("b"), slow("c"))
        val results = arrayOfNulls<Pair<Int, JSONObject>>(2)
        val threads = listOf("a", "b").mapIndexed { i, n -> Thread { results[i] = rig.call("POST", "/api/$n/slow") }.also { it.start() } }
        check(inside.await(5, TimeUnit.SECONDS), "Two operations run at once")
        check(error(rig.call("POST", "/api/a/slow")) == "409 Another watch operation is in progress", "A feature's second operation is a 409")
        check(error(rig.call("POST", "/api/c/slow")) == "409 Another watch operation is in progress", "A third feature's operation is a 409 while two run")
        check(rig.call("GET", "/api/c").second.getString("quick") == "c", "Ordinary routes still answer")
        release.countDown()
        threads.forEach { it.join(5_000) }
        check(results.all { it?.first == 200 }, "Both finish")
        check(rig.call("POST", "/api/c/slow").second.getString("done") == "c", "Slots are released")
    }

    /** The list NotesService installs: no name, command or route collides, and the features report a status. */
    private fun appList() {
        val listener = object : NotificationsFeature.ListenerSettings {
            override fun get(): JSONObject = JSONObject()

            override fun put(input: JSONObject): JSONObject = input

            override fun openAccess(): Boolean = false
        }
        val health = HealthFeature(MemHealthStore(), { _, _ -> null }, { JSONObject() })
        val list = appFeatures(health, listener) { false }
        val rig = FeatureRig(*list.toTypedArray())
        val names = list.map { it.name }
        check(names.first() == "device" && names[1] == "health", "Device first (clock), then health: $names")
        val keys = rig.status().keys().asSequence().toSet()
        check(keys == setOf("device", "health", "watch_settings", "live_heart_rate", "data_upload", "weather", "music", "watch_faces", "alarms", "contacts", "quick_replies"), "Every feature but notifications reports: $keys")
        check(names.indexOf("data_upload") < names.indexOf("notifications") && names.indexOf("data_upload") < names.indexOf("watch_faces"), "The uploader's hooks run before its users': $names")
        // A corrupt stored setting costs only its feature's status.
        rig.settings.values["feature.weather.config"] = "{\"enabled\": tru"
        val s = rig.status()
        check(s.getJSONObject("weather").getString("error") == "Status unavailable (JSONException)" && s.getJSONObject("device").has("battery"), "Corrupt weather settings: $s")
        rig.features.close()
    }

    private fun threads() {
        val got = ArrayList<Long>()
        lateinit var host: FeatureHost
        val rig = FeatureRig(
            Probe(
                connect = { h -> h.submit { got.add(Proto.number(h.request(Proto.command(9, 2)), 3, -1)) } },
                install = { h ->
                    host = h
                    h.on(9, 1) { h.request(Proto.command(9, 2)) }
                    h.onAcked { got.add(-1) }
                },
            ),
        )
        rig.answer(9, 2) { Proto.command(9, 2, Proto.num(3, 5)) }
        rig.ack()
        check(got.isEmpty(), "Ack handlers wait for a connection")
        rig.connect()
        check(got == listOf(5L) && rig.take() == listOf(hex(Proto.command(9, 2))), "A connect hook requests through submit")
        rig.receive(Proto.command(9, 1))
        check(rig.logs.last() == "probe command 9/1 failed: request() would block the watch link; use submit()" && rig.sent().isEmpty(), "A handler may not request")
        host.after(1000) { host.request(Proto.command(9, 2)) }
        rig.fire()
        check(rig.logs.last() == "probe timer failed: request() would block the watch link; use submit()", "Nor may a timer task")
        rig.ack()
        check(got == listOf(5L, -1L), "Ack handlers run while connected")

        host.submit { got.add(0) }
        rig.disconnect()
        check(got == listOf(5L, -1L) && rig.logs.last() == "Dropped a probe task: the watch session ended", "A task queued before a disconnect never runs")
        rig.connect()
        rig.answer(9, 2) { Proto.command(9, 2, Proto.num(100, 3)) }
        var rejected: String? = null
        try {
            host.request(Proto.command(9, 2))
        } catch (e: IOException) {
            rejected = e.message
        }
        check(rejected == "Watch rejected command 9/2 with status 3", "A route may request; a watch rejection throws")
    }

    private fun health() {
        val store = MemHealthStore()
        val health = HealthFeature(store, { path, query -> JSONObject().put("path", path).put("query", JSONObject(query)) }, { JSONObject().put("pending", 0) }) { HealthCaptureTest.DAY }
        val rig = FeatureRig(health)
        check(error(rig.call("POST", "/api/health/sync")) == "400 Connect the watch first", "Health sync needs the watch")
        val none = rig.call("GET", "/api/health/profile").second
        check(!none.getBoolean("configured") && none.isNull("profile"), "No profile yet")
        check(error(rig.call("PUT", "/api/health/profile", JSONObject().put("height_cm", 1))) == "400 weight_kg must be a number from 20 to 300", "Profile validation")
        val put = rig.call("PUT", "/api/health/profile", HealthCaptureTest.profileInput())
        check(put.first == 200 && !put.second.getBoolean("sent_to_watch") && store.saved != null, "Saved offline, sent later")
        rig.connect()
        check(rig.take() == listOf(HealthCaptureTest.USER_INFO, HealthCaptureTest.TODAY), "Connect: profile, then today's file list")
        val again = rig.call("POST", "/api/health/sync").second
        check(!again.getBoolean("started") && again.getJSONObject("health").getString("state") == "listing", "A running sync is not restarted")
        check(error(rig.call("GET", "/api/health/files/zz")) == "400 Invalid health file id" && error(rig.call("GET", "/api/health/files/")) == "400 Invalid health file id", "File id validation")
        check(error(rig.call("GET", "/api/health/files/0102030400030f")) == "400 Health file not found", "Unknown file")
        check(
            error(rig.call("GET", "/api/health/files/0102030400030f/15742a68a")) == "400 Health file not found" &&
                error(rig.call("GET", "/api/health/files/0102030400030f/0000015742a68a")) == "400 Invalid health file id",
            "A version key is 8 hex digits, or more for a later version sharing a CRC-32; at most 12",
        )
        val days = rig.call("GET", "/api/health/days?from=2026-01-01&to=2026-01-02").second
        check(days.getString("path") == "/api/health/days" && days.getJSONObject("query").getString("to") == "2026-01-02", "Queries go to HealthApi")
        check(error(rig.call("GET", "/api/health/days?from=a&from=b")) == "400 Repeated query parameter 'from'", "Repeated parameter")
        val status = rig.status().getJSONObject("health")
        check(status.getString("state") == "listing" && status.getLong("files_stored") == 0L && status.getJSONObject("ingest").getInt("pending") == 0, "Health status")
        rig.disconnect()
        check(rig.status().getJSONObject("health").getString("last_error") == "Watch disconnected during health sync", "Disconnect stops the sync")
    }

    /** The export's routes and status through HealthFeature; HealthConnectExportTest covers the export itself. */
    private fun healthConnect() {
        val settings = HashMap<String, String>()
        val source = object : HealthConnectExport.Source {
            override fun ingestSeq() = 0L

            override fun exportDates(ingestedAfter: Long?) = emptyList<String>()

            override fun setting(key: String): String? = settings[key]

            override fun setting(key: String, value: String) {
                settings[key] = value
            }

            override fun dailySummaries(from: String, to: String) = emptyList<DailyRow>()

            override fun forEachMinute(from: String, to: String, block: (MinuteRow) -> Unit) {}

            override fun sleepSessions(from: String, to: String, limit: Int) = emptyList<SleepRow>()

            override fun manualSamples(from: String, to: String, limit: Int) = emptyList<ManualRow>()

            override fun workouts(from: String, to: String, limit: Int) = emptyList<WorkoutRow>()

            override fun parseReport() = emptyList<ReportRow>()
        }
        var unavailable: String? = "Health Connect export needs Android 14 or later"
        var asked = 0
        val writer = object : HealthConnectExport.Writer {
            override fun unavailable() = unavailable

            override fun granted() = setOf(HcType.STEPS)

            override fun write(records: List<HcRecord>, version: Long) {}

            override fun delete(ids: List<String>) {}

            override fun requestPermissions(): Boolean = ++asked > 0
        }
        val export = HealthConnectExport(source, writer)
        val rig = FeatureRig(HealthFeature(MemHealthStore(), { _, _ -> null }, { JSONObject() }, export))
        val off = rig.call("GET", "/api/health/connect").second
        check(!off.getBoolean("enabled") && off.getString("state") == "off" && off.getString("unavailable") == unavailable, "Off by default: $off")
        check(error(rig.call("PUT", "/api/health/connect", JSONObject().put("enabled", true))) == "400 Health Connect export needs Android 14 or later", "Below Android 14 it stays off")
        unavailable = null
        check(error(rig.call("PUT", "/api/health/connect", JSONObject())) == "400 'enabled' must be true or false", "enabled is required")
        check(error(rig.call("POST", "/api/health/connect/export")) == "400 Turn on the Health Connect export first", "Export now needs the export on")
        val on = rig.call("PUT", "/api/health/connect", JSONObject().put("enabled", true)).second
        check(on.getBoolean("enabled") && on.getJSONArray("allowed").toString() == "[\"steps\"]" && on.isNull("unavailable"), "Turned on: $on")
        check(error(rig.call("POST", "/api/health/connect/export", JSONObject().put("all", "yes"))) == "400 'all' must be true or false", "all is a boolean")
        val queued = rig.call("POST", "/api/health/connect/export", JSONObject().put("all", true)).second
        check(queued.getBoolean("queued") && queued.getJSONObject("health_connect").getBoolean("enabled"), "Export now queues a run")
        check(rig.call("POST", "/api/health/connect/permission").second.getBoolean("requested") && asked == 1, "Permission request goes to the phone")
        check(rig.status().getJSONObject("health").getJSONObject("health_connect").getBoolean("enabled"), "Status under health")
        export.shutdown()
    }

    private fun notifications() {
        var filter = JSONObject().put("calls", false)
        var opened = 0
        val listener = object : NotificationsFeature.ListenerSettings {
            override fun get(): JSONObject = JSONObject(filter.toString())

            override fun put(input: JSONObject): JSONObject {
                filter = input
                return get()
            }

            override fun openAccess(): Boolean = opened++ == 0
        }
        val feature = NotificationsFeature(listener, WatchNotifications(NotificationIds()) { ZoneOffset.UTC })
        val rig = FeatureRig(feature)
        check(rig.call("PUT", "/api/notifications", JSONObject().put("calls", true)).second.getBoolean("calls") && rig.call("GET", "/api/notifications").second.getBoolean("calls"), "Settings routes")
        check(rig.call("POST", "/api/notifications/access").second.getBoolean("requested") && !rig.call("POST", "/api/notifications/access").second.getBoolean("requested"), "Access route")

        val note = PhoneNotification("k", "com.a", "A", "Hi", "Yo", 0)
        rig.connect()
        feature.forward(NotificationEvent.Posted(note))
        check(rig.take() == listOf(hex(WatchNotifications.send(note, 1, ZoneOffset.UTC)!!)), "Forwarded while connected")
        rig.watch.refuse = true
        feature.forward(NotificationEvent.Removed("k"))
        rig.watch.refuse = false
        rig.ack()
        check(rig.take() == listOf(hex(WatchNotifications.delete(1, "com.a", "k")!!)), "A refused removal goes out on the next ack")
        var acted: WatchAction? = null
        NotificationBridge.actions = { acted = it }
        feature.forward(NotificationEvent.Posted(note.copy(key = "j")))
        rig.receive(Proto.command(7, WatchNotifications.OPEN_ON_PHONE, Proto.bytes(9, Proto.bytes(2, Proto.num(1, 2)))))
        NotificationBridge.actions = null
        check(acted == WatchAction.Open("j"), "Watch open reaches the listener")
    }
}
