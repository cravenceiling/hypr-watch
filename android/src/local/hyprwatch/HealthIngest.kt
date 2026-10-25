package local.hyprwatch

import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Rows derived from parsed health files. Times are unix seconds UTC; `date` is the watch's local
// day (YYYY-MM-DD) from the file id's timezone offset. Null means not measured.

data class MinuteRow(
    val time: Long,
    val tzOffset: Int,
    val steps: Int?,
    val activeCalories: Int?,
    val distanceM: Int?,
    val heartRate: Int?,
    val spo2: Int?,
    val stress: Int?,
) {
    val date: String get() = HealthIngest.localDate(time, tzOffset)
}

data class DailyRow(
    val date: String,
    val time: Long,
    val tzOffset: Int,
    val steps: Int?,
    val activeCalories: Int?,
    val calories: Int?,
    val restingHr: Int?,
    val avgHr: Int?,
    val minHr: Int?,
    val maxHr: Int?,
    val avgSpo2: Int?,
    val minSpo2: Int?,
    val avgStress: Int?,
    val maxStress: Int?,
    val standingHours: Int?,
)

data class SleepStageRow(val start: Long, val kind: String, val minutes: Int)

data class SleepRow(
    val bedTime: Long,
    val wakeupTime: Long,
    val tzOffset: Int,
    val finished: Boolean,
    val quality: Int?,
    val totalMinutes: Int?,
    val deepMinutes: Int?,
    val lightMinutes: Int?,
    val remMinutes: Int?,
    val awakeMinutes: Int?,
    val stages: List<SleepStageRow>,
    /** [HealthIngest.SOURCE_SLEEP] or [HealthIngest.SOURCE_SLEEP_STAGES]. */
    val source: Int,
) {
    /** A night belongs to the day the user woke up. */
    val date: String get() = HealthIngest.localDate(wakeupTime, tzOffset)
}

data class ManualRow(val time: Long, val tzOffset: Int, val kind: String, val value: Int) {
    val date: String get() = HealthIngest.localDate(time, tzOffset)
}

data class WorkoutRow(
    val start: Long,
    val end: Long,
    val tzOffset: Int,
    val sport: String,
    val activeSeconds: Int?,
    val calories: Int?,
    val avgHr: Int?,
    val maxHr: Int?,
    val minHr: Int?,
    val steps: Int?,
    val distanceM: Int?,
) {
    val date: String get() = HealthIngest.localDate(start, tzOffset)
}

data class HealthRows(
    val minutes: List<MinuteRow> = emptyList(),
    val daily: List<DailyRow> = emptyList(),
    val sleep: List<SleepRow> = emptyList(),
    val manual: List<ManualRow> = emptyList(),
    val workouts: List<WorkoutRow> = emptyList(),
)

/** status is parsed, unsupported or invalid. */
data class IngestOutcome(val status: String, val reason: String?)

/** One stored version of a raw file; [seq] orders versions saved in the same second. */
data class FileVersion(val idHex: String, val crc: Long, val firstSeen: Long, val seq: Long)

/** How an id was ingested: [crc] of its newest version, and how many [versions] it had then. */
data class IngestMark(val crc: Long, val parserVersion: Int, val versions: Int)

/** What decides between records of one night; [fileRank] is the source file's [HealthFileId.rank]. */
data class SleepCandidate(val fileRank: Long, val bedTime: Long, val wakeupTime: Long, val finished: Boolean, val source: Int)

/**
 * Turns stored raw files into rows on one background thread. Each file id keeps the rows of its
 * newest version that parses, chosen again whenever a version arrives, because today's files grow
 * under the same id. Files may overlap (same minute, day or night), so the store keeps every file's
 * rows and reads resolve each key by a fixed rule ([HealthFileId.rank], [sleepWinners]); the result
 * depends only on the rows per id, never on ingest order. Raising [PARSER_VERSION] re-ingests every file.
 */
class HealthIngest(private val store: Store, private val parse: (ByteArray) -> HealthParseResult = ::parseHealthFile) {
    interface Store {
        fun versions(): List<FileVersion>

        fun ingested(): Map<String, IngestMark>

        fun raw(idHex: String, crc: Long): ByteArray?

        /** In one transaction: replace the rows from [idHex] with [rows] and record [mark] with [outcome]. */
        fun replace(idHex: String, mark: IngestMark, rows: HealthRows, outcome: IngestOutcome, at: Long)

