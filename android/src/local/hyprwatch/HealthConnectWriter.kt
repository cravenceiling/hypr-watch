package local.hyprwatch

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.RecordIdFilter
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord
import android.health.connect.datatypes.Device
import android.health.connect.datatypes.DistanceRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.ExerciseSessionType
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.OxygenSaturationRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.RestingHeartRateRecord
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.units.Energy
import android.health.connect.datatypes.units.Length
import android.health.connect.datatypes.units.Percentage
import android.os.Build
import android.os.OutcomeReceiver
import local.hyprwatch.HealthConnectErrors.NOT_ALLOWED
import local.hyprwatch.HealthConnectErrors.REJECTED
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * [HealthConnectExport.Writer] on the framework Health Connect API (Android 14+). Builds the
 * framework records from [HcRecord]s and waits for each call. Below Android 14 it reports itself
 * unavailable and touches no Health Connect class.
 */
class HealthConnectWriter(private val context: Context) : HealthConnectExport.Writer {
    private fun manager(): HealthConnectManager? =
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(HealthConnectManager::class.java) else null

    override fun unavailable(): String? = when {
        Build.VERSION.SDK_INT < 34 -> "Health Connect export needs Android 14 or later"
        manager() == null -> "Health Connect is not available on this phone"
        else -> null
    }

    override fun granted(): Set<HcType> =
        if (Build.VERSION.SDK_INT < 34) emptySet() else HcType.entries.filter { context.checkSelfPermission(it.permission) == PackageManager.PERMISSION_GRANTED }.toSet()

    override fun write(records: List<HcRecord>, version: Long) {
        val m = manager() ?: throw IOException("Health Connect is not available on this phone")
        // A builder's validation message can quote the value, so it is replaced. A bad zone offset
        // throws DateTimeException, also a RuntimeException.
        val built = try {
            records.map { build(it, version) }
        } catch (e: RuntimeException) {
            throw IllegalArgumentException(REJECTED)
        }
        val error = call { executor, done -> m.insertRecords(built, executor, done.receiver()) } ?: return
        if (error.errorCode == HealthConnectException.ERROR_INVALID_ARGUMENT) throw IllegalArgumentException(REJECTED)
        throw IOException(message(error))
    }

    override fun delete(ids: List<String>) {
        val m = manager() ?: throw IOException("Health Connect is not available on this phone")
        val filters = ids.mapNotNull { id -> HcType.ofId(id)?.let { RecordIdFilter.fromClientRecordId(recordClass(it), id) } }
        val error = call { executor, done -> m.deleteRecords(filters, executor, done.receiver()) } ?: return
        // Health Connect may refuse ids it no longer has (its data cleared); they are gone either way.
        if (error.errorCode != HealthConnectException.ERROR_INVALID_ARGUMENT) throw IOException(message(error))
    }

    override fun requestPermissions(): Boolean = Build.VERSION.SDK_INT >= 34 && NotesActivity.requestHealthConnect(context)

    /**
     * Runs [start] and waits for its OutcomeReceiver; returns Health Connect's error, null on success.
     * A call that throws at once becomes a message without record values (HealthConnectErrors.ofCall).
     */
    private fun call(start: (Executor, Outcome) -> Unit): HealthConnectException? {
        val done = Outcome()
        try {
            start(Executor { it.run() }, done)
        } catch (e: RuntimeException) {
            throw HealthConnectErrors.ofCall(e)
        }
        if (!done.latch.await(60, TimeUnit.SECONDS)) throw IOException("Health Connect did not answer")
        return done.error
    }

    /** One call's outcome; [receiver] has the result type the call takes. */
    private class Outcome {
        val latch = CountDownLatch(1)

        @Volatile
        var error: HealthConnectException? = null

        fun <R> receiver(): OutcomeReceiver<R, HealthConnectException> = object : OutcomeReceiver<R, HealthConnectException> {
            override fun onResult(result: R) {
                latch.countDown()
            }

            override fun onError(e: HealthConnectException) {
                error = e
                latch.countDown()
            }
        }
    }

    private fun message(e: HealthConnectException): String = when (e.errorCode) {
        HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED -> "Health Connect is limiting writes for now; the rest goes with the next export"
        HealthConnectException.ERROR_SECURITY -> NOT_ALLOWED
        HealthConnectException.ERROR_INVALID_ARGUMENT -> REJECTED
        HealthConnectException.ERROR_DATA_SYNC_IN_PROGRESS -> "Health Connect is busy restoring data; try again later"
        else -> "Health Connect failed (error ${e.errorCode})"
    }

