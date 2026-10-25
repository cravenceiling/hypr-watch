/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker
    Copyright (C) 2026 Gadgetbridge contributors

    Ported from Gadgetbridge's WorkoutSummaryParser.java, XiaomiSimpleActivityParser.java,
    XiaomiSimpleDataEntry.java and XiaomiWorkoutType.java.

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

import local.hyprwatch.WorkoutMetric as M

// Layout: [id 7][pad 1][validity bitmap][fixed-width fields]. Like GB, the bitmap is read and
// ignored: unknown field widths would put it out of step with the fields.
// Deviation from GB: u16 fields are read unsigned (GB reads them signed).

private enum class Core { END, ACTIVE_SECONDS, ACTIVE_CALORIES, TOTAL_CALORIES, HR_AVG, HR_MAX, HR_MIN, STEPS, DISTANCE, WORKOUT_TYPE }

private enum class Width { U8, U16, I32, F32 }

private sealed class Field
private class Value(val width: Width, val core: Core?, val metric: M?, val scale: Double = 1.0) : Field()
private class Skip(val n: Int) : Field()
private class SkipIfPreviousByte(val n: Int, val present: (Int) -> Boolean) : Field()

private class Layout(val headerSize: Int) {
    val fields = ArrayList<Field>()

    fun int(c: Core) = fields.add(Value(Width.I32, c, null))
    fun int(m: M) = fields.add(Value(Width.I32, null, m))
    fun short(c: Core) = fields.add(Value(Width.U16, c, null))
    fun short(m: M, scale: Double = 1.0) = fields.add(Value(Width.U16, null, m, scale))
    fun byte(c: Core) = fields.add(Value(Width.U8, c, null))
    fun byte(m: M) = fields.add(Value(Width.U8, null, m))
    fun float(m: M) = fields.add(Value(Width.F32, null, m))
    fun unknown(n: Int) = fields.add(Skip(n))
    fun unknownIfPreviousByte(n: Int, present: (Int) -> Boolean) = fields.add(SkipIfPreviousByte(n, present))

    fun hrZones() {
        int(M.HR_ZONE_EXTREME_SECONDS)
        int(M.HR_ZONE_ANAEROBIC_SECONDS)
        int(M.HR_ZONE_AEROBIC_SECONDS)
        int(M.HR_ZONE_FAT_BURN_SECONDS)
        int(M.HR_ZONE_WARM_UP_SECONDS)
    }

    fun hr() {
        byte(Core.HR_AVG)
        byte(Core.HR_MAX)
        byte(Core.HR_MIN)
    }

    fun startEndActive() {
        int(M.TIME_START)
        int(Core.END)
        int(Core.ACTIVE_SECONDS)
    }
}

/** [r] is positioned after the id and pad byte. Null when GB has no layout for this subtype and version. */
internal fun parseWorkoutSummary(id: HealthFileId, r: HealthByteReader): WorkoutSummary? {
    val subtype = id.knownSubtype ?: return null
    val layout = layoutFor(subtype, id.version) ?: return null
    r.skip(layout.headerSize)
    val core = HashMap<Core, Number>()
    val metrics = LinkedHashMap<M, Double>()
    for (f in layout.fields) {
        when (f) {
            is Skip -> r.skip(f.n)
            is SkipIfPreviousByte -> if (f.present(r.previousByte())) r.skip(f.n)
            is Value -> {
                val v: Number = when (f.width) {
                    Width.U8 -> r.u8()
                    Width.U16 -> r.u16()
                    Width.I32 -> r.i32()
                    Width.F32 -> r.f32()
                }
                if (f.core != null) core[f.core] = v
                if (f.metric != null) metrics[f.metric] = if (f.scale == 1.0) v.toDouble() else v.toDouble() * f.scale
            }
        }
    }
    val workoutType = core[Core.WORKOUT_TYPE]?.toInt()
    fun int(c: Core) = core[c]?.toInt()
    return WorkoutSummary(
        start = id.timestamp,
        end = core[Core.END]?.toLong() ?: id.timestamp,
        tzOffsetSeconds = id.tzOffsetSeconds,
        sport = if (workoutType != null) WorkoutSport.fromXiaomiCode(workoutType) else defaultSport(subtype),
        xiaomiWorkoutType = workoutType,
        activeSeconds = int(Core.ACTIVE_SECONDS),
        activeCalories = int(Core.ACTIVE_CALORIES),
        totalCalories = int(Core.TOTAL_CALORIES),
        hrAvg = int(Core.HR_AVG),
        hrMax = int(Core.HR_MAX),
        hrMin = int(Core.HR_MIN),
        steps = int(Core.STEPS),
        distanceMeters = int(Core.DISTANCE),
        metrics = metrics,
    )
}

