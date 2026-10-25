package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/** The Health Connect record types the export writes, and the write permission each needs. [key] starts its records' ids. */
enum class HcType(val key: String, val permission: String) {
    STEPS("steps", "android.permission.health.WRITE_STEPS"),
    DISTANCE("distance", "android.permission.health.WRITE_DISTANCE"),
    HEART_RATE("heart_rate", "android.permission.health.WRITE_HEART_RATE"),
    RESTING_HEART_RATE("resting_heart_rate", "android.permission.health.WRITE_RESTING_HEART_RATE"),
    OXYGEN_SATURATION("spo2", "android.permission.health.WRITE_OXYGEN_SATURATION"),
    SLEEP("sleep", "android.permission.health.WRITE_SLEEP"),
    EXERCISE("exercise", "android.permission.health.WRITE_EXERCISE"),
    ACTIVE_CALORIES("active_calories", "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED"),
    ;

    companion object {
        fun ofId(id: String): HcType? = entries.firstOrNull { id.startsWith(it.key + ":") }
    }
}

enum class HcSleepStage { AWAKE, LIGHT, DEEP, REM }

/** Health Connect exercise types our sports map to; HealthConnectWriter turns them into the framework constants. */
enum class HcExerciseType {
    OTHER_WORKOUT, RUNNING, RUNNING_TREADMILL, WALKING, HIKING, BIKING, BIKING_STATIONARY, SWIMMING_POOL, SWIMMING_OPEN_WATER,
    ELLIPTICAL, ROWING, ROWING_MACHINE, PADDLING, YOGA, PILATES, STRETCHING, HIGH_INTENSITY_INTERVAL_TRAINING, STRENGTH_TRAINING,
    WEIGHTLIFTING, GYMNASTICS, EXERCISE_CLASS, DANCING, STAIR_CLIMBING, STAIR_CLIMBING_MACHINE, BOXING, MARTIAL_ARTS, FENCING,
    SOCCER, BASKETBALL, VOLLEYBALL, BASEBALL, SOFTBALL, FOOTBALL_AMERICAN, ICE_HOCKEY, TABLE_TENNIS, BADMINTON, TENNIS, CRICKET,
    HANDBALL, SQUASH, GOLF, ICE_SKATING, SKATING, SKIING, SNOWBOARDING, ROCK_CLIMBING, PARAGLIDING, SAILING, SURFING, WATER_POLO,
    SCUBA_DIVING, FRISBEE_DISC,
}

/**
 * One Health Connect record as plain values; HealthConnectWriter builds the framework record from it.
 * [id] is its clientRecordId: the type and the record's time key, so the same data always lands on
 * the same record. Times are unix seconds UTC, [tzOffset] seconds east of UTC.
 */
sealed class HcRecord(val type: HcType) {
    abstract val start: Long

    /** [start] for a record of one instant. */
    abstract val end: Long
    abstract val tzOffset: Int
    val id: String get() = "${type.key}:$start"
}

/** One minute. */
data class HcSteps(override val start: Long, override val end: Long, override val tzOffset: Int, val count: Int) : HcRecord(HcType.STEPS)

/** One minute. */
data class HcDistance(override val start: Long, override val end: Long, override val tzOffset: Int, val meters: Int) : HcRecord(HcType.DISTANCE)

data class HcBpm(val time: Long, val bpm: Int)

/** The readings of one local hour; [start] is the hour, [end] one second after its last reading. */
data class HcHeartRate(override val start: Long, override val end: Long, override val tzOffset: Int, val samples: List<HcBpm>) : HcRecord(HcType.HEART_RATE)

data class HcRestingHeartRate(override val start: Long, override val tzOffset: Int, val bpm: Int) : HcRecord(HcType.RESTING_HEART_RATE) {
    override val end get() = start
}

data class HcOxygen(override val start: Long, override val tzOffset: Int, val percent: Int) : HcRecord(HcType.OXYGEN_SATURATION) {
    override val end get() = start
}

data class HcStage(val start: Long, val end: Long, val stage: HcSleepStage)

data class HcSleep(override val start: Long, override val end: Long, override val tzOffset: Int, val stages: List<HcStage>) : HcRecord(HcType.SLEEP)

data class HcExercise(override val start: Long, override val end: Long, override val tzOffset: Int, val exercise: HcExerciseType, val title: String) :
    HcRecord(HcType.EXERCISE)

