package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.TreeMap

/** How the newest version of one file id was ingested, for GET /health/parse-report. */
data class ReportRow(val idHex: String, val crc: Long, val parserVersion: Int, val status: String, val reason: String?)

/**
 * GET /api/health/{days,series,sleep,samples,workouts,parse-report}. Bad input throws
 * IllegalArgumentException, which NotesService answers with 400.
 */
class HealthApi(private val source: Source) {
    /** Date arguments are inclusive local days (YYYY-MM-DD); rows come back oldest first. */
    interface Source {
        fun dailySummaries(from: String, to: String): List<DailyRow>

        fun forEachMinute(from: String, to: String, block: (MinuteRow) -> Unit)

        /** Nights by wakeup day; at most [limit] rows. */
        fun sleepSessions(from: String, to: String, limit: Int): List<SleepRow>

        fun manualSamples(from: String, to: String, limit: Int): List<ManualRow>

        fun workouts(from: String, to: String, limit: Int): List<WorkoutRow>

        fun parseReport(): List<ReportRow>
    }

    /** [target] is the request path with its query string; null when it is not a health query. */
    fun route(method: String, target: String): JSONObject? {
        val q = target.indexOf('?')
        return if (q < 0) route(method, target, emptyMap()) else route(method, target.substring(0, q), Http.parseQuery(target.substring(q + 1)))
    }

    fun route(method: String, path: String, query: Map<String, String>): JSONObject? {
        if (method != "GET") return null
        return when (path) {
            "/api/health/days" -> days(only(query, RANGE))
            "/api/health/series" -> series(only(query, RANGE + setOf("metric", "bucket")))
            "/api/health/sleep" -> sleep(only(query, RANGE))
            "/api/health/samples" -> samples(only(query, RANGE))
            "/api/health/workouts" -> workouts(only(query, RANGE))
            "/api/health/parse-report" -> {
                only(query, emptySet())
                report()
            }
            else -> null
        }
    }

    private fun days(q: Map<String, String>): JSONObject {
        val (from, to) = range(q)
        val minutes = TreeMap<String, DayAccumulator>()
        source.forEachMinute(from, to) { m -> minutes.getOrPut(m.date) { DayAccumulator() }.add(m) }
        val sleep = source.sleepSessions(from, to, MAX_SLEEP_SESSIONS + 1)
        if (sleep.size > MAX_SLEEP_SESSIONS) throw IllegalArgumentException(tooMany("sleep sessions", MAX_SLEEP_SESSIONS))
        val days = JSONArray()
        for (d in mergeDays(source.dailySummaries(from, to), minutes, sleep)) days.put(d)
        return JSONObject().put("days", days)
    }

    private fun series(q: Map<String, String>): JSONObject {
        val (from, to) = range(q)
        val metric = q["metric"] ?: throw IllegalArgumentException("Missing 'metric' (steps, heart_rate, spo2 or stress)")
        if (metric !in METRICS) throw IllegalArgumentException("Invalid 'metric': use steps, heart_rate, spo2 or stress")
        val bucket = when (val b = q["bucket"]) {
            null -> 15
            "1", "5", "15", "60" -> b.toInt()
            else -> throw IllegalArgumentException("Invalid 'bucket': use 1, 5, 15 or 60 (minutes)")
        }
        val days = LocalDate.parse(to).toEpochDay() - LocalDate.parse(from).toEpochDay() + 1
        val worst = days * 1440 / bucket
        if (worst > MAX_POINTS) {
            throw IllegalArgumentException(
                "$days days at $bucket-minute buckets can return up to $worst points, more than $MAX_POINTS. Use a larger bucket or a shorter range.",
            )
        }
        val s = Series(metric, bucket)
        source.forEachMinute(from, to, s::add)
        return JSONObject().put("metric", metric).put("bucket", bucket).put("points", s.points())
    }

    private fun sleep(q: Map<String, String>): JSONObject {
        val (from, to) = range(q)
        val rows = source.sleepSessions(from, to, MAX_SLEEP_SESSIONS + 1)
        if (rows.size > MAX_SLEEP_SESSIONS) throw IllegalArgumentException(tooMany("sleep sessions", MAX_SLEEP_SESSIONS))
        val a = JSONArray()
        for (s in rows) a.put(sleepJson(s))
        return JSONObject().put("sessions", a)
    }

    private fun samples(q: Map<String, String>): JSONObject {
        val (from, to) = range(q)
        val rows = source.manualSamples(from, to, MAX_SAMPLES + 1)
        if (rows.size > MAX_SAMPLES) throw IllegalArgumentException(tooMany("samples", MAX_SAMPLES))
        val a = JSONArray()
        for (s in rows) a.put(JSONObject().put("t", s.time).put("kind", s.kind).put("value", s.value))
        return JSONObject().put("samples", a)
    }