/** GB's per-subtype activity kind, used when the layout has no workout type field. */
private fun defaultSport(s: HealthSubtype): WorkoutSport = when (s) {
    HealthSubtype.SPORTS_OUTDOOR_WALKING_V1 -> WorkoutSport.WALKING
    HealthSubtype.SPORTS_OUTDOOR_RUNNING -> WorkoutSport.RUNNING
    HealthSubtype.SPORTS_INDOOR_CYCLING -> WorkoutSport.INDOOR_CYCLING
    HealthSubtype.SPORTS_FREESTYLE -> WorkoutSport.EXERCISE
    HealthSubtype.SPORTS_POOL_SWIMMING -> WorkoutSport.SWIMMING
    HealthSubtype.SPORTS_HIIT -> WorkoutSport.HIIT
    HealthSubtype.SPORTS_ELLIPTICAL -> WorkoutSport.ELLIPTICAL_TRAINER
    HealthSubtype.SPORTS_OUTDOOR_CYCLING_V2, HealthSubtype.SPORTS_OUTDOOR_CYCLING -> WorkoutSport.OUTDOOR_CYCLING
    HealthSubtype.SPORTS_TREADMILL -> WorkoutSport.TREADMILL
    HealthSubtype.SPORTS_ROWING -> WorkoutSport.ROWING
    HealthSubtype.SPORTS_JUMP_ROPING -> WorkoutSport.JUMP_ROPING
    else -> WorkoutSport.UNKNOWN
}

private fun layoutFor(s: HealthSubtype, v: Int): Layout? = when (s) {
    HealthSubtype.SPORTS_OUTDOOR_WALKING_V1, HealthSubtype.SPORTS_OUTDOOR_RUNNING -> outdoorWalkingV1(v)
    HealthSubtype.SPORTS_INDOOR_CYCLING -> indoorCycling(v)
    HealthSubtype.SPORTS_FREESTYLE -> freestyle(v)
    HealthSubtype.SPORTS_POOL_SWIMMING -> poolSwimming(v)
    HealthSubtype.SPORTS_HIIT -> hiit(v)
    HealthSubtype.SPORTS_ELLIPTICAL -> elliptical(v)
    HealthSubtype.SPORTS_OUTDOOR_WALKING_V2 -> outdoorWalkingV2(v)
    HealthSubtype.SPORTS_OUTDOOR_CYCLING_V2 -> outdoorCyclingV2(v)
    HealthSubtype.SPORTS_OUTDOOR_CYCLING -> outdoorCycling(v)
    HealthSubtype.SPORTS_TREADMILL -> treadmill(v)
    HealthSubtype.SPORTS_ROWING -> rowing(v)
    HealthSubtype.SPORTS_JUMP_ROPING -> jumpRoping(v)
    else -> null
}

private fun freestyle(v: Int): Layout? {
    val header = when (v) {
        5 -> 3 // Smart Band 8 Active
        7 -> 5
        8, 9, 10 -> 6
        else -> return null
    }
    return Layout(header).apply {
        startEndActive()
        short(Core.ACTIVE_CALORIES)
        hr()
        if (v > 5) unknown(6)
        float(M.TRAINING_EFFECT_AEROBIC)
        if (v > 6) unknown(1)
        unknown(1)
        short(M.RECOVERY_TIME)
        hrZones()
        if (v == 5) {
            unknown(10)
            short(Core.WORKOUT_TYPE)
            unknown(2)
            int(M.TIME_GOAL_SECONDS)
            short(M.CALORIES_GOAL)
        } else {
            short(Core.TOTAL_CALORIES)
            unknown(4)
            float(M.TRAINING_EFFECT_ANAEROBIC)
            unknown(1)
            short(Core.WORKOUT_TYPE)
            unknown(2)
            int(M.TIME_GOAL_SECONDS)
            short(M.CALORIES_GOAL)
            short(M.WORKOUT_LOAD)
            unknown(1)
            if (v > 7) {
                byte(M.VITALITY_GAIN)
                short(M.PUNCH_TOTAL)
                short(M.PUNCH_PERFECT)
                short(M.PUNCH_GOOD)
                short(M.PUNCH_MISS)
                unknown(7)
            }
            if (v >= 10) {
                short(M.THROWS_LOW)
                short(M.THROWS_MEDIUM)
                short(M.THROWS_HIGH)
                unknown(1)
            }
        }
    }
}

