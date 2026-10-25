/*  Copyright (C) 2023-2026 José Rebelo, Andreas Shimokawa, Dany Mestas
    Copyright (C) 2026 Gadgetbridge contributors

    Ported from Gadgetbridge's XiaomiActivityFileFetcher.java (layout and CRC check),
    XiaomiActivityParser.java, XiaomiComplexActivityParser.java, DailyDetailsParser.java,
    DailySummaryParser.java, SleepDetailsParser.java, SleepStagesParser.java and
    ManualSamplesParser.java. Database and Android code removed; decoding kept.

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

import java.util.zip.CRC32

/**
 * Decodes one complete activity file: [id 7][pad 1][body][CRC-32 LE over everything before it].
 * Pure and total: never throws, never reads outside [bytes].
 */
fun parseHealthFile(bytes: ByteArray): HealthParseResult {
    if (bytes.size < MIN_FILE_SIZE) return HealthParseResult.Invalid("File too short: ${bytes.size} bytes")
    val id = HealthFileId.parse(bytes) ?: return HealthParseResult.Invalid("Missing file id")
    val bodyEnd = bytes.size - 4
    val crc = CRC32()
    crc.update(bytes, 0, bodyEnd)
    val stored = HealthByteReader(bytes, bodyEnd, bytes.size).i32().toLong() and 0xFFFFFFFFL
    if (crc.value != stored) return HealthParseResult.Invalid("CRC mismatch", id)
    // GB only warns about a non-zero pad byte; manual samples v1 rejects it below.
    // Every parser here stops before the CRC; GB lets daily summary, sleep and workout summary read into it.
    val r = HealthByteReader(bytes, HealthFileId.SIZE + 1, bodyEnd)
    return try {
        decode(id, bytes[HealthFileId.SIZE].toInt(), r)
    } catch (e: HealthTruncatedException) {
        HealthParseResult.Invalid("Truncated: ${e.message}", id)
    } catch (e: HealthLimitException) {
        HealthParseResult.Invalid(e.message!!, id)
    } catch (e: RuntimeException) {
        HealthParseResult.Invalid("Parser error: $e", id)
    }
}

private const val MIN_FILE_SIZE = 13

// Record caps, so a CRC-valid file of tiny records cannot exhaust memory: four days of minutes,
// two days of one-minute sleep stages, and far more manual measurements than a watch keeps.
internal const val MAX_MINUTE_SAMPLES = 4 * 1440
internal const val MAX_SLEEP_STAGES = 2 * 1440
internal const val MAX_MANUAL_SAMPLES = 10_000

private fun decode(id: HealthFileId, pad: Int, r: HealthByteReader): HealthParseResult {
    fun unsupported(what: String) = HealthParseResult.Unsupported(id, what)
    fun parsed(data: HealthData) = HealthParseResult.Parsed(id, data)
    val v = id.version
    if (id.type == HealthFileType.SPORTS) {
        return when (id.detailType) {
            HealthDetailType.SUMMARY -> {
                val s = parseWorkoutSummary(id, r) ?: return unsupported("No workout summary layout for ${subtypeName(id)} v$v")
                parsed(HealthData.Workout(s))
            }
            HealthDetailType.DETAILS, HealthDetailType.GPS_TRACK -> unsupported("Workout ${id.detailType.name} is out of scope")
            HealthDetailType.UNKNOWN_3 -> unsupported("Unknown detail type 3")
        }
    }
    return when (id.knownSubtype) {
        HealthSubtype.ACTIVITY_DAILY -> when (id.detailType) {
            HealthDetailType.DETAILS -> parseDailyDetails(id, r)?.let(::parsed) ?: unsupported("Daily details v$v")
            HealthDetailType.SUMMARY -> parseDailySummary(id, r)?.let(::parsed) ?: unsupported("Daily summary v$v")
            else -> unsupported("Daily ${id.detailType.name}")
        }
        HealthSubtype.ACTIVITY_SLEEP_STAGES ->
            if (id.detailType != HealthDetailType.DETAILS) unsupported("Sleep stages ${id.detailType.name}")
            else if (v != 2) unsupported("Sleep stages v$v")
            else parsed(parseSleepStages(id, r))
        HealthSubtype.ACTIVITY_MANUAL_SAMPLES ->
            if (id.detailType != HealthDetailType.DETAILS) unsupported("Manual samples ${id.detailType.name}")
            else parseManualSamples(id, pad, r)
        // GB accepts any detail type here: v2 arrives as DETAILS, v4 and v5 as SUMMARY.
        HealthSubtype.ACTIVITY_SLEEP -> parseSleepDetails(id, r)?.let(::parsed) ?: unsupported("Sleep v$v")
        else -> unsupported("Unknown activity subtype ${subtypeName(id)}")
    }
}

