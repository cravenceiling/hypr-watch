package local.hyprwatch

import org.json.JSONObject
import java.io.IOException
import kotlin.system.exitProcess

/** HealthConnectMap and HealthConnectExport over HealthTables on real SQLite, with a fake Health Connect. */
object HealthConnectExportTest {
    private var failures = 0
    private var checks = 0

    private fun check(condition: Boolean, name: String) {
        checks++
        if (!condition) {
            failures++
            System.err.println("FAIL: $name")
        }
    }

    private fun eq(actual: Any?, expected: Any?, name: String) = check(actual == expected, "$name: expected <$expected>, got <$actual>")

    // 2026-01-01T00:00Z and the next two days.
    private const val DAY1 = 1_767_225_600L
    private const val DAY2 = DAY1 + 86_400
    private const val DAY3 = DAY2 + 86_400

    private fun minute(time: Long, steps: Int?, distance: Int?, hr: Int?, spo2: Int?, tz: Int = 0) = MinuteRow(time, tz, steps, 3, distance, hr, spo2, 40)

    private fun night(bed: Long, wake: Long, finished: Boolean, vararg stages: SleepStageRow) =
        SleepRow(bed, wake, 0, finished, 80, ((wake - bed) / 60).toInt(), null, null, null, null, stages.toList(), HealthIngest.SOURCE_SLEEP)

    private fun workout(start: Long, end: Long, sport: String, calories: Int?) = WorkoutRow(start, end, 0, sport, 600, calories, 120, 150, 90, 900, 700)

    private fun daily(time: Long, restingHr: Int?) = DailyRow(HealthIngest.localDate(time, 0), time, 0, 8000, 300, 2000, restingHr, 70, 50, 150, 97, 92, 30, 60, 8)

    @JvmStatic
    fun main(args: Array<String>) {
        mapping()
        planning()
        exportDates()
        export()
        clockSteps()
        restoreStartsOver()
        callFailures()
        if (failures > 0) {
            System.err.println("$failures of $checks Health Connect export checks failed")
            exitProcess(1)
        }
        println("Health Connect export: $checks checks passed")
    }