/** A workout's active calories, over the workout. */
data class HcActiveCalories(override val start: Long, override val end: Long, override val tzOffset: Int, val kcal: Int) : HcRecord(HcType.ACTIVE_CALORIES)

/** Our health rows to Health Connect records, and which of them a run sends. No Android, no I/O. */
object HealthConnectMap {
    /**
     * The records for one day's rows. Minute steps and distance become one record per minute, heart
     * rate (minute and spot readings) one series per local hour, SpO2 readings one record each, a
     * finished night one sleep session with its stages, a workout one exercise session plus its
     * active calories, and a daily summary's resting heart rate one record. Stress, temperature and
     * the minute calories (whose meaning is not verified) are not exported.
     */
    fun records(minutes: List<MinuteRow>, manual: List<ManualRow>, sleep: List<SleepRow>, workouts: List<WorkoutRow>, daily: List<DailyRow>): List<HcRecord> {
        val out = LinkedHashMap<String, HcRecord>()
        fun add(r: HcRecord) {
            out[r.id] = r
        }
        val hours = TreeMap<Long, TreeMap<Long, Int>>()
        val hourOffset = HashMap<Long, Int>()
        fun heart(time: Long, tz: Int, bpm: Int) {
            if (bpm !in 1..300) return
            val hour = time - Math.floorMod(time + tz, 3600L)
            hourOffset.putIfAbsent(hour, tz)
            hours.getOrPut(hour) { TreeMap() }.putIfAbsent(time, bpm)
        }
        for (m in minutes) {
            m.steps?.takeIf { it in 1..1_000_000 }?.let { add(HcSteps(m.time, m.time + 60, m.tzOffset, it)) }
            m.distanceM?.takeIf { it in 1..1_000_000 }?.let { add(HcDistance(m.time, m.time + 60, m.tzOffset, it)) }
            m.heartRate?.let { heart(m.time, m.tzOffset, it) }
            m.spo2?.takeIf { it in 1..100 }?.let { add(HcOxygen(m.time, m.tzOffset, it)) }
        }
        for (s in manual) {
            when (s.kind) {
                "heart_rate" -> heart(s.time, s.tzOffset, s.value)
                "spo2" -> if (s.value in 1..100) add(HcOxygen(s.time, s.tzOffset, s.value))
            }
        }
        for ((hour, readings) in hours) {
            add(HcHeartRate(hour, readings.lastKey() + 1, hourOffset.getValue(hour), readings.map { (t, bpm) -> HcBpm(t, bpm) }))
        }
        for (d in daily) d.restingHr?.takeIf { it in 1..300 }?.let { add(HcRestingHeartRate(d.time, d.tzOffset, it)) }
        for (s in sleep) if (s.finished && s.wakeupTime > s.bedTime) add(HcSleep(s.bedTime, s.wakeupTime, s.tzOffset, stages(s)))
        for (w in workouts) {
            if (w.end <= w.start) continue
            add(HcExercise(w.start, w.end, w.tzOffset, exercise(w.sport), title(w.sport)))
            w.calories?.takeIf { it > 0 }?.let { add(HcActiveCalories(w.start, w.end, w.tzOffset, it)) }
        }
        return out.values.toList()
    }

    /** Each stage runs for its minutes, cut at the next stage and at wakeup so stages never overlap. */
    private fun stages(s: SleepRow): List<HcStage> {
        val sorted = s.stages.sortedBy { it.start }
        return sorted.mapIndexedNotNull { i, st ->
            val stage = when (st.kind) {
                "awake" -> HcSleepStage.AWAKE
                "light" -> HcSleepStage.LIGHT
                "deep" -> HcSleepStage.DEEP
                "rem" -> HcSleepStage.REM
                else -> return@mapIndexedNotNull null
            }
            val from = maxOf(st.start, s.bedTime)
            val to = minOf(st.start + st.minutes * 60L, sorted.getOrNull(i + 1)?.start ?: s.wakeupTime, s.wakeupTime)
            if (to > from) HcStage(from, to, stage) else null
        }
    }