private fun subtypeName(id: HealthFileId): String = id.knownSubtype?.name ?: "0x" + Integer.toHexString(id.subtype)

/** GB XiaomiActivityParser.validData: bit [i] of an MSB-first bitmap. */
private fun validData(header: ByteArray, i: Int): Boolean = i / 8 < header.size && (header[i / 8].toInt() and (1 shl (7 - i % 8))) != 0

// ---- DAILY / DETAILS ----

/** GB XiaomiComplexActivityParser: one header nibble per field group, bit 3 = present, bits 2..0 = sub-values valid. */
private class ComplexReader(private val header: ByteArray, private val r: HealthByteReader) {
    private var group = -1
    private var groupBits = 0
    private var value = 0

    fun reset() {
        group = -1
        groupBits = 0
        value = 0
    }

    fun next(nBits: Int): Boolean {
        group++
        if (group >= header.size * 2) {
            // GB logs an error but still consumes, so the stream advances.
            consume(nBits)
            return false
        }
        if (nibble() and 8 == 0) return false
        groupBits = nBits
        value = consume(nBits)
        return true
    }

    private fun consume(nBits: Int): Int = when (nBits) {
        8 -> r.u8()
        16 -> r.u16()
        else -> r.i32()
    }

    private fun nibble(): Int {
        val b = header[group / 2].toInt()
        return if (group % 2 == 0) (b and 0xF0) shr 4 else b and 0x0F
    }

    fun has(idx: Int): Boolean = nibble() and (1 shl (2 - idx)) != 0

    fun get(offset: Int, nBits: Int): Int {
        val shift = groupBits - offset - nBits
        return (value ushr shift) and ((1 shl nBits) - 1)
    }
}

private fun parseDailyDetails(id: HealthFileId, r: HealthByteReader): HealthData.DailyDetails? {
    val v = id.version
    val headerSize = when (v) {
        1, 2 -> 4
        3 -> 5
        4 -> 6
        else -> return null
    }
    val c = ComplexReader(r.bytes(headerSize), r)
    val samples = ArrayList<MinuteSample>()
    var time = id.timestamp
    while (r.remaining() > 0) {
        val before = r.pos
        c.reset()
        var extra = 0
        var bit0: Int? = null
        var steps: Int? = null
        if (c.next(16)) {
            bit0 = c.get(0, 1)
            if (c.has(1)) extra = c.get(1, 1)
            if (c.has(2)) steps = c.get(2, 14)
        }
        var calBits: Int? = null
        var activeCalories: Int? = null
        if (c.next(8)) {
            calBits = c.get(0, 2)
            if (c.has(1)) activeCalories = c.get(2, 6)
        }
        val g2 = if (c.next(8)) c.get(0, 8) else null
        val distance = if (c.next(16) && c.has(0)) c.get(0, 16) else null
        val hr = if (c.next(8) && c.has(0)) c.get(0, 8) else null
        val energy = if (c.next(8) && c.has(0)) c.get(0, 8) else null
        val g6 = if (c.next(16)) c.get(0, 16) else null
        var spo2: Int? = null
        var stress: Int? = null
        if (v >= 3) {
            if (c.next(8) && c.has(0)) spo2 = c.get(0, 8)
            if (c.next(8) && c.has(0)) stress = c.get(0, 8).takeIf { it != 255 }
        }
        // GB #4625: one byte always follows when this flag is set, before the v4 groups.
        if (extra == 1) r.u8()
        var g9: Int? = null
        var g10: Int? = null
        if (v >= 4) {
            if (c.next(16)) g9 = c.get(0, 16)
            if (c.next(16)) g10 = c.get(0, 16)
        }
        if (r.pos == before) throw HealthTruncatedException("daily details record consumed no bytes at ${r.pos}")
        limit(samples.size, MAX_MINUTE_SAMPLES, "daily details records")
        samples.add(
            MinuteSample(
                time = time,
                steps = steps,
                activeCalories = activeCalories,
                distanceMeters = distance,
                heartRate = hr?.takeIf { it != 0 },
                energy = energy,
                spo2 = spo2?.takeIf { it != 0 },
                stress = stress,
                unknown = MinuteUnknown(bit0, calBits, g2, g6, g9, g10),
            ),
        )
        time += 60
    }
    return HealthData.DailyDetails(id.tzOffsetSeconds, samples)
}