private fun indoorCycling(v: Int): Layout? {
    val header = when (v) {
        8 -> 7
        9 -> 8
        else -> return null
    }
    return Layout(header).apply {
        startEndActive()
        unknown(4)
        short(Core.ACTIVE_CALORIES)
        unknown(4)
        hr()
        float(M.TRAINING_EFFECT_AEROBIC)
        unknown(1)
        unknown(1)
        short(M.RECOVERY_TIME)
        hrZones()
        unknown(2)
        unknown(4)
        float(M.TRAINING_EFFECT_ANAEROBIC)
        unknown(3) // GB FIXME: field lengths not aligned with the header
        int(M.TIME_GOAL_SECONDS)
        short(M.CALORIES_GOAL)
        short(M.CALORIES_GOAL_MAX)
        unknown(28)
        short(M.WORKOUT_LOAD)
        unknown(24)
        byte(M.CONFIGURED_SETS)
        unknown(13)
    }
}

private fun outdoorWalkingV1(v: Int): Layout? {
    if (v == 3) return outdoorV3(withSteps = true) // Mi Watch Color Sport
    if (v != 4) return null
    return Layout(4).apply {
        startEndActive()
        int(Core.DISTANCE)
        int(Core.ACTIVE_CALORIES)
        int(M.PACE_MAX)
        int(M.PACE_MIN)
        unknown(4)
        int(Core.STEPS)
        unknown(2)
        hr()
        unknown(20) // GB FIXME: field lengths not aligned with the header
        float(M.TRAINING_EFFECT_ANAEROBIC)
        unknown(9)
        byte(M.RECOVERY_TIME)
        unknown(2)
        hrZones()
        int(M.TIME_GOAL_SECONDS)
    }
}

private fun outdoorWalkingV2(v: Int): Layout? {
    val header = when (v) {
        1 -> 5 // Smart Band 8 Active
        2 -> 6 // Redmi Watch 3
        4 -> 7
        5, 6 -> 9
        9 -> 13
        else -> return null
    }
    return Layout(header).apply {
        short(Core.WORKOUT_TYPE)
        startEndActive()
        unknown(4) // valid duration
        int(Core.DISTANCE)
        short(Core.TOTAL_CALORIES)
        short(Core.ACTIVE_CALORIES)
        if (v >= 5) int(M.PACE_AVG_SECONDS_PER_KM)
        int(M.PACE_MAX)
        int(M.PACE_MIN)
        if (v >= 5) float(M.SPEED_AVG_KMH)
        float(M.SPEED_MAX_KMH)
        int(Core.STEPS)
        if (v >= 5) {
            short(M.STEP_LENGTH_AVG_CM)
            short(M.STEP_RATE_AVG)
        }
        short(M.STEP_RATE_MAX)
        hr()
        unknown(20)
        float(M.TRAINING_EFFECT_AEROBIC)
        if (v == 1) {
            unknown(9)
        } else {
            unknown(1)
            float(M.TRAINING_EFFECT_ANAEROBIC)
            unknown(if (v >= 9) 6 else 1)
            byte(M.VO2_MAX)
            unknown(2)
            short(M.RECOVERY_TIME)
            unknown(1)
        }
        hrZones()
        if (v == 6) {
            int(M.TIME_GOAL_SECONDS)
            short(M.CALORIES_GOAL)
            int(M.DISTANCE_GOAL_METERS)
            int(M.PACE_GOAL_SECONDS_PER_KM)
            float(M.SPEED_GOAL)
            short(M.CADENCE_GOAL)
            unknown(1)
            short(M.WORKOUT_LOAD)
            unknown(7)
            short(M.PROJECTED_TIME_5KM_SECONDS)
            short(M.PROJECTED_TIME_10KM_SECONDS)
            short(M.PROJECTED_TIME_MARATHON_SECONDS)
            short(M.PROJECTED_TIME_HALF_MARATHON_SECONDS)
            byte(M.VITALITY_GAIN)
            unknown(18)
            short(M.AVG_GROUND_CONTACT_TIME_MS)
            short(M.MIN_GROUND_CONTACT_TIME_MS)
            unknown(7)
        }
        // GB TODO: v5/v7/v8 have 68 trailing bytes after the HR zones, not decoded.
        if (v >= 9) {
            unknown(46)
            short(M.WORKOUT_LOAD)
            unknown(15)
            short(M.ACTIVE_SCORE)
            unknown(17)
            short(M.AVG_GROUND_CONTACT_TIME_MS)
            short(M.MIN_GROUND_CONTACT_TIME_MS)
            unknown(6)
            short(M.AVG_VERTICAL_RATIO, 0.1)
            short(M.MIN_VERTICAL_RATIO, 0.1)
            short(M.MAX_VERTICAL_OSCILLATION_MM)
            short(M.AVG_VERTICAL_OSCILLATION_CM)
            short(M.MIN_VERTICAL_OSCILLATION_CM)
        }
    }
}