    /** "jump_roping" -> "Jump roping". */
    fun title(sport: String): String = sport.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun exercise(sport: String): HcExerciseType = when (sport) {
        "outdoor_running", "trail_run", "running" -> HcExerciseType.RUNNING
        "treadmill" -> HcExerciseType.RUNNING_TREADMILL
        "walking", "outdoor_walking", "indoor_walking", "race_walking" -> HcExerciseType.WALKING
        "hiking", "trekking" -> HcExerciseType.HIKING
        "outdoor_cycling", "bmx" -> HcExerciseType.BIKING
        "indoor_cycling", "spinning" -> HcExerciseType.BIKING_STATIONARY
        "pool_swim", "swimming", "artistic_swimming" -> HcExerciseType.SWIMMING_POOL
        "swimming_openwater", "finswimming" -> HcExerciseType.SWIMMING_OPEN_WATER
        "elliptical_trainer", "air_walker" -> HcExerciseType.ELLIPTICAL
        "rowing", "kayaking", "rafting", "dragon_boat" -> HcExerciseType.ROWING
        "rowing_machine" -> HcExerciseType.ROWING_MACHINE
        "stand_up_paddleboarding" -> HcExerciseType.PADDLING
        "yoga" -> HcExerciseType.YOGA
        "pilates" -> HcExerciseType.PILATES
        "stretching", "flexibility" -> HcExerciseType.STRETCHING
        "hiit" -> HcExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING
        "strength_training", "dumbbell", "barbell", "deadlift", "core_training", "upper_body", "lower_body", "abs", "back", "sit_ups",
        "burpee", "functional_training", "cross_training",
        -> HcExerciseType.STRENGTH_TRAINING
        "weightlifting" -> HcExerciseType.WEIGHTLIFTING
        "gymnastics", "horizontal_bar", "parallel_bars", "mass_gymnastics" -> HcExerciseType.GYMNASTICS
        "aerobics", "step_aerobics", "aerobic_combo" -> HcExerciseType.EXERCISE_CLASS
        "square_dance", "belly_dance", "ballet", "street_dance", "zumba", "folk_dance", "jazz_dance", "latin_dance", "hip_hop", "pole_dance",
        "breaking", "ballroom_dance", "modern_dance", "dance",
        -> HcExerciseType.DANCING
        "stairs" -> HcExerciseType.STAIR_CLIMBING
        "stair_climber", "stepper" -> HcExerciseType.STAIR_CLIMBING_MACHINE
        "boxing", "kickboxing", "muay_thai" -> HcExerciseType.BOXING
        "martial_arts", "judo", "taekwondo", "karate", "jujitsu", "kendo", "wrestling", "tai_chi" -> HcExerciseType.MARTIAL_ARTS
        "fencing" -> HcExerciseType.FENCING
        "soccer", "beach_soccer", "futsal" -> HcExerciseType.SOCCER
        "basketball" -> HcExerciseType.BASKETBALL
        "volleyball", "beach_volleyball" -> HcExerciseType.VOLLEYBALL
        "baseball" -> HcExerciseType.BASEBALL
        "softball" -> HcExerciseType.SOFTBALL
        "american_football" -> HcExerciseType.FOOTBALL_AMERICAN
        "hockey", "ice_hockey" -> HcExerciseType.ICE_HOCKEY
        "table_tennis" -> HcExerciseType.TABLE_TENNIS
        "badminton", "shuttlecock" -> HcExerciseType.BADMINTON
        "tennis" -> HcExerciseType.TENNIS
        "cricket" -> HcExerciseType.CRICKET
        "handball" -> HcExerciseType.HANDBALL
        "squash" -> HcExerciseType.SQUASH
        "golf" -> HcExerciseType.GOLF
        "ice_skating", "indoor_ice_skating" -> HcExerciseType.ICE_SKATING
        "roller_skating", "skateboarding" -> HcExerciseType.SKATING
        "skiing" -> HcExerciseType.SKIING
        "snowboarding" -> HcExerciseType.SNOWBOARDING
        "rock_climbing" -> HcExerciseType.ROCK_CLIMBING
        "paragliding" -> HcExerciseType.PARAGLIDING
        "sailing" -> HcExerciseType.SAILING
        "kitesurfing", "flowriding" -> HcExerciseType.SURFING
        "water_polo" -> HcExerciseType.WATER_POLO
        "diving" -> HcExerciseType.SCUBA_DIVING
        "frisbee" -> HcExerciseType.FRISBEE_DISC
        else -> HcExerciseType.OTHER_WORKOUT
    }