// ---- DAILY / SUMMARY ----

private fun parseDailySummary(id: HealthFileId, r: HealthByteReader): HealthData.Daily? {
    val (headerSize, slotCount) = when (id.version) {
        3, 4 -> 3 to 21 // Smart Band 8 Active, Mi Band 9 Active
        5 -> 4 to 32 // Mi Band 10 and later
        else -> return null
    }
    val header = r.bytes(headerSize)
    // Every slot is consumed; the bitmap only decides whether the value is kept.
    val widths = intArrayOf(4, 2, 1, 1, 1, 4, 1, 4, 1, 1, 1, 1, 3, 2, 2, 1, 1, 4, 1, 4, 1, 2, 2, 1, 1, 1, 1, 2, 1, 1, 2, 2)
    val slots = arrayOfNulls<Long>(slotCount)
    for (i in 0 until slotCount) {
        // Redmi Watch 5 Active (fw 2.2.105) clears the bits of the two trailing reserved v5 slots and
        // leaves their 4 bytes out; GB reads the CRC there. Slots may end early only at a slot
        // boundary and only when no later slot is marked valid; anything else is still truncation.
        if (r.remaining() == 0 && (i until slotCount).none { validData(header, it) }) break
        val value: Long = when (widths[i]) {
            1 -> r.u8().toLong()
            2 -> r.u16().toLong()
            3 -> (r.u8() or (r.u8() shl 8) or (r.u8() shl 16)).toLong()
            else -> r.i32().toLong()
        }
        if (validData(header, i)) slots[i] = value
    }
    fun int(i: Int): Int? = slots.getOrNull(i)?.toInt()
    fun time(i: Int): Long? = slots.getOrNull(i)
    // 0 % is not a reading: the Redmi Watch 5 Active marks avg SpO2 valid and writes 0.
    fun spo2(i: Int): Int? = int(i)?.takeIf { it != 0 }
    return HealthData.Daily(
        DailySummary(
            time = id.timestamp,
            tzOffsetSeconds = id.tzOffsetSeconds,
            steps = int(0),
            activeCalories = int(1),
            restingHeartRate = int(3),
            maxHeartRate = int(4),
            maxHeartRateTime = time(5),
            minHeartRate = int(6),
            minHeartRateTime = time(7),
            avgHeartRate = int(8),
            avgStress = int(9),
            maxStress = int(10),
            minStress = int(11),
            standingHours = int(12),
            calories = int(13),
            recoveryHours = int(14),
            maxSpo2 = spo2(16),
            maxSpo2Time = time(17),
            minSpo2 = spo2(18),
            minSpo2Time = time(19),
            avgSpo2 = spo2(20),
            trainingLoadDay = int(21),
            trainingLoadWeek = int(22),
            trainingLoadLevel = int(23),
            vitalityIncreaseLight = int(24),
            vitalityIncreaseModerate = int(25),
            vitalityIncreaseHigh = int(26),
            vitalityCurrent = int(27),
        ),
    )
}