private fun outdoorCycling(v: Int): Layout? {
    val header = when (v) {
        4, 5 -> 6
        6 -> 7
        8 -> 8 // Smart Band 10 Pro
        else -> return null
    }
    return Layout(header).apply {
        short(Core.WORKOUT_TYPE)
        startEndActive()
        unknown(4)
        int(Core.DISTANCE)
        short(Core.TOTAL_CALORIES)
        short(Core.ACTIVE_CALORIES)
        int(M.PACE_MAX)
        int(M.PACE_MIN)
        if (v >= 5) float(M.SPEED_AVG_KMH)
        float(M.SPEED_MAX_KMH)
        hr()
        altitude()
        float(M.TRAINING_EFFECT_AEROBIC)
        unknown(1)
        float(M.TRAINING_EFFECT_ANAEROBIC)
        unknown(1)
        if (v >= 7) unknown(5)
        byte(M.VO2_MAX)
        unknown(1)
        unknown(1)
        short(M.RECOVERY_TIME)
        unknown(1)
        hrZones()
        if (v >= 6) {
            if (v >= 7) unknown(5)
            int(M.TIME_GOAL_SECONDS)
            short(M.CALORIES_GOAL)
            int(M.DISTANCE_GOAL_METERS)
            float(M.SPEED_GOAL)
            unknown(1) // route byte: 8 more bytes follow when it is 0xFC, 0xFD or 0xFF
            unknownIfPreviousByte(8) { it == 0xFC || it == 0xFD || it == 0xFF }
            short(M.WORKOUT_LOAD)
            unknown(15)
            byte(M.VITALITY_GAIN)
            unknown(5)
            if (v >= 7) unknown(2)
            if (v >= 8) unknown(1)
        }
    }
}

private fun Layout.altitude() {
    float(M.ELEVATION_GAIN_METERS)
    float(M.ELEVATION_LOSS_METERS)
    float(M.ALTITUDE_AVG_METERS)
    float(M.ALTITUDE_MAX_METERS)
    float(M.ALTITUDE_MIN_METERS)
}

private fun hiit(v: Int): Layout? {
    if (v != 5 && v != 6) return null
    return Layout(4).apply {
        startEndActive()
        short(Core.ACTIVE_CALORIES)
        hr()
        float(M.TRAINING_EFFECT_AEROBIC)
        unknown(1)
        unknown(1)
        short(M.RECOVERY_TIME)
        hrZones()
    }
}