    private fun recordClass(t: HcType): Class<out Record> = when (t) {
        HcType.STEPS -> StepsRecord::class.java
        HcType.DISTANCE -> DistanceRecord::class.java
        HcType.HEART_RATE -> HeartRateRecord::class.java
        HcType.RESTING_HEART_RATE -> RestingHeartRateRecord::class.java
        HcType.OXYGEN_SATURATION -> OxygenSaturationRecord::class.java
        HcType.SLEEP -> SleepSessionRecord::class.java
        HcType.EXERCISE -> ExerciseSessionRecord::class.java
        HcType.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord::class.java
    }

    private fun build(r: HcRecord, version: Long): Record {
        val method = if (r is HcExercise || r is HcActiveCalories) Metadata.RECORDING_METHOD_ACTIVELY_RECORDED else Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED
        val meta = Metadata.Builder().setClientRecordId(r.id).setClientRecordVersion(version).setDevice(WATCH).setRecordingMethod(method).build()
        val zone = ZoneOffset.ofTotalSeconds(r.tzOffset)
        fun at(s: Long) = Instant.ofEpochSecond(s)
        return when (r) {
            is HcSteps -> StepsRecord.Builder(meta, at(r.start), at(r.end), r.count.toLong()).setStartZoneOffset(zone).setEndZoneOffset(zone).build()
            is HcDistance -> DistanceRecord.Builder(meta, at(r.start), at(r.end), Length.fromMeters(r.meters.toDouble()))
                .setStartZoneOffset(zone).setEndZoneOffset(zone).build()
            is HcHeartRate -> HeartRateRecord.Builder(meta, at(r.start), at(r.end), r.samples.map { HeartRateRecord.HeartRateSample(it.bpm.toLong(), at(it.time)) })
                .setStartZoneOffset(zone).setEndZoneOffset(zone).build()
            is HcRestingHeartRate -> RestingHeartRateRecord.Builder(meta, at(r.start), r.bpm.toLong()).setZoneOffset(zone).build()
            is HcOxygen -> OxygenSaturationRecord.Builder(meta, at(r.start), Percentage.fromValue(r.percent.toDouble())).setZoneOffset(zone).build()
            is HcSleep -> SleepSessionRecord.Builder(meta, at(r.start), at(r.end))
                .setStages(r.stages.map { SleepSessionRecord.Stage(at(it.start), at(it.end), stage(it.stage)) })
                .setStartZoneOffset(zone).setEndZoneOffset(zone).build()
            is HcExercise -> ExerciseSessionRecord.Builder(meta, at(r.start), at(r.end), exercise(r.exercise)).setTitle(r.title)
                .setStartZoneOffset(zone).setEndZoneOffset(zone).build()
            // The framework's Energy counts small calories.
            is HcActiveCalories -> ActiveCaloriesBurnedRecord.Builder(meta, at(r.start), at(r.end), Energy.fromCalories(r.kcal * 1000.0))
                .setStartZoneOffset(zone).setEndZoneOffset(zone).build()
        }
    }

    private fun stage(s: HcSleepStage): Int = when (s) {
        HcSleepStage.AWAKE -> SleepSessionRecord.StageType.STAGE_TYPE_AWAKE
        HcSleepStage.LIGHT -> SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT
        HcSleepStage.DEEP -> SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP
        HcSleepStage.REM -> SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_REM
    }

