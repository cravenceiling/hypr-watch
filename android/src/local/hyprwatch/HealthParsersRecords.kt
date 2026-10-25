/*  Copyright (C) 2023-2026 José Rebelo, Andreas Shimokawa, Yoran Vulker, Dany Mestas
    Copyright (C) 2026 Gadgetbridge contributors

    Record shapes for data decoded by the Gadgetbridge Xiaomi activity parsers.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package local.hyprwatch

// All times are unix seconds UTC. tzOffsetSeconds comes from the file id. Null means not measured.

sealed interface HealthParseResult {
    data class Parsed(val id: HealthFileId, val data: HealthData) : HealthParseResult

    /** The file is intact but no parser exists for its type, subtype or version. */
    data class Unsupported(val id: HealthFileId, val reason: String) : HealthParseResult

    /** Layout, CRC or content is broken. [id] is null when not even the id could be read. */
    data class Invalid(val reason: String, val id: HealthFileId? = null) : HealthParseResult
}

sealed interface HealthData {
    /** ACTIVITY/DAILY/DETAILS: one sample per minute from the file timestamp. */
    data class DailyDetails(val tzOffsetSeconds: Int, val samples: List<MinuteSample>) : HealthData

    /** ACTIVITY/DAILY/SUMMARY. */
    data class Daily(val summary: DailySummary) : HealthData

    /** ACTIVITY/SLEEP (one session) or ACTIVITY/SLEEP_STAGES (zero or one; empty when the watch sent no data). */
    data class Sleep(val sessions: List<SleepSession>) : HealthData

    /** ACTIVITY/MANUAL_SAMPLES. */
    data class Manual(val tzOffsetSeconds: Int, val samples: List<ManualSample>) : HealthData

    /** SPORTS/SUMMARY. */
    data class Workout(val summary: WorkoutSummary) : HealthData
}

data class MinuteSample(
    val time: Long,
    val steps: Int?,
    val activeCalories: Int?,
    val distanceMeters: Int?,
    /** 0 maps to null. */
    val heartRate: Int?,
    /** Body energy as GB names it; meaning unverified. */
    val energy: Int?,
    /** v3+ only; 0 maps to null. */
    val spo2: Int?,
    /** v3+ only; 255 maps to null. */
    val stress: Int?,
    /** Raw group values GB reads but does not decode. */
    val unknown: MinuteUnknown,
)

data class MinuteUnknown(
    /** Bit 0 of the steps group ("TODO what's the first bit?"). */
    val stepsGroupBit0: Int?,
    /** Bits 0..1 of the calories group ("TODO activity type?"). */
    val caloriesGroupBits0to1: Int?,
    val group2: Int?,
    val group6: Int?,
    /** v4: GB comments "light value", unverified. */
    val v4Group9: Int?,
    /** v4: GB comments "body momentum", unverified. */
    val v4Group10: Int?,
)

data class DailySummary(
    val time: Long,
    val tzOffsetSeconds: Int,
    val steps: Int?,
    val activeCalories: Int?,
    val restingHeartRate: Int?,
    val maxHeartRate: Int?,
    val maxHeartRateTime: Long?,
    val minHeartRate: Int?,
    val minHeartRateTime: Long?,
    /**
     * GB's name. On a Redmi Watch 5 Active it matched the minute-data mean only on the day still in
     * progress; on complete days it was lower, once below [minHeartRate]. Meaning unverified.
     */
    val avgHeartRate: Int?,
    /** GB's name. On a Redmi Watch 5 Active it equalled the day's last stress reading, not the mean. */
    val avgStress: Int?,
    val maxStress: Int?,
    val minStress: Int?,
    /** Bit n set means the user stood during hour n from 00:00 (24 bits). */
    val standingHours: Int?,
    val calories: Int?,
    val recoveryHours: Int?,
    val maxSpo2: Int?,
    val maxSpo2Time: Long?,
    val minSpo2: Int?,
    val minSpo2Time: Long?,
    val avgSpo2: Int?,
    // v5 only
    val trainingLoadDay: Int?,
    val trainingLoadWeek: Int?,
    val trainingLoadLevel: Int?,
    val vitalityIncreaseLight: Int?,
    val vitalityIncreaseModerate: Int?,
    val vitalityIncreaseHigh: Int?,
    val vitalityCurrent: Int?,
)

enum class SleepStageKind { AWAKE, LIGHT, DEEP, REM, NOT_SLEEPING, UNKNOWN }

data class SleepStage(
    val start: Long,
    val kind: SleepStageKind,
    /** Stage value as found in the file. */
    val raw: Int,
    /** Known for ACTIVITY/SLEEP stage packets; null for SLEEP_STAGES, which only lists change times. */
    val durationMinutes: Int?,
)