private fun poolSwimming(v: Int): Layout? {
    val header = when (v) {
        6 -> 4
        7 -> 5
        8 -> 8
        else -> return null
    }
    return Layout(header).apply {
        startEndActive()
        int(Core.DISTANCE)
        short(Core.ACTIVE_CALORIES)
        if (v >= 7) int(M.PACE_AVG_SECONDS_PER_KM)
        int(M.PACE_MAX)
        int(M.PACE_MIN)
        unknown(1)
        short(M.RECOVERY_TIME)
        short(M.STROKES)
        byte(M.SWIM_STYLE)
        if (v >= 7) byte(M.STROKE_RATE_AVG)
        byte(M.STROKE_RATE_MAX)
        short(M.LAPS)
        short(M.SWOLF_AVG)
        short(M.SWOLF_MIN)
        byte(M.POOL_LENGTH_METERS)
        short(Core.TOTAL_CALORIES)
        unknown(4)
        int(M.TIME_GOAL_SECONDS)
        short(M.CALORIES_GOAL)
        int(M.DISTANCE_GOAL_METERS)
        int(M.PACE_GOAL_SECONDS_PER_KM)
        short(M.LENGTHS_GOAL)
        float(M.TRAINING_EFFECT_AEROBIC)
        unknown(1)
        float(M.TRAINING_EFFECT_ANAEROBIC)
        unknown(1)
        short(M.WORKOUT_LOAD)
        unknown(1)
        byte(M.VITALITY_GAIN)
    }
}

private fun elliptical(v: Int): Layout? {
    if (v !in 3..6) return null
    return Layout(4).apply {
        startEndActive()
        short(Core.ACTIVE_CALORIES)
        int(Core.STEPS)
        if (v >= 6) short(M.CADENCE_AVG)
        short(M.CADENCE_MAX)
        hr()
        float(M.TRAINING_EFFECT_AEROBIC)
        if (v >= 4) unknown(1)
        unknown(1)
        short(M.RECOVERY_TIME)
        hrZones()
    }
}

private fun rowing(v: Int): Layout? {
    val header = when (v) {
        4 -> 4
        6, 7 -> 5
        8 -> 6
        else -> return null
    }
    return Layout(header).apply {
        startEndActive()
        short(Core.ACTIVE_CALORIES)
        hr()
        if (v >= 7) {
            float(M.TRAINING_EFFECT_AEROBIC)
            unknown(2)
            short(M.RECOVERY_TIME)
        } else {
            unknown(7)
            if (v > 4) unknown(1)
        }
        hrZones()
        if (v >= 8) unknown(5)
        if (v >= 7) short(Core.TOTAL_CALORIES) else unknown(2)
        int(M.STROKES)
        int(M.STROKE_RATE_AVG)
        if (v >= 7) {
            int(M.STROKE_RATE_MAX)
            unknown(12)
            byte(M.GROUP_COUNT)
            unknown(4)
            float(M.TRAINING_EFFECT_ANAEROBIC)
            unknown(3)
            int(M.TIME_GOAL_SECONDS)
            short(M.CALORIES_GOAL)
            short(M.GOAL_COUNT)
            short(M.WORKOUT_LOAD)
            unknown(1)
            byte(M.VITALITY_GAIN)
        } else {
            unknown(4)
        }
    }
}

private fun treadmill(v: Int): Layout? {
    val header = when (v) {
        5 -> 4
        9 -> 6
        10 -> 8
        11 -> 9
        else -> return null
    }
    return Layout(header).apply {
        startEndActive()
        int(Core.DISTANCE)
        short(Core.ACTIVE_CALORIES)
        if (v >= 10) int(M.PACE_AVG_SECONDS_PER_KM)
        int(M.PACE_MAX)
        int(M.PACE_MIN)
        int(Core.STEPS)
        if (v >= 10) {
            short(M.STEP_LENGTH_AVG_CM)
            short(M.CADENCE_AVG)
        }
        short(M.CADENCE_MAX)
        hr()
        float(M.TRAINING_EFFECT_AEROBIC)
        if (v == 9) {
            unknown(4)
        } else {
            if (v >= 10) unknown(1)
            byte(M.VO2_MAX)
            if (v >= 10) byte(M.VO2_MAX_LEVEL)
            unknown(1)
        }
        short(M.RECOVERY_TIME)
        hrZones()
        if (v == 9) {
            unknown(39)
            short(M.WORKOUT_LOAD)
            unknown(7)
            byte(M.VITALITY_GAIN)
        }
        if (v >= 11) {
            short(Core.TOTAL_CALORIES)
            unknown(4)
            float(M.TRAINING_EFFECT_ANAEROBIC)
            unknown(5)
            int(M.TIME_GOAL_SECONDS)
            short(M.CALORIES_GOAL)
            int(M.DISTANCE_GOAL_METERS)
            int(M.PACE_GOAL_SECONDS_PER_KM)
            float(M.SPEED_GOAL)
            short(M.CADENCE_GOAL)
            int(M.DISTANCE_METERS_CALIBRATED)
            short(M.WORKOUT_LOAD)
            unknown(7)
            short(M.PROJECTED_TIME_5KM_SECONDS)
            short(M.PROJECTED_TIME_10KM_SECONDS)
            short(M.PROJECTED_TIME_MARATHON_SECONDS)
            short(M.PROJECTED_TIME_HALF_MARATHON_SECONDS)
            byte(M.VITALITY_GAIN)
            unknown(18)
            short(M.AVG_GROUND_CONTACT_TIME_MS)
            short(M.MIN_GROUND_CONTACT_TIME_MS)
            unknown(7)
        }
    }
}