    /** CRC-32 of the record's values, to tell whether it changed since it was sent. */
    fun fingerprint(r: HcRecord): Long = CRC32().apply { update(r.toString().toByteArray(Charsets.UTF_8)) }.value

    /**
     * [write]: records not sent with these values before (all of them when [resend]); [delete]: ids
     * sent before that the day no longer has, of allowed types; [sent]: the fingerprints to remember.
     */
    data class Plan(val write: List<HcRecord>, val delete: List<String>, val sent: Map<String, Long>, val unchanged: Int)

    /** Only types in [granted]; [previous] is what the last run sent for this day. */
    fun plan(records: List<HcRecord>, previous: Map<String, Long>, granted: Set<HcType>, resend: Boolean): Plan {
        val sent = LinkedHashMap<String, Long>()
        val write = ArrayList<HcRecord>()
        for (r in records) {
            if (r.type !in granted) continue
            val f = fingerprint(r)
            sent[r.id] = f
            if (resend || previous[r.id] != f) write.add(r)
        }
        val delete = previous.keys.filter { it !in sent && HcType.ofId(it) in granted }
        return Plan(write, delete, sent, sent.size - write.size)
    }
}

/** HealthConnectWriter's messages, apart from Android so host tests check how a failed call ends. They never quote values. */
object HealthConnectErrors {
    const val NOT_ALLOWED = "Hypr Watch is not allowed to write this data to Health Connect"
    const val REJECTED = "Health Connect rejected a record"

    /**
     * A Health Connect call that threw before it started. The framework throws IllegalArgumentException
     * for a record it cannot convert; that one record is rejected. Anything else (a dead binder, a
     * service starting up) may pass, so it is an IOException and the day stays pending.
     */
    fun ofCall(e: RuntimeException): Exception = when (e) {
        is IllegalArgumentException -> IllegalArgumentException(REJECTED)
        is SecurityException -> IOException(NOT_ALLOWED)
        else -> IOException("Health Connect failed; the next export tries again")
    }
}

/**
 * Copies health rows to Android Health Connect, off by default. Each run sends the days that have
 * rows from files ingested since the previous run (every day on the first run, after "export all"
 * or once the user allows another type), newest first, and remembers which days are still to go,
 * so an interrupted run resumes. Records carry stable ids and a version that grows with every run
 * (the run's time, or one more than the last run's when the clock stepped back), so a repeat
 * upserts instead of duplicating. For the last [SENT_DAYS] days it also remembers each sent
 * record's fingerprint: unchanged records are skipped and records the day lost are deleted. Health
 * values are never logged; errors carry only [Writer]'s messages.
 */