// ---- ACTIVITY / SLEEP (0x08) ----

private const val STAGE_MAGIC = 0xfffcfafb.toInt()

private fun parseSleepDetails(id: HealthFileId, r: HealthByteReader): HealthData.Sleep? {
    val v = id.version
    val headerSize = when (v) {
        1, 2, 3, 4 -> 1
        5 -> 2
        else -> return null
    }
    val header = r.bytes(headerSize)
    var idx = 0
    val isAwake = r.u8()
    idx++
    val bedTime = r.i32().toLong()
    idx++
    val wakeupTime = r.i32().toLong()
    idx++
    var quality: Int? = null
    if (v >= 4) {
        if (validData(header, idx)) quality = r.u8()
        idx++
    }
    var intoBed: Long? = null
    if (v >= 5) {
        r.skip(9) // unverified metadata
        intoBed = r.i32().toLong()
        r.skip(4) // second wakeup time, equal to the first
        idx += 5
    }
    // HR (u8), SpO2 (u8) and v3+ snore (f32) series. GB skips them; so does this port.
    // Sample time would be firstRecordTime (bedTime before v2) + unit * index.
    for (sampleSize in if (v >= 3) intArrayOf(1, 1, 4) else intArrayOf(1, 1)) {
        if (validData(header, idx)) {
            r.skip(2) // unit, seconds per sample
            val count = r.i16()
            if (count > 0) {
                if (v >= 2) r.skip(4) // firstRecordTime
                r.skip(count * sampleSize)
            }
        }
        idx++
    }

    val stages = ArrayList<SleepStage>()
    var minutes: IntArray? = null // total, wake, light, rem, deep from the last summary packet
    var dropped = false
    try {
        while (r.remaining() >= 17) {
            if (!findStagePacket(r)) break
            r.u8() // header length, "seems to always be 17"
            val ts = r.i64() // seconds for types 16 and 17
            r.u8() // parity
            val type = r.u8()
            val dataLen = (r.u8() shl 8) or r.u8()
            // These types carry no data; the length bytes are believed to be flags.
            if (type == 0x2 || type == 0x3 || type == 0x9 || type == 0xc || type == 0xd || type == 0xe || type == 0xf || type == 0x12) continue
            val d = HealthByteReader(r.bytes(dataLen), 0, dataLen)
            when (type) {
                16 -> {
                    d.u8() // sleep index << 4 | wake count
                    val total = d.u16be()
                    val wake = d.u16be()
                    val light = d.u16be()
                    val rem = d.u16be()
                    val deep = d.u16be()
                    d.u8() // flags: 0x10 has REM, 0x04 has stages
                    d.u8() // unknown minutes
                    minutes = intArrayOf(total, wake, light, rem, deep)
                }
                17 -> {
                    // The watch rewrites the night as it goes: each 16/17 pair is a fresh snapshot
                    // from bed time, so a later packet replaces every stage from its start on. GB
                    // keys stages by timestamp, so it only overwrites the ones that start at the same time.
                    stages.removeAll { it.start >= ts }
                    var start = ts
                    repeat(dataLen / 2) {
                        limit(stages.size, MAX_SLEEP_STAGES, "sleep stages")
                        val value = d.u16be()
                        val raw = value shr 12
                        val duration = value and 0xFFF
                        stages.add(SleepStage(start, sleepDetailsStage(raw), raw, duration))
                        start += duration * 60L
                    }
                }
                // 1 RR intervals, 0x0A respiration, 0x65 sentinel: not mapped to records.
            }
        }
    } catch (e: HealthTruncatedException) {
        // GB keeps the summary but drops the stages.
        dropped = true
    }
    val m = minutes
    return HealthData.Sleep(
        listOf(
            SleepSession(
                bedTime = bedTime,
                wakeupTime = wakeupTime,
                tzOffsetSeconds = id.tzOffsetSeconds,
                // GB calls this byte isAwake and comments "!isSleepFinish", but every completed night
                // from a Redmi Watch 5 Active (v2, file stamped at wake-up, wake_up packet present)
                // has 1. Read 1 as awake, so finished. No in-progress night has been seen yet.
                finished = isAwake == 1,
                intoBedTime = intoBed,
                sleepQuality = quality,
                totalMinutes = m?.get(0),
                awakeMinutes = m?.get(1),
                lightMinutes = m?.get(2),
                remMinutes = m?.get(3),
                deepMinutes = m?.get(4),
                stages = if (dropped) emptyList() else stages,
                stagesDropped = dropped,
            ),
        ),
    )
}