private fun outdoorCyclingV2(v: Int): Layout? {
    if (v == 3) return outdoorV3(withSteps = false) // Mi Watch Color Sport
    if (v != 4) return null
    return Layout(5).apply {
        startEndActive()
        int(Core.DISTANCE)
        short(Core.ACTIVE_CALORIES)
        unknown(8)
        float(M.SPEED_MAX_KMH)
        hr()
        unknown(28)
        hrZones()
        unknown(18)
        unknown(2)
        unknown(6)
    }
}

/** Version 3 of outdoor walking v1 and outdoor cycling v2 (Mi Watch Color Sport). */
private fun outdoorV3(withSteps: Boolean): Layout = Layout(4).apply {
    startEndActive()
    int(Core.DISTANCE)
    short(Core.ACTIVE_CALORIES)
    int(M.PACE_MAX)
    int(M.PACE_MIN)
    float(M.SPEED_MAX_KMH)
    if (withSteps) {
        int(Core.STEPS)
        short(M.CADENCE_MAX)
    }
    hr()
    altitude()
    float(M.TRAINING_EFFECT_AEROBIC)
    byte(M.VO2_MAX)
    unknown(1) // energy consumed
    short(M.RECOVERY_TIME)
    hrZones()
    short(Core.TOTAL_CALORIES)
    unknown(4) // valid duration, always 0
    float(M.TRAINING_EFFECT_ANAEROBIC)
}

private fun jumpRoping(v: Int): Layout? {
    if (v != 3 && v != 5) return null
    return Layout(5).apply {
        startEndActive()
        short(Core.ACTIVE_CALORIES)
        hr()
        float(M.TRAINING_EFFECT_AEROBIC)
        if (v == 3) {
            unknown(3)
        } else {
            unknown(2)
            short(M.RECOVERY_TIME)
        }
        hrZones()
        unknown(2)
        int(M.JUMPS)
        short(M.JUMP_RATE_AVG)
        unknown(2)
        short(M.JUMP_RATE_MAX)
        if (v == 3) {
            unknown(43)
            unknown(2)
            unknown(2)
        } else {
            unknown(27)
            unknown(4)
            float(M.TRAINING_EFFECT_ANAEROBIC)
            unknown(3)
            int(M.TIME_GOAL_SECONDS)
            short(M.CALORIES_GOAL)
            int(M.JUMPS_GOAL)
            short(M.WORKOUT_LOAD)
            unknown(1)
            byte(M.VITALITY_GAIN)
        }
    }
}