        fun forgetIngested()
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "health-ingest").apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)

    @Volatile
    private var stopped = false

    /** Called on the ingest thread after a pass, once files were ingested and no other pass is queued. */
    @Volatile
    var onIngested: (() -> Unit)? = null

    // Files ingested since onIngested last ran, so a queued pass that ingests nothing still reports them.
    private val unreported = AtomicInteger()

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastErrorAt: Long? = null

    /** Queues one pass over files not yet ingested; requests made while a pass is queued coalesce. */
    fun request() {
        if (stopped || !queued.compareAndSet(false, true)) return
        submit {
            queued.set(false)
            passed(runNow())
        }
    }

    /** Drops every ingest mark so the next pass parses all raw files again. */
    fun reingestAll() = submit {
        store.forgetIngested()
        passed(runNow())
    }

    private fun passed(ingested: Int) {
        if (unreported.addAndGet(ingested) > 0 && !queued.get()) {
            unreported.set(0)
            onIngested?.invoke()
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (e: Exception) {
                    failed(e, System.currentTimeMillis() / 1000)
                }
            }
        } catch (ignored: RejectedExecutionException) {
        }
    }

    /** One synchronous pass; returns how many files were ingested. Call only from the executor or tests. */
    fun runNow(clock: () -> Long = { System.currentTimeMillis() / 1000 }): Int {
        var n = 0
        for (versions in pending(store.versions(), store.ingested(), PARSER_VERSION)) {
            if (stopped) break
            val newest = versions[0]
            try {
                val (rows, outcome) = latestParsed(versions)
                if (stopped) break
                store.replace(newest.idHex, IngestMark(newest.crc, PARSER_VERSION, versions.size), rows, outcome, clock())
                n++
            } catch (e: Exception) {
                // A storage error may pass, so the id stays pending for the next pass.
                failed(e, clock())
            }
        }
        return n
    }

    /** Rows of the newest version that parses (none if no version does), and how the newest version parsed. */
    private fun latestParsed(versions: List<FileVersion>): Pair<HealthRows, IngestOutcome> {
        var newest: IngestOutcome? = null
        for (v in versions) {
            val raw = store.raw(v.idHex, v.crc) ?: continue
            val result = try {
                parse(raw)
            } catch (e: Exception) {
                HealthParseResult.Invalid("Parser failed: ${e.message ?: e.javaClass.simpleName}")
            }
            val (rows, outcome) = rowsOf(result)
            if (newest == null) newest = outcome
            if (outcome.status == "parsed") return rows to newest
        }
        return HealthRows() to (newest ?: IngestOutcome("invalid", "No stored version could be read"))
    }

    private fun failed(e: Exception, at: Long) {
        lastError = "Ingest failed: ${e.message ?: e.javaClass.simpleName}"
        lastErrorAt = at
    }

    /** For GET /api/status: files waiting for ingest and the last ingest failure. */
    fun status(): JSONObject =
        JSONObject().put("pending", pending(store.versions(), store.ingested(), PARSER_VERSION).size)
            .put("last_error", lastError ?: JSONObject.NULL).put("last_error_at", lastErrorAt ?: JSONObject.NULL)

    fun shutdown() {
        stopped = true
        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val PARSER_VERSION = 3

        const val SOURCE_SLEEP_STAGES = 1
        const val SOURCE_SLEEP = 2

        fun localDate(time: Long, tzOffset: Int): String = LocalDate.ofEpochDay(Math.floorDiv(time + tzOffset, 86_400L)).toString()

        /**
         * Ids with a version stored since they were ingested at [parserVersion], each with its versions
         * newest first. Any new version counts, not only a new newest one: first_seen follows the
         * phone's clock, so a valid version can sort below an invalid newer one and still supply the rows.
         */
        fun pending(versions: List<FileVersion>, ingested: Map<String, IngestMark>, parserVersion: Int): List<List<FileVersion>> =
            versions.groupBy { it.idHex }.toSortedMap().values
                .filter { vs -> ingested[vs[0].idHex].let { it == null || it.versions != vs.size || it.parserVersion != parserVersion } }
                .map { vs -> vs.sortedWith(compareByDescending<FileVersion> { it.firstSeen }.thenByDescending { it.seq }) }

        /**
         * The records that show, one per night. Two records are the same night when they share a bed
         * time or overlap by more than half the shorter one (SLEEP and SLEEP_STAGES can disagree by a
         * few minutes). Among those, a finished night beats one the watch is still counting, then
         * SLEEP beats SLEEP_STAGES for its quality and stage durations, then the higher-ranked file wins.
         */
        fun sleepWinners(candidates: List<SleepCandidate>): List<SleepCandidate> {
            val winners = ArrayList<SleepCandidate>()
            val best = compareByDescending<SleepCandidate> { it.finished }.thenByDescending { it.source }
                .thenByDescending { it.fileRank }.thenBy { it.bedTime }
            for (c in candidates.sortedWith(best)) {
                if (winners.none { sameNight(it, c) }) winners.add(c)
            }
            return winners
        }

        private fun sameNight(a: SleepCandidate, b: SleepCandidate): Boolean {
            val overlap = minOf(a.wakeupTime, b.wakeupTime) - maxOf(a.bedTime, b.bedTime)
            val shorter = minOf(a.wakeupTime - a.bedTime, b.wakeupTime - b.bedTime)
            return a.bedTime == b.bedTime || overlap * 2 > shorter
        }

        fun rowsOf(result: HealthParseResult): Pair<HealthRows, IngestOutcome> = when (result) {
            is HealthParseResult.Unsupported -> HealthRows() to IngestOutcome("unsupported", result.reason)
            is HealthParseResult.Invalid -> HealthRows() to IngestOutcome("invalid", result.reason)
            is HealthParseResult.Parsed -> rowsOf(result.id, result.data) to IngestOutcome("parsed", null)
        }

        private fun rowsOf(id: HealthFileId, data: HealthData): HealthRows = when (data) {
            is HealthData.DailyDetails -> HealthRows(
                minutes = data.samples.mapNotNull { s ->
                    val heart = vital(s.heartRate)
                    val spo2 = vital(s.spo2)
                    if (s.steps == null && s.activeCalories == null && s.distanceMeters == null && heart == null && spo2 == null && s.stress == null) null
                    else MinuteRow(s.time, data.tzOffsetSeconds, s.steps, s.activeCalories, s.distanceMeters, heart, spo2, s.stress)
                },
            )
            is HealthData.Daily -> HealthRows(daily = listOf(dailyRow(data.summary)))
            is HealthData.Sleep -> {
                val source = if (id.knownSubtype == HealthSubtype.ACTIVITY_SLEEP_STAGES) SOURCE_SLEEP_STAGES else SOURCE_SLEEP
                HealthRows(sleep = data.sessions.filter { it.bedTime > 0 && it.wakeupTime >= it.bedTime }.map { sleepRow(it, source) })
            }
            is HealthData.Manual -> HealthRows(manual = data.samples.map { ManualRow(it.time, data.tzOffsetSeconds, it.kind.name.lowercase(), it.value) })
            is HealthData.Workout -> HealthRows(workouts = listOf(workoutRow(data.summary)))
        }

        // The watch writes 0 for a heart rate or SpO2 it did not measure; a stress score of 0 is a reading.
        private fun vital(v: Int?): Int? = v?.takeIf { it > 0 }

        private fun dailyRow(s: DailySummary) = DailyRow(
            date = localDate(s.time, s.tzOffsetSeconds),
            time = s.time,
            tzOffset = s.tzOffsetSeconds,
            steps = s.steps,
            activeCalories = s.activeCalories,
            calories = s.calories,
            restingHr = vital(s.restingHeartRate),
            avgHr = vital(s.avgHeartRate),
            minHr = vital(s.minHeartRate),
            maxHr = vital(s.maxHeartRate),
            avgSpo2 = vital(s.avgSpo2),
            minSpo2 = vital(s.minSpo2),
            avgStress = s.avgStress,
            maxStress = s.maxStress,
            standingHours = s.standingHours?.let { Integer.bitCount(it and 0xFFFFFF) },
        )

        private fun sleepRow(s: SleepSession, source: Int) = SleepRow(
            bedTime = s.bedTime,
            wakeupTime = s.wakeupTime,
            tzOffset = s.tzOffsetSeconds,
            finished = s.finished,
            quality = s.sleepQuality,
            totalMinutes = s.totalMinutes,
            deepMinutes = s.deepMinutes,
            lightMinutes = s.lightMinutes,
            remMinutes = s.remMinutes,
            awakeMinutes = s.awakeMinutes,
            stages = stages(s),
            source = source,
        )

        /** SLEEP_STAGES lists only change times, so a stage lasts until the next one or until wakeup. */
        private fun stages(s: SleepSession): List<SleepStageRow> {
            val sorted = s.stages.sortedBy { it.start }
            return sorted.mapIndexedNotNull { i, st ->
                val kind = when (st.kind) {
                    SleepStageKind.AWAKE, SleepStageKind.NOT_SLEEPING -> "awake"
                    SleepStageKind.LIGHT -> "light"
                    SleepStageKind.DEEP -> "deep"
                    SleepStageKind.REM -> "rem"
                    SleepStageKind.UNKNOWN -> return@mapIndexedNotNull null
                }
                val minutes = st.durationMinutes ?: (((sorted.getOrNull(i + 1)?.start ?: s.wakeupTime) - st.start) / 60).toInt().coerceAtLeast(0)
                SleepStageRow(st.start, kind, minutes)
            }
        }

        private fun workoutRow(w: WorkoutSummary) = WorkoutRow(
            start = w.start,
            end = w.end,
            tzOffset = w.tzOffsetSeconds,
            sport = w.sport.name.lowercase(),
            activeSeconds = w.activeSeconds,
            calories = w.activeCalories,
            avgHr = vital(w.hrAvg),
            maxHr = vital(w.hrMax),
            minHr = vital(w.hrMin),
            steps = w.steps,
            distanceM = w.distanceMeters,
        )
    }
}