    private fun workouts(q: Map<String, String>): JSONObject {
        val (from, to) = range(q)
        val rows = source.workouts(from, to, MAX_WORKOUTS + 1)
        if (rows.size > MAX_WORKOUTS) throw IllegalArgumentException(tooMany("workouts", MAX_WORKOUTS))
        val a = JSONArray()
        for (w in rows) {
            a.put(
                JSONObject().put("start", w.start).put("end", w.end).put("tz_offset", w.tzOffset).put("sport", w.sport)
                    .putOrNull("active_seconds", w.activeSeconds).putOrNull("calories", w.calories).putOrNull("avg_hr", w.avgHr).putOrNull("max_hr", w.maxHr)
                    .putOrNull("min_hr", w.minHr).putOrNull("steps", w.steps).putOrNull("distance_m", w.distanceM),
            )
        }
        return JSONObject().put("workouts", a)
    }

    private fun report(): JSONObject {
        val a = JSONArray()
        for (r in source.parseReport()) {
            val id = HealthFileId.fromHex(r.idHex)
            a.put(
                JSONObject().put("id", r.idHex).put("crc32", "%08x".format(r.crc))
                    .putOrNull("type", id?.type?.name?.lowercase())
                    .putOrNull("subtype", id?.let { it.knownSubtype?.name?.lowercase() ?: "0x%02x".format(it.subtype) })
                    .putOrNull("detail", id?.detailType?.name?.lowercase())
                    .putOrNull("version", id?.version)
                    .put("status", r.status).putOrNull("reason", r.reason).put("parser_version", r.parserVersion),
            )
        }
        return JSONObject().put("parser_version", HealthIngest.PARSER_VERSION).put("files", a)
    }

    /** Sums and extremes over one local day of minute samples; every field stays null until a sample has it. */
    class DayAccumulator {
        private var steps: Long? = null
        private var activeCalories: Long? = null
        private var distance: Long? = null
        private val hr = Stat()
        private val spo2 = Stat()
        private val stress = Stat()

        fun add(m: MinuteRow) {
            m.steps?.let { steps = (steps ?: 0) + it }
            m.activeCalories?.let { activeCalories = (activeCalories ?: 0) + it }
            m.distanceM?.let { distance = (distance ?: 0) + it }
            hr.add(m.heartRate)
            spo2.add(m.spo2)
            stress.add(m.stress)
        }

        fun toJson(date: String): JSONObject = dayJson(date).putOrNull("steps", steps).putOrNull("active_calories", activeCalories).putOrNull("distance_m", distance)
            .putOrNull("avg_hr", hr.avg()).putOrNull("min_hr", hr.min).putOrNull("max_hr", hr.max)
            .putOrNull("avg_spo2", spo2.avg()).putOrNull("min_spo2", spo2.min)
            .putOrNull("avg_stress", stress.avg()).putOrNull("max_stress", stress.max)
    }

    private class Stat {
        var sum = 0L
        var count = 0
        var min: Int? = null
        var max: Int? = null

        fun add(v: Int?) {
            if (v == null) return
            sum += v
            count++
            min = minOf(min ?: v, v)
            max = maxOf(max ?: v, v)
        }

        fun avg(): Long? = if (count == 0) null else Math.round(sum.toDouble() / count)
    }

    /** Minute samples bucketed on the watch's local clock: steps summed, other metrics averaged to one decimal. */
    class Series(private val metric: String, bucketMinutes: Int) {
        private val seconds = bucketMinutes * 60L
        private val buckets = TreeMap<Long, LongArray>()

        fun add(m: MinuteRow) {
            val v = when (metric) {
                "steps" -> m.steps
                "heart_rate" -> m.heartRate
                "spo2" -> m.spo2
                else -> m.stress
            } ?: return
            val start = m.time - Math.floorMod(m.time + m.tzOffset, seconds)
            // A timezone change can repeat local hours, so the range alone does not bound the bucket count.
            val acc = buckets.getOrPut(start) {
                if (buckets.size >= MAX_POINTS) throw IllegalArgumentException("More than $MAX_POINTS points in this range. Use a larger bucket or a shorter range.")
                LongArray(2)
            }
            acc[0] += v.toLong()
            acc[1]++
        }

        fun points(): JSONArray {
            val a = JSONArray()
            for ((t, acc) in buckets) {
                val value: Any = if (metric == "steps") acc[0] else Math.round(acc[0] * 10.0 / acc[1]) / 10.0
                a.put(JSONObject().put("t", t).put("value", value))
            }
            return a
        }
    }