    private fun mapping() {
        val t = DAY1 + 8 * 3600
        val records = HealthConnectMap.records(
            minutes = listOf(
                minute(t, 30, 20, 70, 97),
                minute(t + 60, 0, 0, 72, null),
                minute(t + 120, null, null, 0, 101),
                // 08:59Z is 09:29 at UTC+0:30, in the local hour that starts at 08:30Z.
                minute(t + 3540, 5, null, 80, null, tz = 1800),
            ),
            manual = listOf(
                ManualRow(t + 30, 0, "heart_rate", 75),
                ManualRow(t + 60, 0, "heart_rate", 99),
                ManualRow(t + 90, 0, "spo2", 95),
                ManualRow(t + 100, 0, "stress", 50),
                ManualRow(t + 110, 0, "temperature", 3674),
            ),
            sleep = listOf(
                night(
                    DAY1 - 3600, DAY1 + 6 * 3600, true,
                    SleepStageRow(DAY1 - 3600, "light", 120),
                    // Overlaps the next stage by 30 minutes, which cuts it.
                    SleepStageRow(DAY1 + 3600, "deep", 150),
                    SleepStageRow(DAY1 + 3 * 3600, "rem", 30),
                    SleepStageRow(DAY1 + 4 * 3600, "unknown", 10),
                    // Runs past wakeup, which cuts it.
                    SleepStageRow(DAY1 + 5 * 3600, "awake", 120),
                ),
                night(DAY1 + 13 * 3600, DAY1 + 14 * 3600, false),
            ),
            workouts = listOf(workout(t + 7200, t + 9000, "jump_roping", 120), workout(t + 10_000, t + 10_000, "outdoor_running", 50), workout(t + 20_000, t + 21_000, "outdoor_running", null)),
            daily = listOf(daily(DAY1, 55)),
        )
        eq(
            records.map { it.id },
            listOf(
                "steps:$t", "distance:$t", "spo2:$t", "steps:${t + 3540}", "spo2:${t + 90}", "heart_rate:$t", "heart_rate:${t + 1800}",
                "resting_heart_rate:$DAY1", "sleep:${DAY1 - 3600}", "exercise:${t + 7200}", "active_calories:${t + 7200}", "exercise:${t + 20_000}",
            ),
            "one record per minute with steps, distance or SpO2; one heart rate series per local hour; finished nights and timed workouts only",
        )
        val byId = records.associateBy { it.id }
        eq(byId["steps:$t"], HcSteps(t, t + 60, 0, 30), "a minute's steps")
        eq(byId["distance:$t"], HcDistance(t, t + 60, 0, 20), "a minute's distance")
        eq(byId["spo2:${t + 90}"], HcOxygen(t + 90, 0, 95), "a spot SpO2 reading")
        eq(byId["heart_rate:$t"], HcHeartRate(t, t + 61, 0, listOf(HcBpm(t, 70), HcBpm(t + 30, 75), HcBpm(t + 60, 72))), "minute readings win over a spot reading at the same time")
        eq(byId["heart_rate:${t + 1800}"], HcHeartRate(t + 1800, t + 3541, 1800, listOf(HcBpm(t + 3540, 80))), "the hour is the watch's local hour")
        eq(byId["resting_heart_rate:$DAY1"], HcRestingHeartRate(DAY1, 0, 55), "resting heart rate from the daily summary")
        eq(
            byId["sleep:${DAY1 - 3600}"],
            HcSleep(
                DAY1 - 3600, DAY1 + 6 * 3600, 0,
                listOf(
                    HcStage(DAY1 - 3600, DAY1 + 3600, HcSleepStage.LIGHT),
                    HcStage(DAY1 + 3600, DAY1 + 3 * 3600, HcSleepStage.DEEP),
                    HcStage(DAY1 + 3 * 3600, DAY1 + 3 * 3600 + 1800, HcSleepStage.REM),
                    HcStage(DAY1 + 5 * 3600, DAY1 + 6 * 3600, HcSleepStage.AWAKE),
                ),
            ),
            "stages cut at the next stage and at wakeup; unknown stages left out",
        )
        eq(byId["exercise:${t + 7200}"], HcExercise(t + 7200, t + 9000, 0, HcExerciseType.OTHER_WORKOUT, "Jump roping"), "a sport Health Connect lacks is other, titled")
        eq(byId["active_calories:${t + 7200}"], HcActiveCalories(t + 7200, t + 9000, 0, 120), "a workout's active calories")
        eq(byId["exercise:${t + 20_000}"], HcExercise(t + 20_000, t + 21_000, 0, HcExerciseType.RUNNING, "Outdoor running"), "running")
        eq(HealthConnectMap.exercise("treadmill"), HcExerciseType.RUNNING_TREADMILL, "treadmill")
        eq(HealthConnectMap.exercise("pool_swim"), HcExerciseType.SWIMMING_POOL, "pool")
        eq(HealthConnectMap.exercise("freestyle"), HcExerciseType.OTHER_WORKOUT, "unknown sport")
        eq(HcType.ofId("resting_heart_rate:1"), HcType.RESTING_HEART_RATE, "type from id")
        eq(HcType.ofId("heart_rate:1"), HcType.HEART_RATE, "type from id, not its suffix")
        eq(HcType.entries.map { it.permission }.toSet().size, 8, "one permission per type")
    }

