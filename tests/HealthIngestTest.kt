package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.TreeMap
import java.util.zip.CRC32
import kotlin.system.exitProcess

fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 255) }

/** A store the ingest checks run against: the in-memory reference or HealthTables on real SQLite. */
interface TestHealth : HealthIngest.Store, HealthApi.Source {
    /** Stores [raw] as a new version first seen at [at]. */
    fun add(raw: ByteArray, at: Long)

    fun minuteRows(): Int

    /** raw() throws for this id hex. */
    var failRaw: String?
}

/**
 * In-memory reference for HealthTables' ingest tables: every file keeps its own rows; reads take per
 * key the row of the highest-ranked file, and for sleep the rows HealthIngest.sleepWinners picks.
 */
class MemHealth : TestHealth {
    private class Version(val crc: Long, val firstSeen: Long, val seq: Long, val raw: ByteArray)

    private val files = LinkedHashMap<String, ArrayList<Version>>()
    private var seq = 0L
    private val marks = HashMap<String, IngestMark>()
    private val outcomes = TreeMap<String, Pair<Long, IngestOutcome>>()
    private val rows = TreeMap<Long, HealthRows>()

    override var failRaw: String? = null

    override fun add(raw: ByteArray, at: Long) {
        val hex = hexOf(raw.copyOf(7))
        files.getOrPut(hex) { ArrayList() }.add(Version(crcOf(raw), at, ++seq, raw))
    }

    override fun minuteRows(): Int = rows.values.sumOf { it.minutes.size }

    override fun versions() = files.flatMap { (id, vs) -> vs.map { FileVersion(id, it.crc, it.firstSeen, it.seq) } }

    override fun ingested(): Map<String, IngestMark> = HashMap(marks)

    override fun raw(idHex: String, crc: Long): ByteArray? {
        if (idHex == failRaw) throw IllegalStateException("disk I/O error")
        return files[idHex]?.firstOrNull { it.crc == crc }?.raw
    }

    override fun forgetIngested() = marks.clear()

    override fun replace(idHex: String, mark: IngestMark, rows: HealthRows, outcome: IngestOutcome, at: Long) {
        this.rows[HealthFileId.fromHex(idHex)!!.rank] = rows
        marks[idHex] = mark
        outcomes[idHex] = mark.crc to outcome
    }

    /** Lower-ranked files first, so a later file, or a later row of one file, replaces the same key. */
    private fun <K : Comparable<K>, R> latest(of: (HealthRows) -> List<R>, key: (R) -> K): Collection<R> {
        val out = TreeMap<K, R>()
        for (r in rows.values) for (x in of(r)) out[key(x)] = x
        return out.values
    }

    private fun inRange(date: String, from: String, to: String) = date in from..to

    override fun dailySummaries(from: String, to: String) = latest({ it.daily }, { it.date }).filter { inRange(it.date, from, to) }

    override fun forEachMinute(from: String, to: String, block: (MinuteRow) -> Unit) =
        latest({ it.minutes }, { it.time }).filter { inRange(it.date, from, to) }.forEach(block)

    override fun sleepSessions(from: String, to: String, limit: Int): List<SleepRow> {
        val byKey = HashMap<Pair<Long, Long>, SleepRow>()
        for ((rank, r) in rows) for (s in r.sleep) byKey[rank to s.bedTime] = s
        val winners = HealthIngest.sleepWinners(byKey.map { (k, s) -> SleepCandidate(k.first, s.bedTime, s.wakeupTime, s.finished, s.source) })
        return winners.map { byKey.getValue(it.fileRank to it.bedTime) }.filter { inRange(it.date, from, to) }.sortedBy { it.bedTime }.take(limit)
    }

    override fun manualSamples(from: String, to: String, limit: Int) =
        latest({ it.manual }, { "%012d/%s".format(it.time, it.kind) }).filter { inRange(it.date, from, to) }.take(limit)

    override fun workouts(from: String, to: String, limit: Int) = latest({ it.workouts }, { it.start }).filter { inRange(it.date, from, to) }.take(limit)

    override fun parseReport() = outcomes.map { (id, v) -> ReportRow(id, v.first, marks[id]!!.parserVersion, v.second.status, v.second.reason) }

    companion object {
        fun crcOf(raw: ByteArray): Long = (0 until 4).fold(0L) { a, i -> a or ((raw[raw.size - 4 + i].toLong() and 255) shl (8 * i)) }
    }
}

/** HealthTables on an in-memory SQLite database. */
class SqlHealth(val sql: JdbcSql = JdbcSql()) : TestHealth {
    val tables = HealthTables(sql)

    init {
        tables.create()
    }

    override var failRaw: String? = null

    override fun add(raw: ByteArray, at: Long) {
        check(tables.save(raw.copyOf(7), MemHealth.crcOf(raw), raw, false, at))
    }

    override fun minuteRows(): Int = sql.rows("SELECT COUNT(*) FROM health_minutes").single().toInt()

    override fun versions() = tables.versions()

    override fun ingested() = tables.ingested()

    override fun raw(idHex: String, crc: Long): ByteArray? {
        if (idHex == failRaw) throw IllegalStateException("disk I/O error")
        return tables.raw(idHex, crc)
    }

    override fun replace(idHex: String, mark: IngestMark, rows: HealthRows, outcome: IngestOutcome, at: Long) = tables.replace(idHex, mark, rows, outcome, at)

    override fun forgetIngested() = tables.forgetIngested()

    override fun dailySummaries(from: String, to: String) = tables.dailySummaries(from, to)

    override fun forEachMinute(from: String, to: String, block: (MinuteRow) -> Unit) = tables.forEachMinute(from, to, block)