    companion object {
        const val MAX_DAYS = 400
        const val MAX_POINTS = 40_000
        const val MAX_SAMPLES = 50_000
        const val MAX_SLEEP_SESSIONS = 2_000
        const val MAX_WORKOUTS = 5_000

        private val RANGE = setOf("from", "to")
        private val METRICS = setOf("steps", "heart_rate", "spo2", "stress")
        private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

        private fun only(q: Map<String, String>, allowed: Set<String>): Map<String, String> {
            val extra = q.keys.firstOrNull { it !in allowed }
            if (extra != null) throw IllegalArgumentException("Unknown query parameter '$extra'")
            return q
        }

        /** Validated inclusive range as ISO dates. */
        fun range(q: Map<String, String>): Pair<String, String> {
            val from = date(q, "from")
            val to = date(q, "to")
            if (to.isBefore(from)) throw IllegalArgumentException("'to' ($to) is before 'from' ($from)")
            val days = to.toEpochDay() - from.toEpochDay() + 1
            if (days > MAX_DAYS) throw IllegalArgumentException("The range covers $days days; at most $MAX_DAYS are allowed")
            return from.toString() to to.toString()
        }

        private fun date(q: Map<String, String>, name: String): LocalDate {
            val v = q[name] ?: throw IllegalArgumentException("Missing '$name' (YYYY-MM-DD)")
            if (!v.matches(DATE)) throw IllegalArgumentException("Invalid '$name': use YYYY-MM-DD")
            return try {
                LocalDate.parse(v)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Invalid '$name': $v is not a calendar date")
            }
        }

        private fun tooMany(what: String, max: Int) = "More than $max $what in this range. Use a shorter range."

        /**
         * One object per day that has any data. Daily summary values win field by field, except the
         * averages; gaps come from minute samples, and sleep totals from the nights that ended that day.
         */
        fun mergeDays(summaries: List<DailyRow>, minutes: Map<String, DayAccumulator>, sleep: List<SleepRow>): List<JSONObject> {
            val byDate = summaries.associateBy { it.date }
            val nights = sleep.groupBy { it.date }
            val dates = (byDate.keys + minutes.keys + nights.keys).toSortedSet()
            return dates.map { date ->
                val j = minutes[date]?.toJson(date) ?: dayJson(date)
                byDate[date]?.let { s ->
                    j.over("steps", s.steps).over("active_calories", s.activeCalories).over("calories", s.calories)
                        .over("resting_hr", s.restingHr).over("min_hr", s.minHr).over("max_hr", s.maxHr)
                        .over("min_spo2", s.minSpo2).over("max_stress", s.maxStress).over("standing_hours", s.standingHours)
                    // On a Redmi Watch 5 Active the summary's "average" HR was below the minute mean on
                    // complete days (once below the day's minimum) and its "average" stress was the last
                    // reading, so the minute mean wins and the summary only fills a day without one.
                    j.under("avg_hr", s.avgHr).under("avg_spo2", s.avgSpo2).under("avg_stress", s.avgStress)
                }
                nights[date]?.let { n ->
                    j.putOrNull("sleep_minutes", sum(n) { it.totalMinutes }).putOrNull("deep_minutes", sum(n) { it.deepMinutes })
                        .putOrNull("light_minutes", sum(n) { it.lightMinutes }).putOrNull("rem_minutes", sum(n) { it.remMinutes })
                        .putOrNull("awake_minutes", sum(n) { it.awakeMinutes })
                }
                j
            }
        }

        private fun sum(rows: List<SleepRow>, f: (SleepRow) -> Int?): Int? = rows.mapNotNull(f).takeIf { it.isNotEmpty() }?.sum()

        private val DAY_FIELDS = listOf(
            "steps", "active_calories", "calories", "distance_m", "resting_hr", "avg_hr", "min_hr", "max_hr", "avg_spo2", "min_spo2",
            "avg_stress", "max_stress", "standing_hours", "sleep_minutes", "deep_minutes", "light_minutes", "rem_minutes", "awake_minutes",
        )

        private fun dayJson(date: String): JSONObject {
            val j = JSONObject().put("date", date)
            for (f in DAY_FIELDS) j.put(f, JSONObject.NULL)
            return j
        }

        fun sleepJson(s: SleepRow): JSONObject {
            val stages = JSONArray()
            for (st in s.stages) stages.put(JSONObject().put("start", st.start).put("kind", st.kind).put("minutes", st.minutes))
            return JSONObject().put("bed_time", s.bedTime).put("wakeup_time", s.wakeupTime).put("tz_offset", s.tzOffset).put("finished", s.finished)
                .putOrNull("total_minutes", s.totalMinutes).putOrNull("deep_minutes", s.deepMinutes).putOrNull("light_minutes", s.lightMinutes)
                .putOrNull("rem_minutes", s.remMinutes).putOrNull("awake_minutes", s.awakeMinutes).putOrNull("quality", s.quality).put("stages", stages)
        }

        /** JSONObject.put drops the key for a Kotlin null; the API spells missing values as JSON null. */
        private fun JSONObject.putOrNull(key: String, v: Any?): JSONObject = put(key, v ?: JSONObject.NULL)

        private fun JSONObject.over(key: String, v: Any?): JSONObject = if (v == null) this else put(key, v)

        private fun JSONObject.under(key: String, v: Any?): JSONObject = if (v == null || !isNull(key)) this else put(key, v)
    }
}