/** GB readStagePacketHeader: slide one byte at a time until the magic matches. */
private fun findStagePacket(r: HealthByteReader): Boolean {
    while (r.remaining() >= 17) {
        if (r.i32() == STAGE_MAGIC) return true
        r.pos -= 3
    }
    return false
}

/** GB decodeStage, expressed as kinds. */
private fun sleepDetailsStage(raw: Int): SleepStageKind = when (raw) {
    0 -> SleepStageKind.AWAKE
    1 -> SleepStageKind.LIGHT
    2 -> SleepStageKind.DEEP
    3 -> SleepStageKind.REM
    4 -> SleepStageKind.NOT_SLEEPING
    else -> SleepStageKind.UNKNOWN
}

// ---- ACTIVITY / SLEEP_STAGES (0x03) v2 ----

private fun parseSleepStages(id: HealthFileId, r: HealthByteReader): HealthData.Sleep {
    r.skip(7) // unknown, first two bytes always FF FF
    val total = r.i16()
    val bedTime = r.i32().toLong()
    val wakeupTime = r.i32().toLong()
    r.skip(3)
    val deep = r.i16()
    val light = r.i16()
    val rem = r.i16()
    val awake = r.i16()
    if (bedTime == 0L || wakeupTime == 0L || total == 0) return HealthData.Sleep(emptyList())
    r.u8()
    val stages = ArrayList<SleepStage>()
    while (r.remaining() > 0) {
        limit(stages.size, MAX_SLEEP_STAGES, "sleep stages")
        val time = r.i32().toLong()
        val raw = r.u8()
        stages.add(SleepStage(time, sleepStagesStage(raw), raw, null))
    }
    return HealthData.Sleep(
        listOf(
            SleepSession(
                bedTime = bedTime,
                wakeupTime = wakeupTime,
                tzOffsetSeconds = id.tzOffsetSeconds,
                finished = true,
                intoBedTime = null,
                sleepQuality = null,
                totalMinutes = total,
                deepMinutes = deep,
                lightMinutes = light,
                remMinutes = rem,
                awakeMinutes = awake,
                stages = stages,
                stagesDropped = false,
            ),
        ),
    )
}

/** GB stores these raw and reads them as its stage codes; whether the watch uses that table is unverified. */
private fun sleepStagesStage(raw: Int): SleepStageKind = when (raw) {
    0 -> SleepStageKind.NOT_SLEEPING
    2 -> SleepStageKind.DEEP
    3 -> SleepStageKind.LIGHT
    4 -> SleepStageKind.REM
    5 -> SleepStageKind.AWAKE
    else -> SleepStageKind.UNKNOWN
}

// ---- ACTIVITY / MANUAL_SAMPLES ----