    private fun planning() {
        val steps = HcSteps(DAY1, DAY1 + 60, 0, 30)
        val spo2 = HcOxygen(DAY1, 0, 97)
        val all = HcType.entries.toSet()
        val first = HealthConnectMap.plan(listOf(steps, spo2), emptyMap(), all, false)
        eq(first.write, listOf(steps, spo2), "everything is new")
        eq(first.sent.keys.toList(), listOf("steps:$DAY1", "spo2:$DAY1"), "both remembered")
        val again = HealthConnectMap.plan(listOf(steps, spo2), first.sent, all, false)
        eq(again.write to again.unchanged, emptyList<HcRecord>() to 2, "the same values are not sent again")
        eq(HealthConnectMap.plan(listOf(steps, spo2), first.sent, all, true).write.size, 2, "resend sends them anyway")
        val grown = HealthConnectMap.plan(listOf(steps.copy(count = 31)), first.sent, all, false)
        eq(grown.write, listOf(steps.copy(count = 31)), "a changed value is sent")
        eq(grown.delete, listOf("spo2:$DAY1"), "a record the day lost is deleted")
        val noSpo2 = HealthConnectMap.plan(listOf(steps, spo2), first.sent, all - HcType.OXYGEN_SATURATION, false)
        eq(noSpo2.write.size to noSpo2.delete, 0 to emptyList<String>(), "a type no longer allowed is neither sent nor deleted")
        eq(noSpo2.sent.keys.toList(), listOf("steps:$DAY1"), "and not remembered, so it goes once allowed again")
    }

    private fun id(time: Long, subtype: Int, type: HealthFileType = HealthFileType.ACTIVITY) = HealthFileId(time, 0, 1, type, subtype, HealthDetailType.DETAILS).toHex()

    private fun put(t: HealthTables, idHex: String, rows: HealthRows, at: Long) =
        t.replace(idHex, IngestMark(1, HealthIngest.PARSER_VERSION, 1), rows, IngestOutcome("parsed", null), at)

    private fun exportDates() {
        val t = SqlHealth().tables
        put(t, id(DAY1, 0), HealthRows(minutes = listOf(minute(DAY1 + 60, 10, null, null, null), minute(DAY2 + 60, 10, null, null, null))), 100)
        put(t, id(DAY2, 8), HealthRows(sleep = listOf(night(DAY3 - 3600, DAY3 + 3600, true))), 200)
        eq(t.ingestSeq(), 2L, "each ingest takes the next number")
        eq(t.exportDates(null), listOf("2026-01-01", "2026-01-02", "2026-01-03"), "every day with rows")
        eq(t.exportDates(1), listOf("2026-01-03"), "days of files ingested after the first: a night counts on its wakeup day")
        eq(t.exportDates(0), listOf("2026-01-01", "2026-01-02", "2026-01-03"), "days of files ingested after none")
        eq(t.exportDates(2), emptyList<String>(), "nothing ingested since")
        // The number ignores the clock, and dropping the ingest marks does not start it over.
        put(t, id(DAY1, 0), HealthRows(minutes = listOf(minute(DAY1 + 60, 11, null, null, null), minute(DAY2 + 60, 11, null, null, null))), 50)
        eq(t.exportDates(2), listOf("2026-01-01", "2026-01-02"), "a file ingested at an earlier time still counts")
        t.forgetIngested()
        put(t, id(DAY2, 8), HealthRows(sleep = listOf(night(DAY3 - 3600, DAY3 + 3600, true))), 10)
        eq(t.ingestSeq() to t.exportDates(3), 4L to listOf("2026-01-03"), "numbers keep growing after the marks are dropped")
    }