    override fun sleepSessions(from: String, to: String, limit: Int) = tables.sleepSessions(from, to, limit)

    override fun manualSamples(from: String, to: String, limit: Int) = tables.manualSamples(from, to, limit)

    override fun workouts(from: String, to: String, limit: Int) = tables.workouts(from, to, limit)

    override fun parseReport() = tables.parseReport()
}

object HealthIngestTest {
    private var failures = 0
    private var checks = 0

    /** The store kind the current checks run on, for failure messages. */
    private var kind = ""

    private fun check(condition: Boolean, name: String) {
        checks++
        if (!condition) {
            failures++
            System.err.println("FAIL${if (kind.isEmpty()) "" else " ($kind)"}: $name")
        }
    }

    private fun eq(actual: Any?, expected: Any?, name: String) = check(actual == expected, "$name: expected <$expected>, got <$actual>")

    /** JSON compared by value: sorted keys, numbers as plain decimals (70 == 70.0 == 70L). */
    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keySet().sorted().joinToString(",", "{", "}") { "\"$it\":" + canon(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is Number -> BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun json(actual: Any?, expected: String, name: String) {
        val e = if (expected.trimStart().startsWith("[")) JSONArray(expected) else JSONObject(expected)
        eq(canon(actual), canon(e), name)
    }

    private fun rejects(name: String, expected: String, block: () -> Unit) {
        val message = try {
            block()
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
        eq(message, expected, name)
    }

    private class Le {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v) }
        fun u16(v: Int) = apply { u8(v and 255); u8((v shr 8) and 255) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun file(id: HealthFileId, body: ByteArray): ByteArray {
        val b = id.toBytes() + byteArrayOf(0) + body + ByteArray(4)
        val crc = CRC32()
        crc.update(b, 0, b.size - 4)
        for (i in 0 until 4) b[b.size - 4 + i] = (crc.value ushr (8 * i)).toByte()
        return b
    }

    /** Daily details v3 minute: steps, active calories 5 (group 0x45), distance 70 m, then HR, SpO2, stress raw. */
    private fun minute(steps: Int, hr: Int, spo2: Int, stress: Int) = Le().u16(steps).u8(0x45).u8(7).u16(70).u8(hr).u8(spo2).u8(stress).bytes()

    private fun details(ts: Long, tz: Int, vararg minutes: ByteArray): ByteArray {
        val header = byteArrayOf(0xBA.toByte(), 0x8C.toByte(), 0xC0.toByte(), 0x0C, 0xC0.toByte())
        return file(HealthFileId(ts, tz, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS), minutes.fold(header) { a, b -> a + b })
    }

    private fun summary(time: Long, tz: Int, steps: Int?, activeCalories: Int?, calories: Int?, restingHr: Int?, avgHr: Int?, standing: Int?) = DailySummary(
        time, tz, steps, activeCalories, restingHr, null, null, null, null, avgHr, null, null, null, standing, calories, null,
        null, null, null, null, null, null, null, null, null, null, null, null,
    )

    private fun session(bed: Long, wake: Long, tz: Int, finished: Boolean, total: Int?, stages: List<SleepStage> = emptyList()) =
        SleepSession(bed, wake, tz, finished, null, null, total, if (total == null) null else 100, if (total == null) null else 300, null, null, stages, false)

    // 2026-01-01T00:00Z, and local midnight of that day at UTC+1.
    private const val JAN1_UTC = 1_767_225_600L
    private const val JAN1_PLUS1 = JAN1_UTC - 3600

    @JvmStatic
    fun main(args: Array<String>) {
        localDays()
        pendingVersions()
        rowMapping()
        sleepWinners()
        bucketing()
        dailyAverages()
        validation()
        val stores = listOf<Pair<String, () -> TestHealth>>("memory" to ::MemHealth, "sqlite" to { SqlHealth() })
        val snapshots = stores.map { (kind, make) ->
            this.kind = kind
            ingestAndQuery(make)
            failures(make)
            reorderedVersions(make)
            overlappingFiles(make)
        }
        kind = ""
        eq(snapshots[1], snapshots[0], "SQLite tables answer every query as the reference does")
        HealthTablesTest.run(::eq)
        if (failures > 0) {
            System.err.println("$failures of $checks health ingest checks failed")
            exitProcess(1)
        }
        println("Health ingest: $checks checks passed")
    }

    private fun localDays() {
        eq(HealthIngest.localDate(JAN1_UTC - 1, 0), "2025-12-31", "last UTC second of 2025")
        eq(HealthIngest.localDate(JAN1_UTC - 1, 3600), "2026-01-01", "same instant at UTC+1 is the next day")
        eq(HealthIngest.localDate(JAN1_UTC + 3000, -3600), "2025-12-31", "UTC-1 is still the previous day")
        eq(HealthIngest.localDate(JAN1_PLUS1, 3600), "2026-01-01", "local midnight belongs to its day")
        eq(HealthIngest.localDate(-1, 0), "1969-12-31", "floor division before the epoch")
    }

    private fun pendingVersions() {
        val a = HealthFileId(JAN1_UTC + 86_400, 0, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS).toHex()
        val b = HealthFileId(JAN1_UTC, 0, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS).toHex()
        val c = HealthFileId(JAN1_UTC - 86_400, 0, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS).toHex()
        val d = HealthFileId(JAN1_UTC - 2 * 86_400, 0, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS).toHex()
        val versions = listOf(
            FileVersion(a, 1, 100, 1), FileVersion(a, 2, 200, 2), FileVersion(a, 3, 200, 3),
            FileVersion(b, 7, 100, 4), FileVersion(c, 9, 100, 5),
            // d's newest version is still 8; 6 arrived later with an older clock.
            FileVersion(d, 8, 300, 6), FileVersion(d, 6, 150, 7),
        )
        val marks = mapOf(a to IngestMark(2, 1, 2), b to IngestMark(7, 1, 1), c to IngestMark(9, 0, 1), d to IngestMark(8, 1, 1))
        eq(
            HealthIngest.pending(versions, marks, 1),
            listOf(
                listOf(FileVersion(d, 8, 300, 6), FileVersion(d, 6, 150, 7)),
                listOf(FileVersion(a, 3, 200, 3), FileVersion(a, 2, 200, 2), FileVersion(a, 1, 100, 1)), listOf(FileVersion(c, 9, 100, 5)),
            ),
            "ids with a version stored since their ingest, versions newest first, old parser version redone, by id",
        )
        eq(HealthIngest.pending(versions, emptyMap(), 1).map { it[0].idHex }, listOf(d, b, a, c), "everything pending after forgetting marks")
    }

    private fun rowMapping() {
        val id = HealthFileId(JAN1_PLUS1, 4, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.SUMMARY)
        val (rows, outcome) = HealthIngest.rowsOf(HealthParseResult.Parsed(id, HealthData.Daily(summary(JAN1_PLUS1, 3600, 0, 0, 1800, 0, 0, 0b1011))))
        eq(outcome, IngestOutcome("parsed", null), "summary parsed")
        eq(rows.daily, listOf(DailyRow("2026-01-01", JAN1_PLUS1, 3600, 0, 0, 1800, null, null, null, null, null, null, null, null, 3)),
            "summary row: zero steps kept, zero vitals null, standing hours counted")
        eq(HealthIngest.rowsOf(HealthParseResult.Unsupported(id, "Daily summary v9")), HealthRows() to IngestOutcome("unsupported", "Daily summary v9"), "unsupported")
        eq(HealthIngest.rowsOf(HealthParseResult.Invalid("CRC mismatch", id)), HealthRows() to IngestOutcome("invalid", "CRC mismatch"), "invalid")

        val all = MinuteRow(0, 0, null, null, null, null, null, null)
        val details = HealthData.DailyDetails(
            0,
            listOf(
                MinuteSample(60, null, null, null, null, 5, null, null, MinuteUnknown(null, null, null, null, null, null)),
                MinuteSample(120, 0, null, null, null, null, null, 0, MinuteUnknown(null, null, null, null, null, null)),
            ),
        )
        eq(HealthIngest.rowsOf(HealthParseResult.Parsed(id, details)).first.minutes, listOf(all.copy(time = 120, steps = 0, stress = 0)),
            "minute with only unmapped fields dropped; zero steps and zero stress kept")

        val stagesId = HealthFileId(JAN1_PLUS1, 4, 2, HealthFileType.ACTIVITY, 3, HealthDetailType.DETAILS)
        val bed = JAN1_PLUS1 - 7200
        val s = session(bed, bed + 3600, 3600, true, 60, listOf(
            SleepStage(bed + 600, SleepStageKind.DEEP, 2, null),
            SleepStage(bed, SleepStageKind.LIGHT, 3, null),
            SleepStage(bed + 1800, SleepStageKind.UNKNOWN, 9, null),
            SleepStage(bed + 2400, SleepStageKind.NOT_SLEEPING, 0, null),
        ))
        val row = HealthIngest.rowsOf(HealthParseResult.Parsed(stagesId, HealthData.Sleep(listOf(s)))).first.sleep.single()
        eq(row.stages, listOf(SleepStageRow(bed, "light", 10), SleepStageRow(bed + 600, "deep", 20), SleepStageRow(bed + 2400, "awake", 20)),
            "change-time stages get durations from the next change or wakeup; unknown dropped")
        eq(row.source to row.date, HealthIngest.SOURCE_SLEEP_STAGES to "2025-12-31", "sleep source and wakeup day")
    }

    private fun sleepWinners() {
        fun c(rank: Long, bed: Long, wake: Long, finished: Boolean = true, source: Int = HealthIngest.SOURCE_SLEEP) = SleepCandidate(rank, bed, wake, finished, source)
        val sleep = c(1, 0, 600)
        val stages = c(2, 120, 600, source = HealthIngest.SOURCE_SLEEP_STAGES)
        eq(HealthIngest.sleepWinners(listOf(stages, sleep)), listOf(sleep), "SLEEP beats SLEEP_STAGES for a night whose bed times differ")
        val running = c(3, 0, 300, finished = false)
        eq(HealthIngest.sleepWinners(listOf(running, stages)), listOf(stages), "a finished night beats one still being counted")
        eq(HealthIngest.sleepWinners(listOf(sleep, c(5, 0, 600))), listOf(c(5, 0, 600)), "on a tie the higher-ranked file wins")
        val nap = c(4, 500, 900)
        eq(HealthIngest.sleepWinners(listOf(sleep, nap)), listOf(nap, sleep), "overlap of half the shorter or less is another night")
        eq(HealthIngest.sleepWinners(listOf(c(1, 50, 50), c(2, 50, 50))), listOf(c(2, 50, 50)), "same bed time is the same night")
    }

    private fun bucketing() {
        fun m(t: Long, tz: Int, steps: Int?, hr: Int?) = MinuteRow(t, tz, steps, null, null, hr, null, null)
        val steps = HealthApi.Series("steps", 15)
        val hr = HealthApi.Series("heart_rate", 15)
        val rows = listOf(m(JAN1_UTC, 0, 10, 60), m(JAN1_UTC + 840, 0, 5, 61), m(JAN1_UTC + 900, 0, 0, null), m(JAN1_UTC + 1800, 0, null, 70), m(JAN1_UTC + 2700, 0, null, null))
        rows.forEach { steps.add(it); hr.add(it) }
        json(steps.points(), "[{t:$JAN1_UTC,value:15},{t:${JAN1_UTC + 900},value:0}]", "steps summed, zero kept, empty buckets omitted")
        json(hr.points(), "[{t:$JAN1_UTC,value:60.5},{t:${JAN1_UTC + 1800},value:70}]", "heart rate averaged, empty buckets omitted")

        // At UTC+5:30 an hour bucket starts on the local hour, which is half past in UTC.
        val local = HealthApi.Series("steps", 60)
        local.add(m(JAN1_UTC, 22 * 900, 4, null))
        local.add(m(JAN1_UTC + 1740, 22 * 900, 6, null))
        local.add(m(JAN1_UTC + 1800, 22 * 900, 1, null))
        json(local.points(), "[{t:${JAN1_UTC - 1800},value:10},{t:${JAN1_UTC + 1800},value:1}]", "hour buckets follow the local clock")

        val third = HealthApi.Series("stress", 5)
        for ((i, v) in listOf(10, 10, 11).withIndex()) third.add(MinuteRow(JAN1_UTC + i * 60L, 0, null, null, null, null, null, v))
        json(third.points(), "[{t:$JAN1_UTC,value:10.3}]", "average rounded to one decimal")

        val capped = HealthApi.Series("steps", 1)
        for (i in 0 until HealthApi.MAX_POINTS) capped.add(m(i * 60L, 0, 1, null))
        capped.add(m(0, 0, 1, null))
        rejects("points beyond the cap", "More than 40000 points in this range. Use a larger bucket or a shorter range.") { capped.add(m(-60, 0, 1, null)) }
    }

    /** Real watch summaries: avg HR below the minute mean (here below min HR), avg stress the last reading. */
    private fun dailyAverages() {
        fun summary(date: String, avgHr: Int, avgSpo2: Int, avgStress: Int) =
            DailyRow(date, 0, 0, 9000, null, null, 50, avgHr, 48, 120, avgSpo2, 88, avgStress, 55, null)
        fun minutes(vararg rows: MinuteRow) = HealthApi.DayAccumulator().apply { rows.forEach(::add) }
        val days = HealthApi.mergeDays(
            listOf(summary("2026-10-23", 43, 90, 40), summary("2026-10-24", 59, 95, 33), summary("2026-10-25", 61, 94, 25)),
            mapOf(
                "2026-10-23" to minutes(MinuteRow(0, 0, 10, null, null, 72, 95, 20), MinuteRow(60, 0, 5, null, null, 76, 97, 40)),
                "2026-10-25" to minutes(MinuteRow(0, 0, 7, null, null, null, null, null)),
            ),
            emptyList(),
        )
        fun pick(d: JSONObject) = JSONObject().put("date", d.get("date")).put("resting_hr", d.get("resting_hr")).put("avg_hr", d.get("avg_hr"))
            .put("min_hr", d.get("min_hr")).put("max_hr", d.get("max_hr")).put("avg_spo2", d.get("avg_spo2")).put("min_spo2", d.get("min_spo2"))
            .put("avg_stress", d.get("avg_stress")).put("max_stress", d.get("max_stress"))
        json(
            JSONArray(days.map(::pick)),
            """[{date:"2026-10-23",resting_hr:50,avg_hr:74,min_hr:48,max_hr:120,avg_spo2:96,min_spo2:88,avg_stress:30,max_stress:55},
                {date:"2026-10-24",resting_hr:50,avg_hr:59,min_hr:48,max_hr:120,avg_spo2:95,min_spo2:88,avg_stress:33,max_stress:55},
                {date:"2026-10-25",resting_hr:50,avg_hr:61,min_hr:48,max_hr:120,avg_spo2:94,min_spo2:88,avg_stress:25,max_stress:55}]""",
            "averages from minute samples when the day has them, else the summary; min, max and resting from the summary",
        )
    }

    private fun validation() {
        val api = HealthApi(MemHealth())
        fun get(target: String) = api.route("GET", target)
        rejects("missing from", "Missing 'from' (YYYY-MM-DD)") { get("/api/health/days?to=2026-01-01") }
        rejects("bad date shape", "Invalid 'from': use YYYY-MM-DD") { get("/api/health/days?from=2026-1-01&to=2026-01-01") }
        rejects("non-ASCII digits", "Invalid 'to': use YYYY-MM-DD") { get("/api/health/days?from=2026-01-01&to=2026-01-0٢") }
        rejects("impossible date", "Invalid 'to': 2026-02-30 is not a calendar date") { get("/api/health/days?from=2026-02-01&to=2026-02-30") }
        rejects("reversed", "'to' (2026-01-01) is before 'from' (2026-01-02)") { get("/api/health/sleep?from=2026-01-02&to=2026-01-01") }
        rejects("401 days", "The range covers 401 days; at most 400 are allowed") { get("/api/health/workouts?from=2025-01-01&to=2026-02-05") }
        check(get("/api/health/workouts?from=2025-01-01&to=2026-02-04") != null, "400 days allowed")
        rejects("unknown param", "Unknown query parameter 'limit'") { get("/api/health/samples?from=2026-01-01&to=2026-01-01&limit=5") }
        rejects("repeated param", "Repeated query parameter 'from'") { get("/api/health/days?from=2026-01-01&from=2026-01-02&to=2026-01-03") }
        rejects("bad param name", "Invalid query parameter") { get("/api/health/days?Fr%6Fm=1") }
        rejects("missing metric", "Missing 'metric' (steps, heart_rate, spo2 or stress)") { get("/api/health/series?from=2026-01-01&to=2026-01-01") }
        rejects("bad metric", "Invalid 'metric': use steps, heart_rate, spo2 or stress") { get("/api/health/series?metric=calories&from=2026-01-01&to=2026-01-01") }
        rejects("bad bucket", "Invalid 'bucket': use 1, 5, 15 or 60 (minutes)") { get("/api/health/series?metric=steps&bucket=30&from=2026-01-01&to=2026-01-01") }
        rejects("too many points", "400 days at 1-minute buckets can return up to 576000 points, more than 40000. Use a larger bucket or a shorter range.") {
            get("/api/health/series?metric=steps&bucket=1&from=2025-01-01&to=2026-02-04")
        }
        rejects("too many points at 5", "139 days at 5-minute buckets can return up to 40032 points, more than 40000. Use a larger bucket or a shorter range.") {
            get("/api/health/series?metric=steps&bucket=5&from=2026-01-01&to=2026-05-19")
        }
        json(get("/api/health/series?metric=spo2&from=2025-01-01&to=2026-02-04"), "{metric:spo2,bucket:15,points:[]}", "default bucket 15 covers 400 days")
        json(get("/api/health/series?metric=steps&bucket=1&from=2026-01-01&to=2026-01-27"), "{metric:steps,bucket:1,points:[]}", "27 days at 1 minute fits")
        rejects("parse-report takes no params", "Unknown query parameter 'x'") { get("/api/health/parse-report?x=1") }
        eq(get("/api/health/files"), null, "other health paths fall through")
        eq(api.route("POST", "/api/health/days?from=2026-01-01&to=2026-01-01"), null, "only GET is a health query")
    }

    private fun ingestAndQuery(make: () -> TestHealth) {
        val store = make()
        // Today's file at UTC+1 (timezone 4 x 15 min) as first fetched, then grown by one minute.
        val v1 = details(JAN1_PLUS1, 4, minute(10, 60, 95, 20), minute(5, 0, 0, 255))
        val v2 = details(JAN1_PLUS1, 4, minute(10, 60, 95, 20), minute(5, 0, 0, 255), minute(7, 80, 97, 40))
        val todayId = hexOf(v1.copyOf(7))
        // Fake files whose parse results are given directly; their bytes only carry an id and a CRC.
        val summaryId = HealthFileId(JAN1_PLUS1, 4, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.SUMMARY)
        val sleepId = HealthFileId(JAN1_PLUS1 - 36_000, 4, 2, HealthFileType.ACTIVITY, 8, HealthDetailType.DETAILS)
        val stagesId = HealthFileId(JAN1_PLUS1 - 36_000, 4, 2, HealthFileType.ACTIVITY, 3, HealthDetailType.DETAILS)
        val manualId = HealthFileId(JAN1_PLUS1 + 86_400, 4, 2, HealthFileType.ACTIVITY, 6, HealthDetailType.DETAILS)
        val workoutId = HealthFileId(JAN1_PLUS1 + 36_000, 4, 3, HealthFileType.SPORTS, 8, HealthDetailType.SUMMARY)
        val wake = JAN1_PLUS1 + 6 * 3600 + 1800
        val given = mapOf(
            summaryId to HealthData.Daily(summary(JAN1_PLUS1, 3600, 1000, null, 1800, 0, 43, 0b111)),
            sleepId to HealthData.Sleep(listOf(session(wake - 8 * 3600, wake, 3600, true, 480, listOf(SleepStage(wake - 8 * 3600, SleepStageKind.LIGHT, 1, 300))))),
            stagesId to HealthData.Sleep(listOf(session(wake - 8 * 3600, wake, 3600, true, 470))),
            manualId to HealthData.Manual(3600, listOf(ManualSample(JAN1_PLUS1 + 86_400 + 60, ManualSampleKind.HEART_RATE, 64))),
            workoutId to HealthData.Workout(
                WorkoutSummary(JAN1_PLUS1 + 36_000, JAN1_PLUS1 + 37_800, 3600, WorkoutSport.entries.first(), null, 1800, 150, 200, 120, 160, 0, null, 2500, emptyMap()),
            ),
        )
        val byHex = given.keys.associateBy { it.toHex() }
        val ingest = HealthIngest(store) { raw ->
            byHex[hexOf(raw.copyOf(7))]?.let { HealthParseResult.Parsed(it, given.getValue(it)) } ?: parseHealthFile(raw)
        }
        val unsupported = file(HealthFileId(JAN1_PLUS1, 4, 9, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS), ByteArray(8))
        val broken = file(HealthFileId(JAN1_PLUS1 + 60, 4, 1, HealthFileType.ACTIVITY, 6, HealthDetailType.DETAILS), byteArrayOf(-1, 0, 0, 0, 0, 2, 50)).also { it[it.size - 1]++ }

        store.add(v1, 100)
        for (id in given.keys) store.add(file(id, byteArrayOf(1)), 100)
        store.add(unsupported, 100)
        store.add(broken, 100)
        eq(ingest.runNow { 500 }, 8, "first pass ingests every file")
        eq(ingest.runNow { 600 }, 0, "second pass has nothing to do")
        val api = HealthApi(store)
        json(api.route("GET", "/api/health/series?metric=steps&bucket=60&from=2026-01-01&to=2026-01-01"),
            "{metric:steps,bucket:60,points:[{t:$JAN1_PLUS1,value:15}]}", "v1 steps")

        store.add(v2, 200)
        eq(ingest.runNow { 700 }, 1, "the grown version is ingested")
        eq(store.minuteRows(), 3, "v2 replaced v1's minutes")
        json(api.route("GET", "/api/health/series?metric=steps&bucket=60&from=2026-01-01&to=2026-01-01"),
            "{metric:steps,bucket:60,points:[{t:$JAN1_PLUS1,value:22}]}", "v2 steps")
        json(api.route("GET", "/api/health/series?metric=heart_rate&bucket=1&from=2026-01-01&to=2026-01-01"),
            "{metric:heart_rate,bucket:1,points:[{t:$JAN1_PLUS1,value:60},{t:${JAN1_PLUS1 + 120},value:80}]}", "minutes without heart rate omitted")
        json(api.route("GET", "/api/health/series?metric=heart_rate&from=2025-12-31&to=2025-12-31"),
            "{metric:heart_rate,bucket:15,points:[]}", "UTC 2025-12-31 23:00 is local 2026-01-01")

        json(
            api.route("GET", "/api/health/days?from=2025-12-31&to=2026-01-02"),
            """{days:[{date:"2026-01-01",steps:1000,active_calories:15,calories:1800,distance_m:210,resting_hr:null,avg_hr:70,min_hr:60,max_hr:80,
                avg_spo2:96,min_spo2:95,avg_stress:30,max_stress:40,standing_hours:3,sleep_minutes:480,deep_minutes:100,light_minutes:300,
                rem_minutes:null,awake_minutes:null}]}""",
            "summary wins except its average HR (43), minutes fill gaps, sleep by wakeup day, missing values null",
        )
        json(
            api.route("GET", "/api/health/sleep?from=2026-01-01&to=2026-01-01"),
            """{sessions:[{bed_time:${wake - 8 * 3600},wakeup_time:$wake,tz_offset:3600,finished:true,total_minutes:480,deep_minutes:100,
                light_minutes:300,rem_minutes:null,awake_minutes:null,quality:null,stages:[{start:${wake - 8 * 3600},kind:light,minutes:300}]}]}""",
            "SLEEP kept over SLEEP_STAGES for the same night",
        )
        json(api.route("GET", "/api/health/samples?from=2026-01-02&to=2026-01-02"),
            "{samples:[{t:${JAN1_PLUS1 + 86_460},kind:heart_rate,value:64}]}", "manual samples")
        json(
            api.route("GET", "/api/health/workouts?from=2026-01-01&to=2026-01-01"),
            """{workouts:[{start:${JAN1_PLUS1 + 36_000},end:${JAN1_PLUS1 + 37_800},tz_offset:3600,sport:"${WorkoutSport.entries.first().name.lowercase()}",
                active_seconds:1800,calories:150,avg_hr:120,max_hr:160,min_hr:null,steps:null,distance_m:2500}]}""",
            "workouts: active calories, zero heart rate null",
        )
        json(api.route("GET", "/api/health/days?from=2026-01-03&to=2026-01-04"), "{days:[]}", "days without data are omitted")

        val report = api.route("GET", "/api/health/parse-report")!!
        eq(report.getInt("parser_version"), HealthIngest.PARSER_VERSION, "report parser version")
        val files = (0 until report.getJSONArray("files").length()).map { report.getJSONArray("files").getJSONObject(it) }.associateBy { it.getString("id") }
        json(files[hexOf(unsupported.copyOf(7))],
            """{id:"${hexOf(unsupported.copyOf(7))}",crc32:"%08x",type:activity,subtype:activity_daily,detail:details,version:9,
                status:unsupported,reason:"Daily details v9",parser_version:3}""".format(MemHealth.crcOf(unsupported)), "unsupported file reported")
        eq(files[hexOf(broken.copyOf(7))]?.let { it.getString("status") to it.getString("reason") }, "invalid" to "CRC mismatch", "invalid file reported")
        eq(files[todayId]?.getString("crc32"), "%08x".format(MemHealth.crcOf(v2)), "report shows the ingested version")
        eq(files[todayId]?.isNull("reason"), true, "parsed file has null reason")

        store.forgetIngested()
        eq(ingest.runNow { 800 }, 8, "re-ingest after forgetting marks parses every file again")
        ingest.shutdown()
        eq(store.minuteRows(), 3, "re-ingest keeps the newest version's rows")
    }

    /**
     * Files that overlap (minutes, a day, nights, a manual sample, a workout), some with newer
     * versions, ingested one at a time in several orders, must give what one pass over the final
     * set gives.
     */
    private fun overlappingFiles(make: () -> TestHealth): String {
        val given = HashMap<String, HealthParseResult>()
        fun fake(id: HealthFileId, n: Int, data: HealthData): ByteArray = file(id, byteArrayOf(n.toByte())).also { given[hexOf(it)] = HealthParseResult.Parsed(id, data) }
        val t0 = JAN1_PLUS1
        // Minutes: B starts a minute after A, so it ranks higher and wins the two minutes they share.
        val a1 = details(t0, 4, minute(10, 60, 95, 0), minute(5, 61, 95, 20))
        val a2 = details(t0, 4, minute(10, 60, 95, 0), minute(5, 61, 95, 20), minute(7, 62, 95, 20))
        val b = details(t0 + 60, 4, minute(1, 70, 96, 30), minute(2, 71, 96, 30))
        // One day from two summaries; S2's newer version is corrupt, so its first version stays.
        val s1Id = HealthFileId(t0, 4, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.SUMMARY)
        val s2Id = HealthFileId(t0, 4, 4, HealthFileType.ACTIVITY, 0, HealthDetailType.SUMMARY)
        val s1 = fake(s1Id, 1, HealthData.Daily(summary(t0, 3600, 1000, null, 1500, null, null, null)))
        val s21 = fake(s2Id, 1, HealthData.Daily(summary(t0, 3600, 2000, null, 1800, null, null, null)))
        val s22 = file(s2Id, byteArrayOf(2)).also { it[it.size - 1]++ }
        // Two nights in SLEEP and SLEEP_STAGES with bed times a few minutes apart. SLEEP's second
        // version no longer has the first night, so SLEEP_STAGES' copy shows for it.
        val w1 = t0 + 6 * 3600 + 1800
        val w2 = w1 + 86_400
        val sleepId = HealthFileId(t0 - 36_000, 4, 2, HealthFileType.ACTIVITY, 8, HealthDetailType.DETAILS)
        val stagesId = HealthFileId(t0 - 36_000, 4, 2, HealthFileType.ACTIVITY, 3, HealthDetailType.DETAILS)
        val sleep1 = fake(sleepId, 1, HealthData.Sleep(listOf(session(w1 - 8 * 3600, w1, 3600, true, 480))))
        val sleep2 = fake(sleepId, 2, HealthData.Sleep(listOf(session(w2 - 7 * 3600, w2, 3600, true, 400))))
        val stages = fake(stagesId, 1, HealthData.Sleep(listOf(session(w1 - 8 * 3600 + 120, w1, 3600, true, 470), session(w2 - 7 * 3600 + 180, w2, 3600, true, 390))))
        // The same manual sample and workout in two files each.
        val sample = t0 + 86_400 + 120
        val m1 = fake(HealthFileId(t0 + 86_400, 4, 2, HealthFileType.ACTIVITY, 6, HealthDetailType.DETAILS), 1,
            HealthData.Manual(3600, listOf(ManualSample(sample, ManualSampleKind.HEART_RATE, 64))))
        val m2 = fake(HealthFileId(t0 + 86_460, 4, 2, HealthFileType.ACTIVITY, 6, HealthDetailType.DETAILS), 1,
            HealthData.Manual(3600, listOf(ManualSample(sample, ManualSampleKind.HEART_RATE, 70))))
        fun workout(calories: Int) =
            HealthData.Workout(WorkoutSummary(t0 + 36_000, t0 + 37_800, 3600, WorkoutSport.entries.first(), null, 1800, calories, 120, 100, 140, null, null, null, emptyMap()))
        val wk1 = fake(HealthFileId(t0 + 36_000, 4, 3, HealthFileType.SPORTS, 8, HealthDetailType.SUMMARY), 1, workout(150))
        val wk2 = fake(HealthFileId(t0 + 36_000, 4, 4, HealthFileType.SPORTS, 8, HealthDetailType.SUMMARY), 1, workout(200))
        // Each list holds one id's versions, oldest first.
        val ids = listOf(listOf(a1, a2), listOf(b), listOf(s1), listOf(s21, s22), listOf(sleep1, sleep2), listOf(stages), listOf(m1), listOf(m2), listOf(wk1), listOf(wk2))
        val parse = { raw: ByteArray -> given[hexOf(raw)] ?: parseHealthFile(raw) }

        val fresh = make()
        ids.flatten().forEachIndexed { i, raw -> fresh.add(raw, i.toLong()) }
        eq(HealthIngest(fresh, parse).runNow { 1 }, 10, "one pass over the final set")
        val api = HealthApi(fresh)
        json(api.route("GET", "/api/health/series?metric=steps&bucket=1&from=2026-01-01&to=2026-01-01"),
            "{metric:steps,bucket:1,points:[{t:$t0,value:10},{t:${t0 + 60},value:1},{t:${t0 + 120},value:2}]}", "the higher-ranked file wins shared minutes")
        json(api.route("GET", "/api/health/series?metric=stress&bucket=1&from=2026-01-01&to=2026-01-01"),
            "{metric:stress,bucket:1,points:[{t:$t0,value:0},{t:${t0 + 60},value:30},{t:${t0 + 120},value:30}]}", "stress 0 is a reading")
        json(
            api.route("GET", "/api/health/days?from=2026-01-01&to=2026-01-02"),
            """{days:[{date:"2026-01-01",steps:2000,active_calories:15,calories:1800,distance_m:210,resting_hr:null,avg_hr:67,min_hr:60,max_hr:71,
                avg_spo2:96,min_spo2:95,avg_stress:20,max_stress:30,standing_hours:null,sleep_minutes:470,deep_minutes:100,light_minutes:300,
                rem_minutes:null,awake_minutes:null},
               {date:"2026-01-02",steps:null,active_calories:null,calories:null,distance_m:null,resting_hr:null,avg_hr:null,min_hr:null,max_hr:null,
                avg_spo2:null,min_spo2:null,avg_stress:null,max_stress:null,standing_hours:null,sleep_minutes:400,deep_minutes:100,light_minutes:300,
                rem_minutes:null,awake_minutes:null}]}""",
            "corrupt newest summary keeps its last good version; each night counted once",
        )
        json(api.route("GET", "/api/health/sleep?from=2026-01-01&to=2026-01-02")!!.getJSONArray("sessions").let { a -> JSONArray((0 until a.length()).map { a.getJSONObject(it).getLong("bed_time") }) },
            "[${w1 - 8 * 3600 + 120},${w2 - 7 * 3600}]", "night 1 from SLEEP_STAGES after SLEEP dropped it, night 2 from SLEEP")
        json(api.route("GET", "/api/health/samples?from=2026-01-02&to=2026-01-02"), "{samples:[{t:$sample,kind:heart_rate,value:70}]}", "later manual file wins")
        eq(api.route("GET", "/api/health/workouts?from=2026-01-01&to=2026-01-01")!!.getJSONArray("workouts").getJSONObject(0).getInt("calories"), 200, "later workout file wins")
        val expected = snapshot(fresh)

        // Interleavings that keep each id's versions in order: by id, reversed, and shuffled.
        val orders = mutableListOf(ids.flatten(), ids.reversed().flatten())
        for (seed in 1..8) {
            val rnd = java.util.Random(seed.toLong())
            val left = ids.map { ArrayDeque(it) }.toMutableList()
            val order = ArrayList<ByteArray>()
            while (left.isNotEmpty()) {
                val q = left[rnd.nextInt(left.size)]
                order.add(q.removeFirst())
                if (q.isEmpty()) left.remove(q)
            }
            orders.add(order)
        }
        for ((n, order) in orders.withIndex()) {
            val store = make()
            val ingest = HealthIngest(store, parse)
            order.forEachIndexed { i, raw ->
                store.add(raw, i.toLong())
                ingest.runNow { 1 }
            }
            eq(snapshot(store), expected, "order $n gives the same tables as one pass")
            store.forgetIngested()
            ingest.runNow { 1 }
            eq(snapshot(store), expected, "order $n then re-ingest of everything")
        }
        return expected
    }

    private fun snapshot(store: TestHealth): String {
        val api = HealthApi(store)
        val range = "from=2025-12-30&to=2026-01-05"
        return listOf("days", "sleep", "samples", "workouts").map { "$it?$range" }
            .plus(listOf("steps", "heart_rate", "spo2", "stress").map { "series?metric=$it&bucket=1&$range" })
            .plus("parse-report")
            .joinToString("\n") { canon(api.route("GET", "/api/health/$it")) }
    }

    /** A store error leaves the file pending for the next pass instead of marking it invalid for good. */
    private fun failures(make: () -> TestHealth) {
        val store = make()
        val bad = details(JAN1_PLUS1, 4, minute(3, 60, 95, 20))
        val good = details(JAN1_PLUS1 + 86_400, 4, minute(4, 60, 95, 20))
        store.add(bad, 100)
        store.add(good, 100)
        store.failRaw = hexOf(bad.copyOf(7))
        val ingest = HealthIngest(store)
        eq(ingest.runNow { 500 }, 1, "a file that fails does not stop the pass")
        val report = HealthApi(store).route("GET", "/api/health/parse-report")!!.getJSONArray("files")
        eq(report.length() to report.getJSONObject(0).getString("id"), 1 to hexOf(good.copyOf(7)), "the failed file is not reported")
        json(ingest.status(), """{pending:1,last_error:"Ingest failed: disk I/O error",last_error_at:500}""", "ingest status")
        eq(ingest.runNow { 600 }, 0, "still failing")
        store.failRaw = null
        eq(ingest.runNow { 700 }, 1, "the next pass after the error clears ingests it")
        json(ingest.status(), """{pending:0,last_error:"Ingest failed: disk I/O error",last_error_at:600}""", "nothing pending")

        val throwing = make()
        throwing.add(bad, 100)
        val parser = HealthIngest(throwing) { throw IllegalStateException("index 9") }
        eq(parser.runNow { 800 } to parser.status().getInt("pending"), 1 to 0, "a parser exception is a parse outcome, recorded once")
        val row = HealthApi(throwing).route("GET", "/api/health/parse-report")!!.getJSONArray("files").getJSONObject(0)
        eq(row.getString("status") to row.getString("reason"), "invalid" to "Parser failed: index 9", "and reported as invalid")
        ingest.shutdown()
        parser.shutdown()
    }

    /**
     * first_seen follows the phone's clock. A valid version stored after a rollback sorts below the
     * invalid newest one, yet it supplies the rows a rebuild would use, so it is ingested at once.
     */
    private fun reorderedVersions(make: () -> TestHealth) {
        val store = make()
        val v1 = details(JAN1_PLUS1, 4, minute(10, 60, 95, 20))
        val v2 = details(JAN1_PLUS1, 4, minute(10, 60, 95, 20), minute(5, 61, 95, 20))
        val corrupt = details(JAN1_PLUS1, 4, minute(1, 60, 95, 20), minute(1, 60, 95, 20), minute(1, 60, 95, 20)).also { it[it.size - 1]++ }
        val ingest = HealthIngest(store)
        store.add(v1, 1_000)
        store.add(corrupt, 3_000)
        eq(ingest.runNow { 10 }, 1, "v1 and the corrupt newest version")
        val steps = "/api/health/series?metric=steps&bucket=60&from=2026-01-01&to=2026-01-01"
        json(HealthApi(store).route("GET", steps), "{metric:steps,bucket:60,points:[{t:$JAN1_PLUS1,value:10}]}", "v1 supplies the rows")
        store.add(v2, 2_000)
        eq(ingest.runNow { 20 }, 1, "the version stored after the clock rollback is ingested")
        json(HealthApi(store).route("GET", steps), "{metric:steps,bucket:60,points:[{t:$JAN1_PLUS1,value:15}]}", "v2 supplies the rows")
        val rebuilt = make()
        store.versions().sortedBy { it.seq }.forEach { v -> rebuilt.add(store.raw(v.idHex, v.crc)!!, v.firstSeen) }
        HealthIngest(rebuilt).runNow { 30 }
        eq(snapshot(rebuilt), snapshot(store), "same as a rebuild")
        ingest.shutdown()
    }
}