private fun parseManualSamples(id: HealthFileId, pad: Int, r: HealthByteReader): HealthParseResult {
    val samples = ArrayList<ManualSample>()
    when (id.version) {
        1 -> {
            // Mi Band 9 Active: 0xFF header, then 6-byte records.
            if (pad != 0) return HealthParseResult.Invalid("Manual samples v1: non-zero pad byte", id)
            if (r.u8() != 0xFF) return HealthParseResult.Invalid("Manual samples v1: bad header", id)
            if (r.remaining() == 0 || r.remaining() % 6 != 0) return HealthParseResult.Invalid("Manual samples v1: bad length ${r.remaining()}", id)
            while (r.remaining() > 0) {
                limit(samples.size, MAX_MANUAL_SAMPLES, "manual samples")
                val time = r.i32().toLong()
                val type = r.u8()
                val value = r.u8()
                val kind = when (type) {
                    0x02 -> ManualSampleKind.SPO2
                    0x03 -> ManualSampleKind.STRESS
                    else -> return HealthParseResult.Invalid("Manual samples v1: unknown type $type", id)
                }
                if (value == 0 || value > 100) return HealthParseResult.Invalid("Manual samples v1: value $value out of range", id)
                samples.add(ManualSample(time, kind, value))
            }
        }
        2 -> {
            // No header. GB FIXME: the type byte is really two nibbles (length + type), see GB #3517.
            while (r.remaining() > 0) {
                limit(samples.size, MAX_MANUAL_SAMPLES, "manual samples")
                val time = r.i32().toLong()
                val type = r.u8()
                val (kind, value) = when (type) {
                    0x11 -> ManualSampleKind.HEART_RATE to r.u8()
                    0x12 -> ManualSampleKind.SPO2 to r.u8()
                    0x13 -> ManualSampleKind.STRESS to r.u8()
                    0x44 -> ManualSampleKind.TEMPERATURE to r.i32()
                    // Unknown record size, so the rest of the file cannot be read.
                    else -> return HealthParseResult.Unsupported(id, "Manual samples v2: unknown record type 0x" + Integer.toHexString(type))
                }
                if (value != 0) samples.add(ManualSample(time, kind, value))
            }
        }
        else -> return HealthParseResult.Unsupported(id, "Manual samples v${id.version}")
    }
    return HealthParseResult.Parsed(id, HealthData.Manual(id.tzOffsetSeconds, samples))
}

// ---- bounded little-endian reader ----

internal class HealthTruncatedException(message: String) : RuntimeException(message)

internal class HealthLimitException(message: String) : RuntimeException(message)

/** Throws before the record that would exceed [max]. */
private fun limit(count: Int, max: Int, what: String) {
    if (count >= max) throw HealthLimitException("More than $max $what")
}

/** Reads [buf] between [pos] and [limit]; any read past [limit] throws [HealthTruncatedException]. */
internal class HealthByteReader(private val buf: ByteArray, var pos: Int, private val limit: Int) {
    fun remaining(): Int = limit - pos

    private fun need(n: Int): Int {
        if (n < 0 || n > limit - pos) throw HealthTruncatedException("need $n bytes at $pos, have ${limit - pos}")
        val at = pos
        pos += n
        return at
    }

    private fun at(i: Int): Int = buf[i].toInt() and 255

    fun skip(n: Int) {
        need(n)
    }

    fun bytes(n: Int): ByteArray {
        val at = need(n)
        return buf.copyOfRange(at, at + n)
    }

    fun previousByte(): Int {
        if (pos < 1) throw HealthTruncatedException("no previous byte")
        return at(pos - 1)
    }

    fun u8(): Int = at(need(1))

    fun u16(): Int {
        val i = need(2)
        return at(i) or (at(i + 1) shl 8)
    }

    fun i16(): Int = u16().toShort().toInt()

    fun u16be(): Int {
        val i = need(2)
        return (at(i) shl 8) or at(i + 1)
    }

    fun i32(): Int {
        val i = need(4)
        return at(i) or (at(i + 1) shl 8) or (at(i + 2) shl 16) or (at(i + 3) shl 24)
    }

    fun i64(): Long {
        val lo = i32().toLong() and 0xFFFFFFFFL
        val hi = i32().toLong()
        return (hi shl 32) or lo
    }

    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
}