/** Activity kinds from GB's ActivityKind that Xiaomi workout summaries can map to. */
enum class WorkoutSport {
    UNKNOWN, OUTDOOR_RUNNING, WALKING, HIKING, TREKKING, TRAIL_RUN, OUTDOOR_CYCLING, INDOOR_CYCLING,
    FREE_TRAINING, POOL_SWIM, SWIMMING_OPENWATER, ELLIPTICAL_TRAINER, YOGA, ROWING_MACHINE, JUMP_ROPING,
    OUTDOOR_WALKING, HIIT, TRIATHLON, SAILING, STAND_UP_PADDLEBOARDING, WATER_POLO, OTHER_WATER_SPORTS,
    WATER_SKIING, KAYAKING, RAFTING, ROWING, POWERBOATING, FINSWIMMING, DIVING, ARTISTIC_SWIMMING,
    SNORKELING, KITESURFING, FLOWRIDING, DRAGON_BOAT, ROCK_CLIMBING, SKATEBOARDING, ROLLER_SKATING, PARKOUR,
    ATV, PARAGLIDING, BMX, RACE_WALKING, STAIR_CLIMBER, STAIRS, STEPPER, CORE_TRAINING, FLEXIBILITY, PILATES,
    GYMNASTICS, STRETCHING, STRENGTH_TRAINING, CROSS_TRAINING, AEROBICS, PHYSICAL_TRAINING, WALL_BALL,
    DUMBBELL, BARBELL, WEIGHTLIFTING, DEADLIFT, BURPEE, SIT_UPS, FUNCTIONAL_TRAINING, UPPER_BODY, LOWER_BODY,
    ABS, BACK, SPINNING, AIR_WALKER, STEP_AEROBICS, HORIZONTAL_BAR, PARALLEL_BARS, MASS_GYMNASTICS,
    CARDIO_COMBAT, BATTLE_ROPE, AEROBIC_COMBO, INDOOR_WALKING, INDOOR_FITNESS, SQUARE_DANCE, BELLY_DANCE,
    BALLET, STREET_DANCE, ZUMBA, FOLK_DANCE, JAZZ_DANCE, LATIN_DANCE, HIP_HOP, POLE_DANCE, BREAKING,
    BALLROOM_DANCE, MODERN_DANCE, DANCE, BOXING, WRESTLING, MARTIAL_ARTS, TAI_CHI, MUAY_THAI, JUDO,
    TAEKWONDO, KARATE, KICKBOXING, KENDO, FENCING, JUJITSU, SOCCER, BASKETBALL, VOLLEYBALL, BASEBALL,
    SOFTBALL, AMERICAN_FOOTBALL, HOCKEY, TABLE_TENNIS, BADMINTON, TENNIS, CRICKET, HANDBALL, BOWLING, SQUASH,
    BILLIARDS, SHUTTLECOCK, BEACH_SOCCER, BEACH_VOLLEYBALL, SEPAK_TAKRAW, GOLF, TABLE_FOOTBALL, FUTSAL,
    HACKY_SACK, BOCCE, JAI_ALAI, GATEBALL, DODGEBALL, SHUFFLEBOARD, ICE_SKATING, CURLING,
    OTHER_WINTER_SPORTS, SNOWMOBILING, ICE_HOCKEY, BOBSLEIGH, SLEDDING, INDOOR_ICE_SKATING, SNOWBOARDING,
    SKIING, ARCHERY, DARTS, HORSE_RIDING, TUG_OF_WAR, HULA_HOOP, KITE_FLYING, FISHING, FRISBEE, SWING,
    SOMATOSENSORY_GAME, ESPORTS, CHESS, CHECKERS, WEIQI, BRIDGE, BOARD_GAME, EQUESTRIAN, ATHLETICS,
    AUTO_RACING, RUNNING, EXERCISE, SWIMMING, TREADMILL,
    ;