    /** Health Connect's version check, and which files a run finds, when the phone's clock steps. */
    private fun clockSteps() {
        val t = SqlHealth().tables
        val hc = FakeHealthConnect()
        var now = DAY3 + 86_400
        val export = HealthConnectExport(t, hc) { now * 1000 }
        export.shutdown()
        export.setEnabled(true)
        val file = id(DAY1, 0)
        put(t, file, HealthRows(minutes = listOf(minute(DAY1 + 3600, 30, null, null, null))), now)
        export.runNow()
        val first = now * 1000

        // An hour back, a changed record still outranks the one Health Connect holds.
        now -= 3600
        put(t, file, HealthRows(minutes = listOf(minute(DAY1 + 3600, 31, null, null, null))), now)
        export.runNow(resend = true)
        eq(hc.store["steps:${DAY1 + 3600}"], first + 1 to HcSteps(DAY1 + 3600, DAY1 + 3660, 0, 31), "the version grows when the clock steps back")

        // A run while the clock is a day ahead does not hide the files ingested once it is right again.
        now += 86_400
        export.runNow()
        now -= 86_400
        put(t, file, HealthRows(minutes = listOf(minute(DAY1 + 3600, 32, null, null, null))), now)
        export.runNow()
        eq(hc.store["steps:${DAY1 + 3600}"]?.second, HcSteps(DAY1 + 3600, DAY1 + 3660, 0, 32), "a file ingested after the clock came back is exported")
    }

    /** A restore forgets what was sent, so the new phone's Health Connect gets every day; the version keeps growing. */
    private fun restoreStartsOver() {
        val t = SqlHealth().tables
        val hc = FakeHealthConnect()
        var now = DAY3 + 86_400
        val export = HealthConnectExport(t, hc) { now * 1000 }
        export.shutdown()
        export.setEnabled(true)
        put(t, id(DAY1, 0), HealthRows(minutes = listOf(minute(DAY1 + 3600, 30, null, null, null))), now)
        put(t, id(DAY2, 0), HealthRows(minutes = listOf(minute(DAY2 + 3600, 40, null, null, null))), now)
        export.runNow()
        eq(hc.take().size, 2, "both days sent from the old phone")
        val sentAt = now * 1000

        HealthConnectExport.forgetProgress(t)
        val state = JSONObject(t.setting("health_connect")!!)
        eq(
            listOf(state.getBoolean("enabled"), state.getLong("version"), state.has("ingest_seq"), state.has("granted"), state.has("pending")),
            listOf<Any>(true, sentAt, false, false, false),
            "the restore keeps enabled and the version, and forgets the rest",
        )
        eq(t.setting("health_connect_sent"), "{}", "and every sent fingerprint")

        // The new phone's Health Connect holds nothing, and its clock is a minute behind the old phone's.
        val fresh = FakeHealthConnect()
        now -= 60
        val again = HealthConnectExport(t, fresh) { now * 1000 }
        again.shutdown()
        again.runNow()
        eq(fresh.take(), listOf("steps:${DAY2 + 3600}", "steps:${DAY1 + 3600}"), "every day goes to the new phone, newest first")
        check(fresh.store.values.all { it.first == sentAt + 1 }, "at a version above the last one sent")
    }

    /** A Health Connect call that throws before it starts: only a record the framework cannot take is a rejection. */
    private fun callFailures() {
        fun of(e: RuntimeException) = HealthConnectErrors.ofCall(e).let { it.javaClass.simpleName to it.message }
        eq(of(IllegalStateException("binder died")), "IOException" to "Health Connect failed; the next export tries again", "a failed call is retried later")
        eq(of(SecurityException("denied")), "IOException" to "Hypr Watch is not allowed to write this data to Health Connect", "a refused permission")
        eq(of(IllegalArgumentException("bad record")), "IllegalArgumentException" to "Health Connect rejected a record", "a record the framework cannot convert")
    }

    /** Health Connect's upsert: one record per client id, a write with a lower version ignored. */
    private class FakeHealthConnect : HealthConnectExport.Writer {
        val store = HashMap<String, Pair<Long, HcRecord>>()
        val writes = ArrayList<String>()
        val deletes = ArrayList<String>()
        var granted = HcType.entries.toSet() - HcType.OXYGEN_SATURATION
        var unavailable: String? = null
        var failWrites = 0
        var reject = emptySet<String>()

        override fun unavailable() = unavailable

        override fun granted() = granted