class HealthConnectExport(
    private val source: Source,
    private val writer: Writer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Source : HealthApi.Source {
        /** The number of the latest ingest of a file, 0 before the first. Each ingest takes the next number. */
        fun ingestSeq(): Long

        /** Local days with rows from files whose ingest number is above [ingestedAfter]; every day with rows when null. */
        fun exportDates(ingestedAfter: Long?): List<String>

        /** A value in health.db's settings table, where the export keeps its state. */
        fun setting(key: String): String?

        fun setting(key: String, value: String)
    }

    /** Health Connect itself; HealthConnectWriter on the phone. */
    interface Writer {
        /** Null when Health Connect takes records; otherwise why not, for the user. */
        fun unavailable(): String?

        /** The types the user allows the app to write. */
        fun granted(): Set<HcType>

        /**
         * Inserts or updates [records] by id, with clientRecordVersion [version], all or none. Throws
         * IllegalArgumentException when Health Connect rejects a record, any other exception with a
         * message for the user when the write may work later.
         */
        fun write(records: List<HcRecord>, version: Long)

        /** Deletes this app's records with these ids; ids it does not have count as deleted. */
        fun delete(ids: List<String>)

        /** Asks the user to allow the types; false when there is nothing to ask. */
        fun requestPermissions(): Boolean
    }

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "health-connect").apply { isDaemon = true } }
    private val lock = Any()
    private val resendRequested = AtomicBoolean(false)
    private var next: ScheduledFuture<*>? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopped = false

    /** Runs an export after [delayMs], unless one is already due by then; [resend] sends every day again. */
    fun request(delayMs: Long = DELAY_MS, resend: Boolean = false) {
        if (resend) resendRequested.set(true)
        synchronized(lock) {
            if (stopped) return
            val due = next
            if (due != null && due.getDelay(TimeUnit.MILLISECONDS) <= delayMs) return
            due?.cancel(false)
            next = try {
                executor.schedule(
                    {
                        synchronized(lock) { next = null }
                        runNow(resendRequested.getAndSet(false))
                    },
                    delayMs, TimeUnit.MILLISECONDS,
                )
            } catch (ignored: RejectedExecutionException) {
                null
            }
        }
    }

    /** One export now, on the caller's thread: the executor's, or a test's. */
    fun runNow(resend: Boolean = false) {
        if (!state().optBoolean("enabled")) return
        running = true
        val started = clock()
        var written = 0
        var deleted = 0
        var unchanged = 0
        var days = 0
        var rejected = 0
        var error: String? = null
        var pendingLeft = 0
        try {
            writer.unavailable()?.let { throw IllegalStateException(it) }
            val granted = writer.granted()
            check(granted.isNotEmpty()) { "Allow Hypr Watch to write to Health Connect" }
            val s = state()
            val before = strings(s.optJSONArray("granted"))
            val everyDay = resend || !s.has("ingest_seq") || !before.containsAll(granted.map { it.key })
            // Ingest numbers, not times, so a clock that steps hides no file. Read before the days: a file
            // whose ingest commits in between goes with this run and again with the next.
            val ingested = source.ingestSeq()
            val pending = TreeSet(strings(s.optJSONArray("pending")))
            pending.addAll(source.exportDates(if (everyDay) null else s.getLong("ingest_seq")))
            // Health Connect keeps a record whose stored version is higher, so the version never goes back with the clock.
            val version = maxOf(started, s.optLong("version") + 1)
            update {
                it.put("ingest_seq", ingested).put("version", version).put("pending", JSONArray(pending))
                    .put("granted", JSONArray(granted.map { t -> t.key }.sorted()))
            }
            val sent = sentDays()
            for (date in pending.descendingSet().toList()) {
                // Turned off meanwhile: the remaining days stay pending.
                if (!state().optBoolean("enabled")) break
                // A record still running (this minute's steps) goes with a later run, so its day stays
                // pending. Until then it is neither sent nor, if a run sent it before, deleted.
                val (records, running) = recordsOn(date).partition { it.end <= started / 1000 }
                val plan = HealthConnectMap.plan(records, (sent[date] ?: emptyMap()) - running.map { it.id }.toSet(), granted, resend)
                if (plan.delete.isNotEmpty()) {
                    for (ids in plan.delete.chunked(CHUNK)) writer.delete(ids)
                    // Forgotten at once, so a write that fails below never has them deleted twice.
                    sent[date] = (sent[date] ?: emptyMap()) - plan.delete.toSet()
                    saveSent(sent)
                }
                var dayRejected = 0
                for (chunk in plan.write.chunked(CHUNK)) dayRejected += write(chunk, version)
                sent[date] = plan.sent
                while (sent.size > SENT_DAYS) sent.remove(sent.firstKey())
                saveSent(sent)
                if (running.isEmpty()) pending.remove(date)
                update { it.put("pending", JSONArray(pending)) }
                written += plan.write.size - dayRejected
                rejected += dayRejected
                deleted += plan.delete.size
                unchanged += plan.unchanged
                days++
            }
        } catch (e: InterruptedException) {
            // shutdown(): not a failure, and the remaining days stay pending.
            Thread.currentThread().interrupt()
            return
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            pendingLeft = state().optJSONArray("pending")?.length() ?: 0
        } finally {
            running = false
        }
        val at = clock() / 1000
        update {
            it.put("last_run_at", at).put("last_written", written).put("last_deleted", deleted).put("last_unchanged", unchanged).put("last_days", days)
                .put("last_rejected", rejected)
            if (error == null) it.put("last_success_at", at).remove("last_error") else it.put("last_error", error).put("last_error_at", at)
        }
        // Rate limits and busy spells pass, so days left behind are tried again without waiting for a sync.
        if (pendingLeft > 0) request(RETRY_MS)
    }

    /**
     * Writes [records]; when Health Connect rejects the batch, halves it until the rejected records
     * stand alone and leaves those out, so one bad record never holds back a day. Returns how many
     * were left out. They stay fingerprinted, so they are tried again only once their values change.
     */
    private fun write(records: List<HcRecord>, version: Long): Int =
        try {
            writer.write(records, version)
            0
        } catch (e: IllegalArgumentException) {
            if (records.size == 1) 1 else records.chunked((records.size + 1) / 2).sumOf { write(it, version) }
        }

    private fun recordsOn(date: String): List<HcRecord> {
        val minutes = ArrayList<MinuteRow>()
        source.forEachMinute(date, date) { minutes.add(it) }
        return HealthConnectMap.records(
            minutes,
            source.manualSamples(date, date, HealthApi.MAX_SAMPLES),
            source.sleepSessions(date, date, HealthApi.MAX_SLEEP_SESSIONS),
            source.workouts(date, date, HealthApi.MAX_WORKOUTS),
            source.dailySummaries(date, date),
        )
    }

    /** Turns the export on or off; turning it on starts a run. */
    fun setEnabled(on: Boolean): JSONObject {
        if (on) writer.unavailable()?.let { throw IllegalArgumentException(it) }
        update { it.put("enabled", on) }
        if (on) request(0)
        return status()
    }

    /** "Export now": a run right away; [resend] sends every day again. */
    fun exportNow(resend: Boolean): JSONObject {
        require(state().optBoolean("enabled")) { "Turn on the Health Connect export first" }
        writer.unavailable()?.let { throw IllegalArgumentException(it) }
        request(0, resend)
        return status()
    }

    fun requestPermissions(): Boolean = writer.requestPermissions()

    /** For /api/status under health.health_connect. Times are unix seconds or null. */
    fun status(): JSONObject {
        val s = state()
        val unavailable = writer.unavailable()
        val granted = if (unavailable == null) writer.granted() else emptySet()
        val enabled = s.optBoolean("enabled")
        val j = JSONObject().put("enabled", enabled).put("unavailable", unavailable ?: JSONObject.NULL)
            .put("state", if (!enabled) "off" else if (running) "exporting" else "idle")
            .put("allowed", JSONArray(HcType.entries.filter { it in granted }.map { it.key }))
            .put("not_allowed", JSONArray(HcType.entries.filter { it !in granted }.map { it.key }))
            .put("pending_days", s.optJSONArray("pending")?.length() ?: 0)
        for (k in listOf("last_run_at", "last_success_at", "last_error", "last_error_at")) j.put(k, s.opt(k) ?: JSONObject.NULL)
        for (k in listOf("last_days", "last_written", "last_unchanged", "last_deleted", "last_rejected")) j.put(k, s.optInt(k))
        return j
    }

    fun shutdown() {
        synchronized(lock) { stopped = true }
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun state(): JSONObject = source.setting(STATE)?.let(::JSONObject) ?: JSONObject()

    // Routes change `enabled` while a run updates the rest, so every write re-reads under one lock.
    private fun update(change: (JSONObject) -> Unit) = synchronized(this) {
        val s = state()
        change(s)
        source.setting(STATE, s.toString())
    }

    private fun sentDays(): TreeMap<String, Map<String, Long>> {
        val out = TreeMap<String, Map<String, Long>>()
        val j = source.setting(SENT)?.let(::JSONObject) ?: return out
        for (date in j.keys()) {
            val day = j.getJSONObject(date)
            out[date] = day.keys().asSequence().associateWith { day.getLong(it) }
        }
        return out
    }

    private fun saveSent(days: Map<String, Map<String, Long>>) {
        val j = JSONObject()
        for ((date, ids) in days) j.put(date, JSONObject(ids))
        source.setting(SENT, j.toString())
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    companion object {
        /** After an ingest pass, so a sync's files land before the export reads them. */
        const val DELAY_MS = 20_000L
        const val CHUNK = 500
        const val SENT_DAYS = 3
        private const val RETRY_MS = 15 * 60_000L
        private const val STATE = "health_connect"
        private const val SENT = "health_connect_sent"

        /**
         * For a restore, on the backup's copy of health.db: forgets which days were sent, so the next
         * run sends every day, since this phone's Health Connect may hold none of them. Keeps `enabled`,
         * and the version, which must never go back while Health Connect holds records written with it.
         */
        fun forgetProgress(source: Source) {
            val s = source.setting(STATE)?.let(::JSONObject) ?: return
            for (k in listOf("ingest_seq", "granted", "pending")) s.remove(k)
            source.setting(STATE, s.toString())
            source.setting(SENT, "{}")
        }
    }
}