    companion object {
        /** GB XiaomiWorkoutType.fromCode. */
        fun fromXiaomiCode(code: Int): WorkoutSport = when (code) {
            1 -> OUTDOOR_RUNNING
            2 -> WALKING
            3 -> HIKING
            4 -> TREKKING
            5 -> TRAIL_RUN
            6 -> OUTDOOR_CYCLING
            7 -> INDOOR_CYCLING
            8 -> FREE_TRAINING
            9 -> POOL_SWIM
            10 -> SWIMMING_OPENWATER
            11 -> ELLIPTICAL_TRAINER
            12 -> YOGA
            13 -> ROWING_MACHINE
            14 -> JUMP_ROPING
            15 -> OUTDOOR_WALKING
            16 -> HIIT
            17 -> TRIATHLON
            100 -> SAILING
            101 -> STAND_UP_PADDLEBOARDING
            102 -> WATER_POLO
            103 -> OTHER_WATER_SPORTS
            104 -> WATER_SKIING
            105 -> KAYAKING
            106 -> RAFTING
            107 -> ROWING
            108 -> POWERBOATING
            109 -> FINSWIMMING
            110 -> DIVING
            111 -> ARTISTIC_SWIMMING
            112 -> SNORKELING
            113 -> KITESURFING
            114 -> FLOWRIDING
            115 -> DRAGON_BOAT
            200 -> ROCK_CLIMBING
            201 -> SKATEBOARDING
            202 -> ROLLER_SKATING
            203 -> PARKOUR
            204 -> ATV
            205 -> PARAGLIDING
            206 -> BMX
            207 -> RACE_WALKING
            300 -> STAIR_CLIMBER
            301 -> STAIRS
            302 -> STEPPER
            303 -> CORE_TRAINING
            304 -> FLEXIBILITY
            305 -> PILATES
            306 -> GYMNASTICS
            307 -> STRETCHING
            308 -> STRENGTH_TRAINING
            309 -> CROSS_TRAINING
            310 -> AEROBICS
            311 -> PHYSICAL_TRAINING
            312 -> WALL_BALL
            313 -> DUMBBELL
            314 -> BARBELL
            315 -> WEIGHTLIFTING
            316 -> DEADLIFT
            317 -> BURPEE
            318 -> SIT_UPS
            319 -> FUNCTIONAL_TRAINING
            320 -> UPPER_BODY
            321 -> LOWER_BODY
            322 -> ABS
            323 -> BACK
            324 -> SPINNING
            325 -> AIR_WALKER
            326 -> STEP_AEROBICS
            327 -> HORIZONTAL_BAR
            328 -> PARALLEL_BARS
            329 -> MASS_GYMNASTICS
            330 -> CARDIO_COMBAT
            331 -> BATTLE_ROPE
            332 -> AEROBIC_COMBO
            333 -> INDOOR_WALKING
            399 -> INDOOR_FITNESS
            400 -> SQUARE_DANCE
            401 -> BELLY_DANCE
            402 -> BALLET
            403 -> STREET_DANCE
            404 -> ZUMBA
            405 -> FOLK_DANCE
            406 -> JAZZ_DANCE
            407 -> LATIN_DANCE
            408 -> HIP_HOP
            409 -> POLE_DANCE
            410 -> BREAKING
            411 -> BALLROOM_DANCE
            412 -> MODERN_DANCE
            499 -> DANCE
            500 -> BOXING
            501 -> WRESTLING
            502 -> MARTIAL_ARTS
            503 -> TAI_CHI
            504 -> MUAY_THAI
            505 -> JUDO
            506 -> TAEKWONDO
            507 -> KARATE
            508 -> KICKBOXING
            509 -> KENDO
            510 -> FENCING
            511 -> JUJITSU
            600 -> SOCCER
            601 -> BASKETBALL
            602 -> VOLLEYBALL
            603 -> BASEBALL
            604 -> SOFTBALL
            605 -> AMERICAN_FOOTBALL
            606 -> HOCKEY
            607 -> TABLE_TENNIS
            608 -> BADMINTON
            609 -> TENNIS
            610 -> CRICKET
            611 -> HANDBALL
            612 -> BOWLING
            613 -> SQUASH
            614 -> BILLIARDS
            615 -> SHUTTLECOCK
            616 -> BEACH_SOCCER
            617 -> BEACH_VOLLEYBALL
            618 -> SEPAK_TAKRAW
            619 -> GOLF
            620 -> TABLE_FOOTBALL
            621 -> FUTSAL
            622 -> HACKY_SACK
            623 -> BOCCE
            624 -> JAI_ALAI
            625 -> GATEBALL
            626 -> DODGEBALL
            627 -> SHUFFLEBOARD
            700 -> ICE_SKATING
            701 -> CURLING
            702 -> OTHER_WINTER_SPORTS
            703 -> SNOWMOBILING
            704 -> ICE_HOCKEY
            705 -> BOBSLEIGH
            706 -> SLEDDING
            707 -> INDOOR_ICE_SKATING
            708 -> SNOWBOARDING
            709 -> SKIING
            800 -> ARCHERY
            801 -> DARTS
            802 -> HORSE_RIDING
            803 -> TUG_OF_WAR
            804 -> HULA_HOOP
            805 -> KITE_FLYING
            806 -> FISHING
            807 -> FRISBEE
            808 -> SHUTTLECOCK
            809 -> SWING
            810 -> SOMATOSENSORY_GAME
            811 -> ESPORTS
            900 -> CHESS
            901 -> CHECKERS
            902 -> WEIQI
            903 -> BRIDGE
            904 -> BOARD_GAME
            10000 -> EQUESTRIAN
            10001 -> ATHLETICS
            10002 -> AUTO_RACING
            else -> UNKNOWN
        }
    }
}