        override fun write(records: List<HcRecord>, version: Long) {
            if (failWrites > 0) {
                failWrites--
                throw IOException("Health Connect is limiting writes for now; the rest goes with the next export")
            }
            if (records.any { it.id in reject }) throw IllegalArgumentException("Health Connect rejected a record")
            for (r in records) {
                check(r.type in granted) { "wrote a type that is not allowed" }
                writes.add(r.id)
                val old = store[r.id]
                if (old == null || old.first <= version) store[r.id] = version to r
            }
        }

        override fun delete(ids: List<String>) {
            deletes.addAll(ids)
            for (id in ids) store.remove(id)
        }

        override fun requestPermissions() = false

        fun take(): List<String> = ArrayList(writes).also { writes.clear() }
    }

    private fun export() {
        val health = SqlHealth()
        val t = health.tables
        val hc = FakeHealthConnect()
        var now = DAY3 + 86_400
        val export = HealthConnectExport(t, hc) { now * 1000 }
        val day1 = id(DAY1, 0)
        val sleepFile = id(DAY2, 8)
        val m1 = minute(DAY1 + 3600, 30, 20, 70, 97)
        put(t, day1, HealthRows(minutes = listOf(m1)), 100)
        put(t, sleepFile, HealthRows(sleep = listOf(night(DAY2 - 3600, DAY2 + 3600, true))), 100)

        export.runNow()
        eq(hc.take(), emptyList<String>(), "off by default: nothing written")
        eq(export.status().getString("state"), "off", "status off")
        check(export.status().isNull("last_run_at"), "and never run")

        // Runs below are on this thread: with the executor stopped, turning the export on queues nothing.
        export.shutdown()
        export.setEnabled(true)
        export.runNow()
        eq(hc.take(), listOf("sleep:${DAY2 - 3600}", "steps:${DAY1 + 3600}", "distance:${DAY1 + 3600}", "heart_rate:${DAY1 + 3600}"), "first run: every day, newest first; SpO2 not allowed")
        val s = export.status()
        eq(
            listOf(s.getString("state"), s.getInt("last_days"), s.getInt("last_written"), s.getInt("pending_days"), s.getLong("last_success_at"), s.isNull("last_error")),
            listOf<Any>("idle", 2, 4, 0, now, true),
            "status after the first run",
        )
        eq(s.getJSONArray("not_allowed").toString(), "[\"spo2\"]", "status lists the type not allowed")

        now += 10
        export.runNow()
        eq(hc.take() to export.status().getInt("last_days"), emptyList<String>() to 0, "nothing ingested since: nothing sent")

        // Today's file grows: the new minute goes, and the hour's heart rate series changes.
        now += 10
        put(t, day1, HealthRows(minutes = listOf(m1, minute(DAY1 + 3660, 12, null, 75, null))), now - 5)
        export.runNow()
        eq(hc.take(), listOf("steps:${DAY1 + 3660}", "heart_rate:${DAY1 + 3600}"), "only new and changed records")
        eq(export.status().getInt("last_unchanged"), 2, "the rest counted unchanged")

        // A better record of the same night starts five minutes later: the old one is deleted.
        now += 10
        put(t, sleepFile, HealthRows(sleep = listOf(night(DAY2 - 3300, DAY2 + 3600, true))), now - 5)
        export.runNow()
        eq(hc.take() to hc.deletes, listOf("sleep:${DAY2 - 3300}") to listOf("sleep:${DAY2 - 3600}"), "the night moves to its new id")

        // A failed write keeps the day for the next run.
        now += 10
        put(t, day1, HealthRows(minutes = listOf(m1, minute(DAY1 + 3660, 12, null, 75, null), minute(DAY1 + 3720, 8, null, null, null))), now - 5)
        hc.failWrites = 1
        export.runNow()
        val failed = export.status()
        eq(
            listOf(failed.getInt("pending_days"), failed.getString("last_error"), failed.getLong("last_error_at")),
            listOf<Any>(1, "Health Connect is limiting writes for now; the rest goes with the next export", now),
            "a failed run reports and keeps its day",
        )
        now += 10
        export.runNow()
        eq(hc.take(), listOf("steps:${DAY1 + 3720}"), "the next run sends it")
        check(export.status().isNull("last_error"), "and clears the error")

        // Allowing SpO2 later sends every day again, once.
        now += 10
        hc.granted = HcType.entries.toSet()
        export.runNow()
        eq(hc.take(), listOf("spo2:${DAY1 + 3600}"), "a newly allowed type goes for every day")
        now += 10
        export.runNow()
        eq(hc.take(), emptyList<String>(), "and not again")

        // Export all again: everything is written once more, and Health Connect still holds one record per id.
        now += 10
        export.runNow(resend = true)
        val expected = listOf("2026-01-01", "2026-01-02").flatMap { d ->
            val minutes = ArrayList<MinuteRow>()
            t.forEachMinute(d, d) { minutes.add(it) }
            HealthConnectMap.records(minutes, t.manualSamples(d, d, 100), t.sleepSessions(d, d, 100), t.workouts(d, d, 100), t.dailySummaries(d, d))
        }
        eq(hc.take().sorted(), expected.map { it.id }.sorted(), "resend writes every record")
        eq(hc.store.mapValues { it.value.second }, expected.associateBy { it.id }, "Health Connect holds exactly the current records")
        check(hc.store.values.all { it.first == now * 1000 }, "each at the latest version")

        // A record still running waits for a later run; a record Health Connect rejects is left out, not its day.
        now += 10
        val bad = now - 150
        hc.reject = setOf("steps:$bad")
        put(t, id(now - 600, 0), HealthRows(minutes = listOf(minute(bad, 5, null, null, null), minute(now - 90, 7, null, null, null), minute(now - 30, 9, null, null, null))), now - 1)
        export.runNow()
        eq(hc.take(), listOf("steps:${now - 90}"), "the rejected record is left out and the running minute waits")
        val rejected = export.status()
        eq(
            listOf(rejected.getInt("last_rejected"), rejected.isNull("last_error"), rejected.getInt("pending_days")),
            listOf<Any>(1, true, 1),
            "a rejected record is counted, not a failed run; the running minute keeps its day pending",
        )
        now += 60
        export.runNow()
        eq(hc.take(), listOf("steps:${now - 90}"), "the minute goes once it ended, with no new ingest; the rejected one is not retried unchanged")
        eq(export.status().getInt("pending_days"), 0, "and the day is done")
        // The clock steps back a minute: the minute just sent looks running again, and stays in Health Connect.
        val sentMinute = "steps:${now - 90}"
        now -= 60
        export.runNow(resend = true)
        check(sentMinute !in hc.deletes && sentMinute in hc.store, "a sent record that looks running again is not deleted")
        now += 60
        hc.take()
        hc.reject = emptySet()

        hc.unavailable = "Health Connect export needs Android 14 or later"
        now += 10
        export.runNow()
        eq(export.status().getString("last_error"), "Health Connect export needs Android 14 or later", "unavailable reported")
        eq(
            runCatching { export.exportNow(false) }.exceptionOrNull()?.message,
            "Health Connect export needs Android 14 or later",
            "export now refused while unavailable",
        )
        hc.unavailable = null
        hc.granted = emptySet()
        export.runNow()
        eq(export.status().getString("last_error"), "Allow Hypr Watch to write to Health Connect", "no type allowed")
        export.setEnabled(false)
        eq(runCatching { export.exportNow(false) }.exceptionOrNull()?.message, "Turn on the Health Connect export first", "export now needs the export on")
        eq(health.sql.rows("SELECT key FROM settings WHERE key LIKE 'health%' ORDER BY key"), listOf("health_connect", "health_connect_sent"), "state lives in two settings")
    }
}