    private fun exercise(t: HcExerciseType): Int = when (t) {
        HcExerciseType.OTHER_WORKOUT -> ExerciseSessionType.EXERCISE_SESSION_TYPE_OTHER_WORKOUT
        HcExerciseType.RUNNING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING
        HcExerciseType.RUNNING_TREADMILL -> ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING_TREADMILL
        HcExerciseType.WALKING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_WALKING
        HcExerciseType.HIKING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_HIKING
        HcExerciseType.BIKING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING
        HcExerciseType.BIKING_STATIONARY -> ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING_STATIONARY
        HcExerciseType.SWIMMING_POOL -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_POOL
        HcExerciseType.SWIMMING_OPEN_WATER -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_OPEN_WATER
        HcExerciseType.ELLIPTICAL -> ExerciseSessionType.EXERCISE_SESSION_TYPE_ELLIPTICAL
        HcExerciseType.ROWING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_ROWING
        HcExerciseType.ROWING_MACHINE -> ExerciseSessionType.EXERCISE_SESSION_TYPE_ROWING_MACHINE
        HcExerciseType.PADDLING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_PADDLING
        HcExerciseType.YOGA -> ExerciseSessionType.EXERCISE_SESSION_TYPE_YOGA
        HcExerciseType.PILATES -> ExerciseSessionType.EXERCISE_SESSION_TYPE_PILATES
        HcExerciseType.STRETCHING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_STRETCHING
        HcExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        HcExerciseType.STRENGTH_TRAINING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_STRENGTH_TRAINING
        HcExerciseType.WEIGHTLIFTING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_WEIGHTLIFTING
        HcExerciseType.GYMNASTICS -> ExerciseSessionType.EXERCISE_SESSION_TYPE_GYMNASTICS
        HcExerciseType.EXERCISE_CLASS -> ExerciseSessionType.EXERCISE_SESSION_TYPE_EXERCISE_CLASS
        HcExerciseType.DANCING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_DANCING
        HcExerciseType.STAIR_CLIMBING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_STAIR_CLIMBING
        HcExerciseType.STAIR_CLIMBING_MACHINE -> ExerciseSessionType.EXERCISE_SESSION_TYPE_STAIR_CLIMBING_MACHINE
        HcExerciseType.BOXING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_BOXING
        HcExerciseType.MARTIAL_ARTS -> ExerciseSessionType.EXERCISE_SESSION_TYPE_MARTIAL_ARTS
        HcExerciseType.FENCING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_FENCING
        HcExerciseType.SOCCER -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SOCCER
        HcExerciseType.BASKETBALL -> ExerciseSessionType.EXERCISE_SESSION_TYPE_BASKETBALL
        HcExerciseType.VOLLEYBALL -> ExerciseSessionType.EXERCISE_SESSION_TYPE_VOLLEYBALL
        HcExerciseType.BASEBALL -> ExerciseSessionType.EXERCISE_SESSION_TYPE_BASEBALL
        HcExerciseType.SOFTBALL -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SOFTBALL
        HcExerciseType.FOOTBALL_AMERICAN -> ExerciseSessionType.EXERCISE_SESSION_TYPE_FOOTBALL_AMERICAN
        HcExerciseType.ICE_HOCKEY -> ExerciseSessionType.EXERCISE_SESSION_TYPE_ICE_HOCKEY
        HcExerciseType.TABLE_TENNIS -> ExerciseSessionType.EXERCISE_SESSION_TYPE_TABLE_TENNIS
        HcExerciseType.BADMINTON -> ExerciseSessionType.EXERCISE_SESSION_TYPE_BADMINTON
        HcExerciseType.TENNIS -> ExerciseSessionType.EXERCISE_SESSION_TYPE_TENNIS
        HcExerciseType.CRICKET -> ExerciseSessionType.EXERCISE_SESSION_TYPE_CRICKET
        HcExerciseType.HANDBALL -> ExerciseSessionType.EXERCISE_SESSION_TYPE_HANDBALL
        HcExerciseType.SQUASH -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SQUASH
        HcExerciseType.GOLF -> ExerciseSessionType.EXERCISE_SESSION_TYPE_GOLF
        HcExerciseType.ICE_SKATING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_ICE_SKATING
        HcExerciseType.SKATING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SKATING
        HcExerciseType.SKIING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SKIING
        HcExerciseType.SNOWBOARDING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SNOWBOARDING
        HcExerciseType.ROCK_CLIMBING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_ROCK_CLIMBING
        HcExerciseType.PARAGLIDING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_PARAGLIDING
        HcExerciseType.SAILING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SAILING
        HcExerciseType.SURFING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SURFING
        HcExerciseType.WATER_POLO -> ExerciseSessionType.EXERCISE_SESSION_TYPE_WATER_POLO
        HcExerciseType.SCUBA_DIVING -> ExerciseSessionType.EXERCISE_SESSION_TYPE_SCUBA_DIVING
        HcExerciseType.FRISBEE_DISC -> ExerciseSessionType.EXERCISE_SESSION_TYPE_FRISBEE_DISC
    }

    private companion object {
        // The model is left out: the watch reports only a model code.
        val WATCH: Device by lazy { Device.Builder().setType(Device.DEVICE_TYPE_WATCH).setManufacturer("Xiaomi").build() }
    }
}