data class SleepSession(
    val bedTime: Long,
    val wakeupTime: Long,
    val tzOffsetSeconds: Int,
    /** ACTIVITY/SLEEP: isAwake byte is 1 (seen on every completed night; 0 assumed to mean still counting). SLEEP_STAGES: always true. */
    val finished: Boolean,
    /** v5 ACTIVITY/SLEEP "into bed" time, about 30 min before [bedTime]. */
    val intoBedTime: Long?,
    /** v4+ ACTIVITY/SLEEP. */
    val sleepQuality: Int?,
    val totalMinutes: Int?,
    val deepMinutes: Int?,
    val lightMinutes: Int?,
    val remMinutes: Int?,
    val awakeMinutes: Int?,
    val stages: List<SleepStage>,
    /** The stage packet stream ended mid-packet; GB then drops all stages, and so does this parser. */
    val stagesDropped: Boolean,
)

enum class ManualSampleKind { HEART_RATE, SPO2, STRESS, TEMPERATURE }

data class ManualSample(
    val time: Long,
    val kind: ManualSampleKind,
    /**
     * bpm, %, or stress score. TEMPERATURE is GB's raw i32, which GB notes is really two u16
     * values; the unit is unverified (observed 0x0E5A = 3674 for a body temperature).
     */
    val value: Int,
)

data class WorkoutSummary(
    /** The file id time, as GB uses; the TIME_START field, which can be a second later, is in [metrics]. */
    val start: Long,
    /** TIME_END field. */
    val end: Long,
    val tzOffsetSeconds: Int,
    val sport: WorkoutSport,
    /** Raw workout type code when the layout carries one; it decides [sport]. */
    val xiaomiWorkoutType: Int?,
    val activeSeconds: Int?,
    /** GB CALORIES_BURNT. */
    val activeCalories: Int?,
    /** GB CALORIES_TOTAL (active + basal). */
    val totalCalories: Int?,
    val hrAvg: Int?,
    val hrMax: Int?,
    val hrMin: Int?,
    val steps: Int?,
    val distanceMeters: Int?,
    /** Every other field GB names for this layout, raw (scaled only where GB scales). */
    val metrics: Map<WorkoutMetric, Double>,
)

/** GB ActivitySummaryEntries keys used by the Xiaomi summary layouts. Units follow GB's labels. */
enum class WorkoutMetric {
    /** Unix seconds. */
    TIME_START,
    TRAINING_EFFECT_AEROBIC,
    TRAINING_EFFECT_ANAEROBIC,
    /** Hours (GB labels walking v1's byte as seconds; value is the same raw byte). */
    RECOVERY_TIME,
    HR_ZONE_EXTREME_SECONDS,
    HR_ZONE_ANAEROBIC_SECONDS,
    HR_ZONE_AEROBIC_SECONDS,
    HR_ZONE_FAT_BURN_SECONDS,
    HR_ZONE_WARM_UP_SECONDS,
    TIME_GOAL_SECONDS,
    CALORIES_GOAL,
    CALORIES_GOAL_MAX,
    DISTANCE_GOAL_METERS,
    PACE_GOAL_SECONDS_PER_KM,
    SPEED_GOAL,
    CADENCE_GOAL,
    LENGTHS_GOAL,
    JUMPS_GOAL,
    GOAL_COUNT,
    WORKOUT_LOAD,
    VITALITY_GAIN,
    ACTIVE_SCORE,
    PACE_AVG_SECONDS_PER_KM,
    /** Fastest pace. Walking v1 labels the unit seconds per metre. */
    PACE_MAX,
    PACE_MIN,
    SPEED_AVG_KMH,
    SPEED_MAX_KMH,
    STEP_LENGTH_AVG_CM,
    STEP_RATE_AVG,
    STEP_RATE_MAX,
    CADENCE_AVG,
    CADENCE_MAX,
    VO2_MAX,
    VO2_MAX_LEVEL,
    DISTANCE_METERS_CALIBRATED,
    PROJECTED_TIME_5KM_SECONDS,
    PROJECTED_TIME_10KM_SECONDS,
    PROJECTED_TIME_HALF_MARATHON_SECONDS,
    PROJECTED_TIME_MARATHON_SECONDS,
    AVG_GROUND_CONTACT_TIME_MS,
    MIN_GROUND_CONTACT_TIME_MS,
    /** Percent, already scaled by GB's 0.1. */
    AVG_VERTICAL_RATIO,
    MIN_VERTICAL_RATIO,
    MAX_VERTICAL_OSCILLATION_MM,
    AVG_VERTICAL_OSCILLATION_CM,
    MIN_VERTICAL_OSCILLATION_CM,
    ELEVATION_GAIN_METERS,
    ELEVATION_LOSS_METERS,
    ALTITUDE_AVG_METERS,
    ALTITUDE_MAX_METERS,
    ALTITUDE_MIN_METERS,
    STROKES,
    STROKE_RATE_AVG,
    STROKE_RATE_MAX,
    /** 0 medley, 1 breaststroke, 2 freestyle, 3 backstroke, 4 butterfly. */
    SWIM_STYLE,
    LAPS,
    SWOLF_AVG,
    SWOLF_MIN,
    POOL_LENGTH_METERS,
    GROUP_COUNT,
    CONFIGURED_SETS,
    JUMPS,
    JUMP_RATE_AVG,
    JUMP_RATE_MAX,
    PUNCH_TOTAL,
    PUNCH_PERFECT,
    PUNCH_GOOD,
    PUNCH_MISS,
    THROWS_LOW,
    THROWS_MEDIUM,
    THROWS_HIGH,
}
