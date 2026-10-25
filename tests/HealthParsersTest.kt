/*  Copyright (C) 2023-2026 José Rebelo, Dany Mestas
    Copyright (C) 2026 Gadgetbridge contributors

    Byte fixtures and expected values ported from Gadgetbridge's XiaomiActivityFileIdTest,
    DailySummaryParserTest, ManualSamplesParserTest and WorkoutSummaryParserTest.

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

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.zip.CRC32
import kotlin.system.exitProcess
import local.hyprwatch.WorkoutMetric as M

object HealthParsersTest {
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

    private fun near(actual: Number?, expected: Double, tolerance: Double, name: String) =
        check(actual != null && Math.abs(actual.toDouble() - expected) <= tolerance, "$name: expected <$expected>, got <$actual>")

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun withCrc(b: ByteArray): ByteArray {
        val crc = CRC32()
        crc.update(b, 0, b.size - 4)
        val v = crc.value
        for (i in 0 until 4) b[b.size - 4 + i] = (v ushr (8 * i)).toByte()
        return b
    }

    /** [id][pad 0][body][crc]. */
    private fun file(id: HealthFileId, body: ByteArray): ByteArray = withCrc(id.toBytes() + byteArrayOf(0) + body + ByteArray(4))

    private fun id(ts: Long, tz: Int, version: Int, type: HealthFileType, subtype: Int, detail: HealthDetailType) =
        HealthFileId(ts, tz, version, type, subtype, detail)

    private class Le {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v) }
        fun u16(v: Int) = apply { u8(v and 255); u8((v shr 8) and 255) }
        fun be16(v: Int) = apply { u8((v shr 8) and 255); u8(v and 255) }
        fun i32(v: Long) = apply { for (i in 0 until 4) u8(((v shr (8 * i)) and 255).toInt()) }
        fun i64(v: Long) = apply { for (i in 0 until 8) u8(((v shr (8 * i)) and 255).toInt()) }
        fun f32(v: Float) = i32(java.lang.Float.floatToIntBits(v).toLong())
        fun raw(b: ByteArray) = apply { out.write(b, 0, b.size) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    /** The parsed data when it is a [T]; any other result is a recorded failure. */
    private inline fun <reified T : HealthData> parsed(bytes: ByteArray, name: String): T? {
        val r = parseHealthFile(bytes)
        val data = (r as? HealthParseResult.Parsed)?.data
        if (data !is T) {
            check(false, "$name: expected Parsed ${T::class.simpleName}, got $r")
            return null
        }
        return data
    }

    private fun invalid(bytes: ByteArray, reason: String, name: String) {
        val r = parseHealthFile(bytes)
        check(r is HealthParseResult.Invalid && r.reason == reason, "$name: expected Invalid($reason), got $r")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        fileId()
        dailySummary()
        manualSamples()
        workoutSummaries()
        dailyDetails()
        sleep()
        layoutErrors()
        recordCaps()
        robustness()
        redmiWatch5Active()
        if (failures > 0) {
            System.err.println("$failures of $checks health parser checks failed")
            exitProcess(1)
        }
        println("Health parsers: $checks checks passed")
    }

    // ---- XiaomiActivityFileIdTest ----

    private fun fileId() {
        val d = HealthFileId.parse(hex("21F328650403A0"))!!
        eq(d.timestamp, 1697182497L, "id decode timestamp")
        eq(d.timezone, 4, "id decode timezone")
        eq(d.version, 3, "id decode version")
        eq(d.type, HealthFileType.SPORTS, "id decode type")
        eq(d.knownSubtype, HealthSubtype.SPORTS_FREESTYLE, "id decode subtype")
        eq(d.detailType, HealthDetailType.DETAILS, "id decode detail type")
        eq(d.tzOffsetSeconds, 3600, "id tz offset")

        val all = hex("21F328650403A021F3286504008C")
        val encoded = HealthFileId.parse(all, 0)!!.toBytes() + HealthFileId.parse(all, 7)!!.toBytes()
        check(encoded.contentEquals(all), "id decode/encode round trip")
        eq(HealthFileId.parse(all, 7)!!.knownSubtype, HealthSubtype.SPORTS_TREADMILL, "id 0x8C is treadmill details")
        eq(HealthFileId.parse(all, 8), null, "id needs 7 bytes")
        eq(HealthFileId.fromHex("21f328650403a0"), d, "id from hex")
        eq(d.toHex(), "21f328650403a0", "id to hex")
        eq(HealthFileId.fromHex("21f328650403zz"), null, "bad hex rejected")
        eq(HealthFileId.fromHex("21F328650403A0"), d, "upper-case hex accepted")
        eq(HealthFileId.fromHex("\uFF12\uFF11f328650403a0"), null, "fullwidth digits rejected")
        eq(HealthFileId.fromHex("\u0662\u0661f328650403a0"), null, "Arabic-Indic digits rejected")

        val zero = HealthFileId.parse(ByteArray(7))!!
        check(zero.timestamp == 0L && zero.version == 0, "zero id")
        val negTz = HealthFileId.parse(hex("C297BD6AF405D9"))!!
        eq(negTz.timezone, -12, "negative timezone")
        eq(negTz.tzOffsetSeconds, -3 * 3600, "negative tz offset")
        eq(negTz.knownSubtype, HealthSubtype.SPORTS_OUTDOOR_WALKING_V2, "walking v2 subtype")
        eq(negTz.detailType, HealthDetailType.SUMMARY, "summary detail type")

        val details = id(100, 0, 1, HealthFileType.SPORTS, 8, HealthDetailType.DETAILS)
        val summary = id(100, 0, 9, HealthFileType.SPORTS, 8, HealthDetailType.SUMMARY)
        val gps = id(100, 0, 0, HealthFileType.SPORTS, 8, HealthDetailType.GPS_TRACK)
        val earlier = id(99, 0, 1, HealthFileType.SPORTS, 8, HealthDetailType.GPS_TRACK)
        eq(listOf(gps, details, summary, earlier).sorted(), listOf(earlier, summary, details, gps), "id ordering: time, then summary, details, gps")
    }

    // ---- DailySummaryParserTest ----

    private val dailyV5 = id(1_700_000_000L, 4, 5, HealthFileType.ACTIVITY, 0, HealthDetailType.SUMMARY)
    private val dailyV3 = id(1_700_000_000L, 4, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.SUMMARY)

    private fun v5BodyAllValues(): ByteArray = Le()
        .i32(12345).u16(250).u8(75).u8(60).u8(180).i32(1700001000).u8(55).i32(1700002000).u8(80)
        .u8(40).u8(90).u8(10).raw(byteArrayOf(1, 1, 1)).u16(2200).u16(480).u8(96)
        .u8(99).i32(1700003000).u8(92).i32(1700004000).u8(95)
        .u16(130).u16(700).u8(2).u8(5).u8(25).u8(40).u16(280).u8(3).u8(30).u16(7).u16(65)
        .bytes()

    private fun daily(id: HealthFileId, bitmap: ByteArray, body: ByteArray, name: String): DailySummary? =
        parsed<HealthData.Daily>(file(id, bitmap + body), name)?.summary

    private fun dailySummary() {
        eq(v5BodyAllValues().size, 57, "v5 body size")
        val all = byteArrayOf(-1, -1, -1, -1)
        daily(dailyV5, all, v5BodyAllValues(), "v5 all bits")?.let { s ->
            eq(s.time, 1_700_000_000L, "v5 time")
            eq(s.tzOffsetSeconds, 3600, "v5 tz")
            eq(s.steps, 12345, "v5 steps")
            eq(s.activeCalories, 250, "v5 active calories")
            eq(s.restingHeartRate, 60, "v5 resting hr")
            eq(s.maxHeartRate, 180, "v5 max hr")
            eq(s.maxHeartRateTime, 1700001000L, "v5 max hr ts")
            eq(s.minHeartRate, 55, "v5 min hr")
            eq(s.minHeartRateTime, 1700002000L, "v5 min hr ts")
            eq(s.avgHeartRate, 80, "v5 avg hr")
            eq(s.avgStress, 40, "v5 avg stress")
            eq(s.maxStress, 90, "v5 max stress")
            eq(s.minStress, 10, "v5 min stress")
            eq(s.standingHours, 0x010101, "v5 standing")
            eq(s.calories, 2200, "v5 calories")
            eq(s.recoveryHours, 480, "v5 recovery")
            eq(s.maxSpo2, 99, "v5 max spo2")
            eq(s.maxSpo2Time, 1700003000L, "v5 max spo2 ts")
            eq(s.minSpo2, 92, "v5 min spo2")
            eq(s.minSpo2Time, 1700004000L, "v5 min spo2 ts")
            eq(s.avgSpo2, 95, "v5 avg spo2")
            eq(s.trainingLoadDay, 130, "v5 load day")
            eq(s.trainingLoadWeek, 700, "v5 load week")
            eq(s.trainingLoadLevel, 2, "v5 load level")
            eq(s.vitalityIncreaseLight, 5, "v5 vitality light")
            eq(s.vitalityIncreaseModerate, 25, "v5 vitality moderate")
            eq(s.vitalityIncreaseHigh, 40, "v5 vitality high")
            eq(s.vitalityCurrent, 280, "v5 vitality current")
        }

        daily(dailyV5, ByteArray(4), ByteArray(57) { -1 }, "v5 no bits")?.let { s ->
            check(
                listOf(s.steps, s.activeCalories, s.restingHeartRate, s.maxHeartRate, s.maxHeartRateTime, s.standingHours, s.calories,
                    s.recoveryHours, s.trainingLoadDay, s.vitalityIncreaseLight, s.vitalityIncreaseModerate, s.vitalityIncreaseHigh,
                    s.vitalityCurrent).all { it == null },
                "v5 no bits set persists nothing",
            )
        }

        daily(dailyV5, byteArrayOf(0x80.toByte(), 0, 0, 0x10), v5BodyAllValues(), "v5 partial vitality")?.let { s ->
            eq(s.steps, 12345, "partial: steps")
            eq(s.vitalityCurrent, 280, "partial: vitality current")
            check(listOf(s.vitalityIncreaseLight, s.vitalityIncreaseModerate, s.vitalityIncreaseHigh, s.activeCalories, s.maxHeartRate, s.calories)
                .all { it == null }, "partial: invalid bits yield null")
        }

        for (slot in intArrayOf(0, 7, 8, 23)) {
            val bitmap = ByteArray(4)
            bitmap[slot / 8] = (1 shl (7 - slot % 8)).toByte()
            val s = daily(dailyV5, bitmap, v5BodyAllValues(), "msb slot $slot") ?: continue
            when (slot) {
                0 -> check(s.steps != null && s.activeCalories == null, "slot 0 msb-first")
                7 -> check(s.minHeartRateTime != null && s.minHeartRate == null, "slot 7 msb-first")
                8 -> check(s.avgHeartRate != null && s.minHeartRateTime == null, "slot 8 msb-first")
                23 -> check(s.trainingLoadLevel != null && s.trainingLoadWeek == null, "slot 23 msb-first")
            }
        }

        val le = ByteArray(57)
        le[0] = 1; le[1] = 2; le[2] = 3; le[3] = 4
        val slot27 = 4 + 2 + 1 + 1 + 1 + 4 + 1 + 4 + 1 + 1 + 1 + 1 + 3 + 2 + 2 + 1 + 1 + 4 + 1 + 4 + 1 + 2 + 2 + 1 + 1 + 1 + 1
        le[slot27] = 2; le[slot27 + 1] = 1
        daily(dailyV5, byteArrayOf(0x80.toByte(), 0, 0, 0x10), le, "v5 little endian")?.let { s ->
            eq(s.steps, 0x04030201, "LE steps")
            eq(s.vitalityCurrent, 0x0102, "LE vitality current")
        }

        val st = ByteArray(57)
        val standingOffset = 4 + 2 + 1 + 1 + 1 + 4 + 1 + 4 + 1 + 1 + 1 + 1
        st[standingOffset] = 0xAA.toByte(); st[standingOffset + 1] = 0xBB.toByte(); st[standingOffset + 2] = 0xCC.toByte()
        daily(dailyV5, byteArrayOf(0, 0x08, 0, 0), st, "v5 standing")?.let { eq(it.standingHours, 0xCCBBAA, "standing packs 3 bytes") }

        val v99 = id(1_700_000_000L, 4, 99, HealthFileType.ACTIVITY, 0, HealthDetailType.SUMMARY)
        check(parseHealthFile(file(v99, ByteArray(1))) is HealthParseResult.Unsupported, "daily summary v99 unsupported")

        val v3Body = v5BodyAllValues().copyOfRange(0, 41)
        daily(dailyV3, byteArrayOf(-1, -1, -1), v3Body, "v3 all bits")?.let { s ->
            eq(s.steps, 12345, "v3 steps")
            eq(s.activeCalories, 250, "v3 active calories")
            eq(s.restingHeartRate, 60, "v3 resting hr")
            eq(s.maxHeartRate, 180, "v3 max hr")
            eq(s.maxHeartRateTime, 1700001000L, "v3 max hr ts")
            eq(s.minHeartRate, 55, "v3 min hr")
            eq(s.minHeartRateTime, 1700002000L, "v3 min hr ts")
            eq(s.avgHeartRate, 80, "v3 avg hr")
            eq(s.avgStress, 40, "v3 avg stress")
            eq(s.maxStress, 90, "v3 max stress")
            eq(s.minStress, 10, "v3 min stress")
            eq(s.standingHours, 0x010101, "v3 standing")
            eq(s.calories, 2200, "v3 calories")
            eq(s.recoveryHours, 480, "v3 recovery")
            eq(s.maxSpo2, 99, "v3 max spo2")
            eq(s.maxSpo2Time, 1700003000L, "v3 max spo2 ts")
            eq(s.minSpo2, 92, "v3 min spo2")
            eq(s.minSpo2Time, 1700004000L, "v3 min spo2 ts")
            eq(s.avgSpo2, 95, "v3 avg spo2")
            check(listOf(s.trainingLoadDay, s.trainingLoadWeek, s.trainingLoadLevel, s.vitalityIncreaseLight, s.vitalityIncreaseModerate,
                s.vitalityIncreaseHigh, s.vitalityCurrent).all { it == null }, "v3 has no v5 slots")
        }
        daily(dailyV3, ByteArray(3), ByteArray(41) { -1 }, "v3 no bits")?.let { s ->
            check(listOf(s.steps, s.activeCalories, s.restingHeartRate, s.maxHeartRate, s.maxHeartRateTime, s.standingHours, s.calories,
                s.recoveryHours, s.avgSpo2).all { it == null }, "v3 no bits set persists nothing")
        }
        daily(dailyV3, byteArrayOf(0x08, 0, 0), v3Body, "v3 partial")?.let { s ->
            eq(s.maxHeartRate, 180, "v3 partial max hr")
            check(listOf(s.steps, s.activeCalories, s.restingHeartRate, s.maxHeartRateTime, s.minHeartRate, s.avgHeartRate, s.standingHours,
                s.calories, s.avgSpo2).all { it == null }, "v3 partial gates per field")
        }
        // A v5 file cut short is Invalid, not a summary with missing fields.
        invalid(file(dailyV5, all + v5BodyAllValues().copyOfRange(0, 50)), "Truncated: need 2 bytes at 61, have 1", "short daily summary invalid")
    }

    // ---- ManualSamplesParserTest ----

    private fun manual(bytes: ByteArray, name: String): List<ManualSample>? = parsed<HealthData.Manual>(bytes, name)?.samples

    private fun manualSamples() {
        val spo2 = "EFB9856AF4011800FFEFB9856A0261511E4648"
        manual(hex(spo2), "v1 spo2")?.let {
            eq(it.size, 1, "v1 spo2 count")
            eq(it.firstOrNull(), ManualSample(1787148783L, ManualSampleKind.SPO2, 97), "v1 spo2 sample")
        }
        manual(hex("65C0856AF4011800FF65C0856A03288CA20578"), "v1 stress 40")?.let {
            eq(it.map { s -> s.kind to s.value }, listOf(ManualSampleKind.STRESS to 40), "v1 stress 40")
        }
        manual(hex("D1C3856AF4011800FFD1C3856A031BFBF91375"), "v1 stress 27")?.let {
            eq(it.map { s -> s.kind to s.value }, listOf(ManualSampleKind.STRESS to 27), "v1 stress 27")
        }
        val reasons = mapOf("header" to "Manual samples v1: bad header", "type" to "Manual samples v1: unknown type 4", "value" to "Manual samples v1: value 101 out of range")
        for ((index, value, name) in listOf(Triple(8, 0, "header"), Triple(13, 4, "type"), Triple(14, 101, "value"))) {
            val p = hex(spo2)
            p[index] = value.toByte()
            invalid(withCrc(p), reasons.getValue(name), "v1 rejects bad $name")
        }

        // v2 records from the comment in GB's ManualSamplesParser.
        val v2 = id(1_705_610_000L, 0, 2, HealthFileType.ACTIVITY, 6, HealthDetailType.DETAILS)
        val body = hex("8A90A965126" + "3" + "C793A9651300" + "9698A965131C" + "E79CA965445A0E0000" + "729FA96511" + "48")
        manual(file(v2, body), "v2")?.let {
            eq(it, listOf(
                ManualSample(0x65A9908AL, ManualSampleKind.SPO2, 0x63),
                ManualSample(0x65A99896L, ManualSampleKind.STRESS, 0x1C),
                ManualSample(0x65A99CE7L, ManualSampleKind.TEMPERATURE, 0x0E5A),
                ManualSample(0x65A99F72L, ManualSampleKind.HEART_RATE, 0x48),
            ), "v2 samples, zero skipped")
        }
        val r = parseHealthFile(file(v2, hex("8A90A9652563")))
        check(r is HealthParseResult.Unsupported && r.reason.contains("0x25"), "v2 unknown record type unsupported: $r")
    }

    // ---- WorkoutSummaryParserTest ----

    private const val TREADMILL_V11_24APR =
        "ErHraQgLjQD7/+/vkf/AAD8SsetpMLjraRgHAAB/EQAAiQCVAQAAWAEAABMDAAAnEwAAWACj" +
            "AKgAp7NeZmaGQAAAAAAoAOkBAADHBAAAPQAAAA4AAAAQAAAAugAXBwAAZmbmPwADAAAA" +
            "AAAAAAAAoA8AAAAAAAAAAAAAAACUEQAAaAADAAAAAAAAAAAAAAAAAAAMAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAADyuEua"
    private const val TREADMILL_V9_21JUN =
        "WCA4aggJjQD//H+QDiBYIDhqhyY4aigGAACgCgAAwAByAQAAlAUAAJAMAAC0AIelZDMz8z8C" +
            "AAAAIAAAAAAAcAAAAPwCAAB0AQAAOgEAAOgAKAYAAAAAAAABAwAAAFgCAAAsAYgTAABK" +
            "AQAAAADIQQAAoAoAABwAAQAAAAAAAA8AAAAAAAAAAAAAAAAAAwAAh6VkA1B6ALQAhdLG" +
            "QAisHEFYIDhqhyY4agAAAAAoBgAAKAYAAAAAAAAAAAAAwAAAAKAKAACQDAAAQwIAAHIB" +
            "AAAAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAAAAAAAAAHAAAAD8AgAAdAEAADoBAAAA" +
            "AAIBMzPzPwAAAAAAAAAADwAcACAAAAAAAAAAAAAAAAAAAAAAAaiMNkSR"
    private const val ROWING_V7_20APR =
        "+l3maQgHtQD/v/N4//pd5mnwZ+Zp9QkAANYAjqRcmplZQAAAFwAAAAAAoQIAAGoFAACwAAA" +
            "AFAEAABsBuAQAABwAAABHAAAAAAAAAAAAAABnAQAABfUJAADNzMw+AAAAAAAAAAAAAABF" +
            "AAIAAJGg1DA="
    private const val ROWING_V7_15APR =
        "8srfaQgHtQD/v/N4//LK32nQ1N9p3QkAANQAkapfAABgQAAAHAAAAAAAGwUAAKwCAAD+AAA" +
            "AAwEAABkBTAQAABoAAAApAAAAAAAAAAAAAAAAAAAAAd0JAADNzIw/AAAAAAAAAAAAAABN" +
            "AAMAAAusxH0="
    private const val ROWING_V8_22AUG =
        "JaCJaggItQD/v/+b8X8loIlquaCJapMAAAAOAIGRapqZmT4BAAAAAAAAAAAAAAAzAAAAOwA" +
            "AACUAAAAAAAAAABMALgAAABIAAAAbAAAAAAAAAAAAAAAAAAAAAZMAAAAAAAAAAQAAAAAAAA" +
            "AAAAAAAAAAAAIAAAAAAAB+WRxL"
    private const val FREESTYLE_V10_FRISBEE =
        "HIK5aQQKoQD+Bv7/wHccgrlpcJO5aVMRAABwAZG8WgAAAAAAAAAAgEAAACMAWgEAAEIEAAA" +
            "FBwAAtAMAANUAAADoAVMRAABmZiZAACcDAAAAAAAAAACIAAMAAAAAAAAAAAAAAAAAAAAA" +
            "aQBdAAcAAEblwNo="
    private const val OUTDOOR_CYCLING_V8_23SEP =
        "doGzaggI3QD3/n/X/4chPwYAdoGzaqGHs2orBgAAKwYAAKIUAACmAH8ATAAAAAwEAABRrkBBnbQ7QoWo" +
            "UAAAAAAAAAAAAAAAAAAAAAAAAAAAMzMTQADNzEw/AAAAAAAAAAAADQAAAAAAAF8AAAAuAwAAVAEA" +
            "ABsBAAAAAAAAAAAAAAAAAAAAAAAAAAAAABwAAAAAAAAAAQAAAAAAAAAABwAAAAAAAAAAy+id1Q=="
    private const val OUTDOOR_CYCLING_V6_23MAY =
        "V7kRaggG3QD3//9fw5CPBgBXuRFqJsoRas8QAADPEAAAzi4AABoCtwFoAAAApCYAACNkIEG56wlC" +
            "W41IAAAAAAAAAAAAAAAAAAAAAAAAAACamRk/AAAAAAAAAAAAAAAAAAAAAAAAAAAKAAAAYQAAAFYE" +
            "AAAAAAAAAAAAAAAAAAAAAAAFAAEAAAAAAAAAAAAAAAAAAAAAAAAAANY0wh8="
    private const val OUTDOOR_RUN_V5_30SEP =
        "wpe9avQF2QD///////8AAAABAMKXvWprnr1qqAYAAKgGAABdEAAACwHkAJYBAACLAQAADgMA" +
            "AJmZDUEbkhFBThAAAGQAkgCyAKLIdwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAB8CAABEAQAAOQIAAAUBAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADqDW67"
    private const val WALKING_V6_29MAY =
        "lrAZaggG2QD3///4/gf/AAACAJawGWoPuBlqeAcAAHgHAABzDQAAHQHpACsCAAB7AQAAdAMA" +
            "AMFxz0B2phdB9g4AAE8AfQCtAJXAgwAAAAAAAAAAAAAAAAAAAAAAAAAAAABAQAAzM/M/AAAA" +
            "ABMAAHEAAAAYAgAAagQAAHMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPQACAAAAAAAA" +
            "AAAAAAAAAAAbAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAUDnru"
    private const val WALKING_V9_04JAN =
        "glJaaQQJ2QD3///4P4AAf/AAAAAfAgCDUlppIVhaaZ0FAACdBQAACAoAAM8ArwAjAgAA1gEA" +
            "AFYDAABv3s1ANA31QKYLAABVAH8AhwCHo2MAAAAAAAAAAAAAAAAAAAAAAAAAAGZmJkAAAAAA" +
            "AAAAAAAAAAAAABAAAAAAAADVAAAAbQIAAO8BAABQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABwAAQAAAAAAAAAAAAAAAAAACgAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAALE479"
    private const val INDOOR_CYCLING_V9_22FEB =
        "UB+6ZwQJnQD/7++f/kOAf1Afume6KLpnagkAAAAAAACIAgAAAACHll6amTlAAABIAAAAAAAA" +
            "AAAAbQQAAMQEAAAjAAAARwNqCQAAAAAAAAAAAAgHAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAFUAAgAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAGaZY0A=="
    private const val ELLIPTICAL_V6_10FEB =
        "6FuqZwQGrQD/9/+P6FuqZ+tiqmcDBwAApgJoAQAADACuAJOcdgAAYEAAAEgAAAAAABUAAAC8" +
            "BgAAHQAAAAAAAAAGAwMHAACamZk+AAAAAAAAAAAAAABgAAIDAC12aDs="
    private const val OUTDOOR_CYCLING_V2_V3_04OCT =
        "oTrCaggDmQD////AoTrCavBgwmpmGQAAqkYAAEoCxwAAAMwCAACLKwtCesY7nNnmQzUz4kPZ5u5DZmYYRGaml0MA" +
            "AEBAMhceAFUAAAAiAQAAEwYAAFQHAACnBwAA2QIAAAAAAAAAAOyp/+M="
    private const val OUTDOOR_WALKING_V1_V3_03OCT =
        "JB/BaggDiQD////wJB/Bah8nwWr4BwAAzAUAAFIALwMAAAAAAAD877RAsAgAAJYAVm1HAAAAQDAzW0KVU55DM7Ox" +
            "QzMzlUOamZk+MgEAAAAAAAAAAAAAAAAAAAAAAACPAQAAfwAAAAAAAAAAAOU1xJ8="

    private val workoutFixtures = listOf(
        TREADMILL_V11_24APR, TREADMILL_V9_21JUN, ROWING_V7_20APR, ROWING_V7_15APR, ROWING_V8_22AUG, FREESTYLE_V10_FRISBEE,
        OUTDOOR_CYCLING_V8_23SEP, OUTDOOR_CYCLING_V6_23MAY, OUTDOOR_RUN_V5_30SEP, WALKING_V6_29MAY, WALKING_V9_04JAN,
        INDOOR_CYCLING_V9_22FEB, ELLIPTICAL_V6_10FEB, OUTDOOR_CYCLING_V2_V3_04OCT, OUTDOOR_WALKING_V1_V3_03OCT,
    )

    private fun workout(bytes: ByteArray, name: String): WorkoutSummary? = parsed<HealthData.Workout>(bytes, name)?.summary

    private fun workoutSummaries() {
        workout(b64(TREADMILL_V11_24APR), "treadmill v11")?.let { w ->
            near(w.distanceMeters, 4479.0, 0.001, "treadmill v11 distance")
            near(w.metrics[M.TRAINING_EFFECT_AEROBIC], 4.2, 0.01, "treadmill v11 aerobic")
            near(w.hrAvg, 167.0, 0.001, "treadmill v11 hr avg")
            near(w.metrics[M.CADENCE_AVG], 163.0, 0.001, "treadmill v11 cadence avg")
            near(w.metrics[M.TRAINING_EFFECT_ANAEROBIC], 1.8, 0.01, "treadmill v11 anaerobic")
            near(w.metrics[M.DISTANCE_GOAL_METERS], 4000.0, 0.001, "treadmill v11 distance goal")
            near(w.metrics[M.DISTANCE_METERS_CALIBRATED], 4500.0, 0.001, "treadmill v11 calibrated")
            near(w.metrics[M.WORKOUT_LOAD], 104.0, 0.001, "treadmill v11 load")
            near(w.metrics[M.VITALITY_GAIN], 12.0, 0.001, "treadmill v11 vitality")
            eq(w.sport, WorkoutSport.TREADMILL, "treadmill sport")
        }
        workout(b64(TREADMILL_V9_21JUN), "treadmill v9")?.let { w ->
            near(w.activeCalories, 192.0, 0.001, "treadmill v9 calories")
            near(w.distanceMeters, 2720.0, 0.001, "treadmill v9 distance")
            near(w.steps, 3216.0, 0.001, "treadmill v9 steps")
            near(w.hrAvg, 135.0, 0.001, "treadmill v9 hr avg")
            near(w.metrics[M.CADENCE_MAX], 180.0, 0.001, "treadmill v9 cadence max")
            near(w.metrics[M.TRAINING_EFFECT_AEROBIC], 1.9, 0.01, "treadmill v9 aerobic")
            near(w.metrics[M.RECOVERY_TIME], 32.0, 0.001, "treadmill v9 recovery")
            near(w.metrics[M.WORKOUT_LOAD], 28.0, 0.001, "treadmill v9 load")
            near(w.metrics[M.VITALITY_GAIN], 15.0, 0.001, "treadmill v9 vitality")
        }
        workout(b64(ROWING_V7_20APR), "rowing v7 20apr")?.let { w ->
            near(w.hrAvg, 142.0, 0.001, "rowing v7 hr avg")
            near(w.activeSeconds, 2549.0, 0.001, "rowing v7 active seconds")
            near(w.metrics[M.STROKES], 1208.0, 0.001, "rowing v7 strokes")
            near(w.metrics[M.STROKE_RATE_AVG], 28.0, 0.001, "rowing v7 stroke rate avg")
            near(w.metrics[M.TRAINING_EFFECT_AEROBIC], 3.4, 0.01, "rowing v7 aerobic")
            near(w.metrics[M.TRAINING_EFFECT_ANAEROBIC], 0.4, 0.01, "rowing v7 anaerobic")
            near(w.metrics[M.STROKE_RATE_MAX], 71.0, 0.001, "rowing v7 stroke rate max")
            near(w.metrics[M.RECOVERY_TIME], 23.0, 0.001, "rowing v7 recovery")
            near(w.metrics[M.WORKOUT_LOAD], 69.0, 0.001, "rowing v7 load")
            near(w.metrics[M.VITALITY_GAIN], 0.0, 0.001, "rowing v7 vitality")
            eq(w.sport, WorkoutSport.ROWING, "rowing sport (GB subtype default)")
        }
        workout(b64(ROWING_V7_15APR), "rowing v7 15apr")?.let { w ->
            near(w.metrics[M.TRAINING_EFFECT_ANAEROBIC], 1.1, 0.01, "rowing v7b anaerobic")
            near(w.metrics[M.TRAINING_EFFECT_AEROBIC], 3.5, 0.01, "rowing v7b aerobic")
            near(w.metrics[M.STROKE_RATE_MAX], 41.0, 0.001, "rowing v7b stroke rate max")
        }
        workout(b64(FREESTYLE_V10_FRISBEE), "freestyle v10")?.let { w ->
            near(w.metrics[M.TRAINING_EFFECT_ANAEROBIC], 2.6, 0.01, "freestyle anaerobic")
            near(w.metrics[M.TRAINING_EFFECT_AEROBIC], 4.0, 0.01, "freestyle aerobic")
            near(w.metrics[M.THROWS_LOW], 105.0, 0.001, "freestyle throws low")
            near(w.metrics[M.THROWS_MEDIUM], 93.0, 0.001, "freestyle throws medium")
            near(w.metrics[M.THROWS_HIGH], 7.0, 0.001, "freestyle throws high")
            eq(w.xiaomiWorkoutType, 807, "freestyle workout type")
            eq(w.sport, WorkoutSport.FRISBEE, "freestyle 807 is frisbee")
        }
        workout(b64(ROWING_V8_22AUG), "rowing v8")?.let { w ->
            near(w.activeSeconds, 147.0, 0.001, "rowing v8 active")
            near(w.hrAvg, 129.0, 0.001, "rowing v8 hr avg")
            near(w.hrMax, 145.0, 0.001, "rowing v8 hr max")
            near(w.hrMin, 106.0, 0.001, "rowing v8 hr min")
            near(w.metrics[M.STROKES], 46.0, 0.001, "rowing v8 strokes")
            near(w.metrics[M.STROKE_RATE_AVG], 18.0, 0.001, "rowing v8 stroke avg")
            near(w.metrics[M.STROKE_RATE_MAX], 27.0, 0.001, "rowing v8 stroke max")
            val zones = listOf(M.HR_ZONE_EXTREME_SECONDS, M.HR_ZONE_ANAEROBIC_SECONDS, M.HR_ZONE_AEROBIC_SECONDS,
                M.HR_ZONE_FAT_BURN_SECONDS, M.HR_ZONE_WARM_UP_SECONDS).sumOf { w.metrics[it] ?: Double.NaN }
            near(zones, 147.0, 0.001, "rowing v8 zones add up to active seconds")
        }
        val cyclingV8 = b64(OUTDOOR_CYCLING_V8_23SEP)
        workout(cyclingV8, "cycling v8")?.let { w ->
            eq(w.sport, WorkoutSport.OUTDOOR_CYCLING, "cycling v8 sport")
            near(w.activeSeconds, 1579.0, 0.001, "cycling v8 active")
            near(w.distanceMeters, 5282.0, 0.001, "cycling v8 distance")
            near(w.totalCalories, 166.0, 0.001, "cycling v8 total calories")
            near(w.activeCalories, 127.0, 0.001, "cycling v8 calories")
            near(w.metrics[M.PACE_MAX], 76.0, 0.001, "cycling v8 pace max")
            near(w.metrics[M.PACE_MIN], 1036.0, 0.001, "cycling v8 pace min")
            near(w.metrics[M.SPEED_AVG_KMH], 12.04, 0.01, "cycling v8 speed avg")
            near(w.metrics[M.SPEED_MAX_KMH], 46.93, 0.01, "cycling v8 speed max")
            near(w.hrAvg, 133.0, 0.001, "cycling v8 hr avg")
            near(w.hrMax, 168.0, 0.001, "cycling v8 hr max")
            near(w.hrMin, 80.0, 0.001, "cycling v8 hr min")
            near(w.metrics[M.TRAINING_EFFECT_AEROBIC], 2.3, 0.01, "cycling v8 aerobic")
            near(w.metrics[M.TRAINING_EFFECT_ANAEROBIC], 0.8, 0.01, "cycling v8 anaerobic")
            near(w.metrics[M.RECOVERY_TIME], 13.0, 0.001, "cycling v8 recovery")
            near(w.metrics[M.HR_ZONE_ANAEROBIC_SECONDS], 95.0, 0.001, "cycling v8 zone anaerobic")
            near(w.metrics[M.HR_ZONE_AEROBIC_SECONDS], 814.0, 0.001, "cycling v8 zone aerobic")
            near(w.metrics[M.HR_ZONE_FAT_BURN_SECONDS], 340.0, 0.001, "cycling v8 zone fat burn")
            near(w.metrics[M.HR_ZONE_WARM_UP_SECONDS], 283.0, 0.001, "cycling v8 zone warm up")
            near(w.metrics[M.WORKOUT_LOAD], 28.0, 0.001, "cycling v8 load")
            near(w.metrics[M.VITALITY_GAIN], 7.0, 0.001, "cycling v8 vitality")
        }
        workout(b64(OUTDOOR_CYCLING_V6_23MAY), "cycling v6")?.let { w ->
            near(w.distanceMeters, 11982.0, 0.001, "cycling v6 distance")
            near(w.totalCalories, 538.0, 0.001, "cycling v6 total calories")
            near(w.activeCalories, 439.0, 0.001, "cycling v6 calories")
            near(w.metrics[M.PACE_MAX], 104.0, 0.001, "cycling v6 pace max")
            near(w.metrics[M.SPEED_MAX_KMH], 34.48, 0.01, "cycling v6 speed max")
            near(w.metrics[M.HR_ZONE_AEROBIC_SECONDS], 10.0, 0.001, "cycling v6 zone aerobic")
            near(w.metrics[M.HR_ZONE_FAT_BURN_SECONDS], 97.0, 0.001, "cycling v6 zone fat burn")
            near(w.metrics[M.HR_ZONE_WARM_UP_SECONDS], 1110.0, 0.001, "cycling v6 zone warm up")
            near(w.metrics[M.WORKOUT_LOAD], 5.0, 0.001, "cycling v6 load")
        }
        // Route ride: the byte at 0x8D is followed by 8 extra bytes.
        val routeOffset = 0x8D
        val route = ByteArray(cyclingV8.size + 8)
        System.arraycopy(cyclingV8, 0, route, 0, routeOffset + 1)
        route[routeOffset] = 0xFF.toByte()
        route.fill(0x5A, routeOffset + 1, routeOffset + 9)
        System.arraycopy(cyclingV8, routeOffset + 1, route, routeOffset + 9, cyclingV8.size - routeOffset - 1)
        workout(withCrc(route), "cycling v8 route")?.let { w ->
            near(w.metrics[M.WORKOUT_LOAD], 28.0, 0.001, "route ride load")
            near(w.metrics[M.VITALITY_GAIN], 7.0, 0.001, "route ride vitality")
        }
        val v99 = cyclingV8.copyOf()
        v99[5] = 99
        val r99 = parseHealthFile(withCrc(v99))
        check(r99 is HealthParseResult.Unsupported && r99.id.knownSubtype == HealthSubtype.SPORTS_OUTDOOR_CYCLING, "cycling v99 unsupported: $r99")

        workout(b64(OUTDOOR_RUN_V5_30SEP), "run v5")?.let { w ->
            eq(w.sport, WorkoutSport.OUTDOOR_RUNNING, "run v5 sport")
            near(w.activeSeconds, 1704.0, 0.001, "run v5 active")
            near(w.distanceMeters, 4189.0, 0.001, "run v5 distance")
            near(w.hrMin, 119.0, 0.001, "run v5 hr min")
            near(w.hrMax, 200.0, 0.001, "run v5 hr max")
            near(w.hrAvg, 162.0, 0.001, "run v5 hr avg")
            near(w.metrics[M.PACE_MAX], 395.0, 0.001, "run v5 pace max")
            near(w.metrics[M.PACE_MIN], 782.0, 0.001, "run v5 pace min")
            near(w.metrics[M.STEP_RATE_MAX], 178.0, 0.001, "run v5 step rate max")
            eq(w.tzOffsetSeconds, -3 * 3600, "run v5 tz")
        }
        workout(b64(WALKING_V6_29MAY), "walk v6")?.let { w ->
            near(w.activeSeconds, 1912.0, 0.001, "walk v6 active")
            near(w.hrMin, 131.0, 0.001, "walk v6 hr min")
            near(w.hrMax, 192.0, 0.001, "walk v6 hr max")
            near(w.hrAvg, 149.0, 0.001, "walk v6 hr avg")
        }
        workout(b64(WALKING_V9_04JAN), "walk v9")?.let { w ->
            eq(w.start, 1767527042L, "walk v9 start is the file id time, as in GB")
            eq(w.metrics[M.TIME_START], 1767527043.0, "walk v9 TIME_START field kept")
            near(w.hrMin, 99.0, 0.001, "walk v9 hr min")
            near(w.hrMax, 163.0, 0.001, "walk v9 hr max")
            near(w.hrAvg, 135.0, 0.001, "walk v9 hr avg")
        }
        workout(b64(INDOOR_CYCLING_V9_22FEB), "indoor cycling v9")?.let { w ->
            near(w.hrMin, 94.0, 0.001, "indoor cycling hr min")
            near(w.hrMax, 150.0, 0.001, "indoor cycling hr max")
            near(w.hrAvg, 135.0, 0.001, "indoor cycling hr avg")
        }
        workout(b64(ELLIPTICAL_V6_10FEB), "elliptical v6")?.let { w ->
            near(w.hrMin, 118.0, 0.001, "elliptical hr min")
            near(w.hrMax, 156.0, 0.001, "elliptical hr max")
            near(w.hrAvg, 147.0, 0.001, "elliptical hr avg")
        }
        workout(b64(OUTDOOR_CYCLING_V2_V3_04OCT), "cycling v2 v3")?.let { w ->
            eq(w.sport, WorkoutSport.OUTDOOR_CYCLING, "cycling v2 v3 sport")
            eq(w.end, 1791123696L, "cycling v2 v3 end")
            near(w.activeSeconds, 6502.0, 0.001, "cycling v2 v3 active")
            near(w.distanceMeters, 18090.0, 0.001, "cycling v2 v3 distance")
            near(w.activeCalories, 586.0, 0.001, "cycling v2 v3 calories")
            near(w.metrics[M.PACE_MAX], 199.0, 0.001, "cycling v2 v3 pace max")
            near(w.metrics[M.PACE_MIN], 716.0, 0.001, "cycling v2 v3 pace min")
            near(w.metrics[M.SPEED_MAX_KMH], 34.79, 0.01, "cycling v2 v3 speed max")
            near(w.hrAvg, 122.0, 0.001, "cycling v2 v3 hr avg")
            near(w.hrMax, 198.0, 0.001, "cycling v2 v3 hr max")
            near(w.hrMin, 59.0, 0.001, "cycling v2 v3 hr min")
            near(w.metrics[M.ELEVATION_GAIN_METERS], 461.7, 0.1, "cycling v2 v3 elevation gain")
            near(w.metrics[M.ELEVATION_LOSS_METERS], 452.4, 0.1, "cycling v2 v3 elevation loss")
            near(w.metrics[M.ALTITUDE_AVG_METERS], 477.8, 0.1, "cycling v2 v3 altitude avg")
            near(w.metrics[M.ALTITUDE_MAX_METERS], 609.6, 0.1, "cycling v2 v3 altitude max")
            near(w.metrics[M.ALTITUDE_MIN_METERS], 303.3, 0.1, "cycling v2 v3 altitude min")
            near(w.metrics[M.TRAINING_EFFECT_AEROBIC], 3.0, 0.01, "cycling v2 v3 aerobic")
            near(w.metrics[M.VO2_MAX], 50.0, 0.001, "cycling v2 v3 vo2max")
            near(w.metrics[M.RECOVERY_TIME], 30.0, 0.001, "cycling v2 v3 recovery")
            near(w.totalCalories, 729.0, 0.001, "cycling v2 v3 total calories")
            near(w.metrics[M.HR_ZONE_EXTREME_SECONDS], 85.0, 0.001, "cycling v2 v3 zone extreme")
            near(w.metrics[M.HR_ZONE_WARM_UP_SECONDS], 1959.0, 0.001, "cycling v2 v3 zone warm up")
        }
        workout(b64(OUTDOOR_WALKING_V1_V3_03OCT), "walking v1 v3")?.let { w ->
            eq(w.sport, WorkoutSport.WALKING, "walking v1 v3 sport")
            near(w.activeSeconds, 2040.0, 0.001, "walking v1 v3 active")
            near(w.distanceMeters, 1484.0, 0.001, "walking v1 v3 distance")
            near(w.activeCalories, 82.0, 0.001, "walking v1 v3 calories")
            near(w.metrics[M.PACE_MAX], 815.0, 0.001, "walking v1 v3 pace max")
            near(w.metrics[M.SPEED_MAX_KMH], 5.65, 0.01, "walking v1 v3 speed max")
            near(w.steps, 2224.0, 0.001, "walking v1 v3 steps")
            near(w.metrics[M.CADENCE_MAX], 150.0, 0.001, "walking v1 v3 cadence max")
            near(w.hrAvg, 86.0, 0.001, "walking v1 v3 hr avg")
            near(w.hrMax, 109.0, 0.001, "walking v1 v3 hr max")
            near(w.hrMin, 71.0, 0.001, "walking v1 v3 hr min")
            near(w.metrics[M.ELEVATION_GAIN_METERS], 2.0, 0.1, "walking v1 v3 elevation gain")
            near(w.metrics[M.ELEVATION_LOSS_METERS], 54.8, 0.1, "walking v1 v3 elevation loss")
            near(w.metrics[M.ALTITUDE_MAX_METERS], 355.4, 0.1, "walking v1 v3 altitude max")
            near(w.metrics[M.ALTITUDE_MIN_METERS], 298.4, 0.1, "walking v1 v3 altitude min")
            near(w.metrics[M.TRAINING_EFFECT_AEROBIC], 0.3, 0.01, "walking v1 v3 aerobic")
            near(w.totalCalories, 127.0, 0.001, "walking v1 v3 total calories")
        }

        // Synthetic HIIT v5: the likely indoor sport on this watch.
        val hiit = id(1_760_000_000L, 8, 5, HealthFileType.SPORTS, 0x10, HealthDetailType.SUMMARY)
        val hiitBody = Le().raw(ByteArray(4) { -1 }).i32(1_760_000_001L).i32(1_760_003_408L).i32(3408).u16(286).u8(126).u8(153).u8(78)
            .i32(java.lang.Float.floatToIntBits(2.5f).toLong()).u8(0).u8(0).u16(20).i32(10).i32(20).i32(30).i32(40).i32(50).bytes()
        workout(file(hiit, hiitBody), "hiit v5")?.let { w ->
            eq(w, WorkoutSummary(1_760_000_000L, 1_760_003_408L, 7200, WorkoutSport.HIIT, null, 3408, 286, null, 126, 153, 78, null, null,
                mapOf(M.TIME_START to 1_760_000_001.0, M.TRAINING_EFFECT_AEROBIC to 2.5, M.RECOVERY_TIME to 20.0, M.HR_ZONE_EXTREME_SECONDS to 10.0, M.HR_ZONE_ANAEROBIC_SECONDS to 20.0,
                    M.HR_ZONE_AEROBIC_SECONDS to 30.0, M.HR_ZONE_FAT_BURN_SECONDS to 40.0, M.HR_ZONE_WARM_UP_SECONDS to 50.0)), "hiit v5 summary")
        }
        val hiitShort = file(hiit, hiitBody.copyOfRange(0, hiitBody.size - 1))
        val hs = parseHealthFile(hiitShort)
        check(hs == HealthParseResult.Invalid("Truncated: need 4 bytes at 53, have 3", hiit), "hiit cut by one byte is Invalid: $hs")

        val details = id(1_760_000_000L, 0, 3, HealthFileType.SPORTS, 8, HealthDetailType.DETAILS)
        check(parseHealthFile(file(details, ByteArray(20))) is HealthParseResult.Unsupported, "workout details unsupported")
        val gps = id(1_760_000_000L, 0, 1, HealthFileType.SPORTS, 8, HealthDetailType.GPS_TRACK)
        check(parseHealthFile(file(gps, ByteArray(1))) is HealthParseResult.Unsupported, "workout gps unsupported")
        val unknownSport = id(1_760_000_000L, 0, 1, HealthFileType.SPORTS, 0x1F, HealthDetailType.SUMMARY)
        check(parseHealthFile(file(unknownSport, ByteArray(40))) is HealthParseResult.Unsupported, "unknown sport subtype unsupported")
    }

    // ---- DAILY / DETAILS (synthetic: GB has no fixture) ----

    private fun dailyDetails() {
        val v3 = id(1_700_000_000L, 4, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS)
        // Nibbles: G0 present+second+third, G1 present+second, G2 present, G3/G4 present+first,
        // G5/G6 absent, G7/G8 present+first, G9 unused.
        val header = hex("BA8CC00CC0")
        val rec1 = Le().u16((1 shl 14) or 100).u8(0x45).u8(7).u16(70).u8(72).u8(97).u8(255).u8(0xEE).bytes()
        val rec2 = Le().u16(0).u8(0).u8(0).u16(0).u8(0).u8(0).u8(30).bytes()
        parsed<HealthData.DailyDetails>(file(v3, header + rec1 + rec2), "daily details v3")?.let { d ->
            eq(d.tzOffsetSeconds, 3600, "details tz")
            eq(d.samples.size, 2, "details sample count")
            eq(d.samples.getOrNull(0), MinuteSample(1_700_000_000L, 100, 5, 70, 72, null, 97, null, MinuteUnknown(0, 1, 7, null, null, null)),
                "details minute 1 (extra byte consumed, stress 255 null)")
            eq(d.samples.getOrNull(1), MinuteSample(1_700_000_060L, 0, 0, 0, null, null, null, 30, MinuteUnknown(0, 0, 0, null, null, null)),
                "details minute 2 (hr 0 and spo2 0 null)")
        }
        val v4 = id(1_700_000_000L, 0, 4, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS)
        // v4: only G4 (HR) and G9 (light) present.
        val rec4 = Le().u8(60).u16(0x1234).bytes()
        parsed<HealthData.DailyDetails>(file(v4, hex("0000C0000800") + rec4), "daily details v4")?.let { d ->
            eq(d.samples.map { it.heartRate to it.unknown.v4Group9 }, listOf(60 to 0x1234), "details v4 light group")
        }
        invalid(file(v3, header + rec1 + rec2.copyOfRange(0, 4)), "Truncated: need 2 bytes at 27, have 0", "details partial record invalid")
        invalid(file(v3, ByteArray(5) + byteArrayOf(1, 2)), "Truncated: daily details record consumed no bytes at 13", "details empty header cannot loop")
        val v9 = id(1_700_000_000L, 0, 9, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS)
        check(parseHealthFile(file(v9, ByteArray(8))) is HealthParseResult.Unsupported, "details v9 unsupported")
    }

    // ---- SLEEP (synthetic: GB has no fixture) ----

    private fun stagePacket(ts: Long, type: Int, data: ByteArray) =
        Le().raw(hex("FBFAFCFF")).u8(17).i64(ts).u8(0).u8(type).be16(data.size).raw(data).bytes()

    private fun sleep() {
        val bed = 1_700_000_000L
        val wake = bed + 75 * 60
        val v2 = id(bed - 600, 4, 2, HealthFileType.ACTIVITY, 8, HealthDetailType.DETAILS)
        val stages17 = Le().be16((1 shl 12) or 30).be16((2 shl 12) or 45).bytes()
        val summary16 = Le().u8(0x12).be16(75).be16(0).be16(30).be16(0).be16(45).u8(0x04).u8(0).bytes()
        val body = Le().u8(0x10) // header: HR block valid
            .u8(1).i32(bed).i32(wake) // isAwake 1: finished
            .u16(60).u16(3).i32(bed).raw(byteArrayOf(60, 61, 62))
            .raw(stagePacket(bed, 17, stages17))
            .u8(0x99) // junk to resync over
            .raw(Le().raw(hex("FBFAFCFF")).u8(17).i64(bed).u8(0).u8(2).be16(0x0101).bytes()) // no-data type
            .raw(stagePacket(bed, 16, summary16))
            .bytes()
        val expected = SleepSession(
            bedTime = bed, wakeupTime = wake, tzOffsetSeconds = 3600, finished = true, intoBedTime = null, sleepQuality = null,
            totalMinutes = 75, deepMinutes = 45, lightMinutes = 30, remMinutes = 0, awakeMinutes = 0,
            stages = listOf(SleepStage(bed, SleepStageKind.LIGHT, 1, 30), SleepStage(bed + 1800, SleepStageKind.DEEP, 2, 45)),
            stagesDropped = false,
        )
        parsed<HealthData.Sleep>(file(v2, body), "sleep v2")?.let { eq(it.sessions, listOf(expected), "sleep v2 session") }

        // Same night, but the final packet claims more data than the file has.
        val cut = body.copyOfRange(0, body.size - 3)
        parsed<HealthData.Sleep>(file(v2, cut), "sleep v2 cut")?.let {
            eq(it.sessions, listOf(expected.copy(totalMinutes = null, deepMinutes = null, lightMinutes = null, remMinutes = null,
                awakeMinutes = null, stages = emptyList(), stagesDropped = true)), "sleep v2 truncated stages dropped")
        }

        val v5 = id(bed, 0, 5, HealthFileType.ACTIVITY, 8, HealthDetailType.SUMMARY)
        val v5Body = Le().u8(0x10).u8(0).u8(0).i32(bed).i32(wake).u8(80).raw(ByteArray(9)).i32(bed - 1800).i32(wake).bytes()
        parsed<HealthData.Sleep>(file(v5, v5Body), "sleep v5")?.let {
            eq(it.sessions, listOf(SleepSession(bed, wake, 0, false, bed - 1800, 80, null, null, null, null, null, emptyList(), false)), "sleep v5 session")
        }
        val v6 = id(bed, 0, 6, HealthFileType.ACTIVITY, 8, HealthDetailType.SUMMARY)
        check(parseHealthFile(file(v6, v5Body)) is HealthParseResult.Unsupported, "sleep v6 unsupported")
        // HR block count runs past the end of the file.
        val badCount = Le().u8(0x10).u8(0).i32(bed).i32(wake).u16(60).u16(500).i32(bed).raw(ByteArray(4)).bytes()
        invalid(file(v2, badCount), "Truncated: need 500 bytes at 26, have 4", "sleep HR block overrun invalid")

        // SLEEP_STAGES (0x03) v2.
        val st = id(bed, 4, 2, HealthFileType.ACTIVITY, 3, HealthDetailType.DETAILS)
        val stBody = Le().raw(hex("FFFF0102030405")).u16(420).i32(bed).i32(bed + 420 * 60).raw(ByteArray(3))
            .u16(60).u16(300).u16(40).u16(20).u8(7).i32(bed).u8(3).i32(bed + 600).u8(2).bytes()
        parsed<HealthData.Sleep>(file(st, stBody), "sleep stages v2")?.let {
            eq(it.sessions, listOf(SleepSession(bed, bed + 420 * 60, 3600, true, null, null, 420, 60, 300, 40, 20,
                listOf(SleepStage(bed, SleepStageKind.LIGHT, 3, null), SleepStage(bed + 600, SleepStageKind.DEEP, 2, null)), false)), "sleep stages session")
        }
        val empty = Le().raw(ByteArray(7)).u16(0).i32(0).i32(0).raw(ByteArray(3)).raw(ByteArray(8)).bytes()
        parsed<HealthData.Sleep>(file(st, empty), "sleep stages empty")?.let { eq(it.sessions, emptyList<SleepSession>(), "sleep stages without data") }
        invalid(file(st, stBody + byteArrayOf(1, 2)), "Truncated: need 4 bytes at 47, have 2", "sleep stages partial record invalid")
        val st1 = id(bed, 4, 1, HealthFileType.ACTIVITY, 3, HealthDetailType.DETAILS)
        check(parseHealthFile(file(st1, stBody)) is HealthParseResult.Unsupported, "sleep stages v1 unsupported")
    }

    // ---- layout errors ----

    private fun layoutErrors() {
        val good = b64(TREADMILL_V11_24APR)
        val badCrc = good.copyOf()
        badCrc[40] = (badCrc[40].toInt() xor 1).toByte()
        val r = parseHealthFile(badCrc)
        check(r is HealthParseResult.Invalid && r.reason == "CRC mismatch" && r.id != null, "CRC mismatch: $r")
        val crcByte = good.copyOf()
        crcByte[crcByte.size - 1] = (crcByte[crcByte.size - 1].toInt() xor 0x80).toByte()
        invalid(crcByte, "CRC mismatch", "CRC high bit mismatch")
        invalid(good.copyOfRange(0, good.size - 1), "CRC mismatch", "truncated transfer fails CRC")
        check(parseHealthFile(ByteArray(0)) == HealthParseResult.Invalid("File too short: 0 bytes"), "empty input")
        invalid(good.copyOfRange(0, 12), "File too short: 12 bytes", "12 bytes too short")
        val unknown = id(1_700_000_000L, 0, 1, HealthFileType.ACTIVITY, 0x05, HealthDetailType.DETAILS)
        val u = parseHealthFile(file(unknown, ByteArray(10)))
        check(u is HealthParseResult.Unsupported && u.id == unknown, "unknown activity subtype unsupported: $u")
        val dailyGps = id(1_700_000_000L, 0, 1, HealthFileType.ACTIVITY, 0, HealthDetailType.GPS_TRACK)
        check(parseHealthFile(file(dailyGps, ByteArray(10))) is HealthParseResult.Unsupported, "daily gps unsupported")
    }

    // ---- record caps: a CRC-valid file of tiny records must not exhaust memory ----

    private fun recordCaps() {
        val details = id(1_700_000_000L, 0, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS)
        // Only the 8-bit heart-rate group is present, so each record is one byte.
        val hrOnly = hex("0000C00000")
        parsed<HealthData.DailyDetails>(file(details, hrOnly + ByteArray(MAX_MINUTE_SAMPLES) { 60 }), "details at the cap")?.let {
            eq(it.samples.size, 5760, "four days of minutes parse")
        }
        invalid(file(details, hrOnly + ByteArray(MAX_MINUTE_SAMPLES + 1) { 60 }), "More than 5760 daily details records", "details over the cap")

        val bed = 1_700_000_000L
        val stages = id(bed, 0, 2, HealthFileType.ACTIVITY, 3, HealthDetailType.DETAILS)
        val stagesHead = Le().raw(hex("FFFF0102030405")).u16(420).i32(bed).i32(bed + 420 * 60).raw(ByteArray(3))
            .u16(60).u16(300).u16(40).u16(20).u8(7)
        for (i in 0..MAX_SLEEP_STAGES) stagesHead.i32(bed + i).u8(3)
        invalid(file(stages, stagesHead.bytes()), "More than 2880 sleep stages", "sleep stages over the cap")

        val sleep = id(bed, 0, 2, HealthFileType.ACTIVITY, 8, HealthDetailType.DETAILS)
        val packed = Le()
        repeat(MAX_SLEEP_STAGES + 1) { packed.be16((1 shl 12) or 1) }
        val sleepBody = Le().u8(0).u8(0).i32(bed).i32(bed + 60).raw(stagePacket(bed, 17, packed.bytes())).bytes()
        invalid(file(sleep, sleepBody), "More than 2880 sleep stages", "sleep stage packet over the cap")

        val manual = id(bed, 0, 2, HealthFileType.ACTIVITY, 6, HealthDetailType.DETAILS)
        val samples = Le()
        repeat(MAX_MANUAL_SAMPLES + 1) { samples.i32(bed + it).u8(0x11).u8(60) }
        invalid(file(manual, samples.bytes()), "More than 10000 manual samples", "manual samples over the cap")
    }

    // ---- never throws ----

    private fun robustness() {
        val rnd = Random(42)
        var threw = 0
        var crashed = 0
        fun attempt(b: ByteArray) {
            try {
                val r = parseHealthFile(b)
                // A caught bounds or state exception is a parser bug, not a file problem.
                if (r is HealthParseResult.Invalid && r.reason.startsWith("Parser error")) {
                    crashed++
                    if (crashed <= 3) System.err.println("parser error on ${b.size} bytes: ${r.reason}")
                }
            } catch (e: Throwable) {
                threw++
                if (threw <= 3) System.err.println("threw on ${b.size} bytes: $e")
            }
        }
        for (f in workoutFixtures) {
            val b = b64(f)
            for (n in 13..b.size) attempt(withCrc(b.copyOfRange(0, n)))
            repeat(200) {
                val m = b.copyOf()
                m[8 + rnd.nextInt(m.size - 12)] = rnd.nextInt(256).toByte()
                attempt(withCrc(m))
            }
        }
        for (flags in 0 until 256) {
            repeat(30) {
                val body = ByteArray(rnd.nextInt(200)) { rnd.nextInt(256).toByte() }
                val idb = byteArrayOf(0, 0x10, 0x60, 0x65, 0, rnd.nextInt(12).toByte(), flags.toByte())
                attempt(withCrc(idb + byteArrayOf(0) + body + ByteArray(4)))
            }
        }
        eq(threw, 0, "parser never throws on mutated or random input")
        eq(crashed, 0, "parser never hits an internal error on mutated or random input")
    }

    // ---- Redmi Watch 5 Active, firmware 2.2.105 (synthetic values in the layouts that watch writes) ----

    private fun redmiWatch5Active() {
        rw5aDailySummary()
        rw5aDailyDetails()
        rw5aSleep()
        rw5aWorkouts()
    }

    /** All 32 v5 bits but 14 (recovery), 21..23 (training load) and 30..31 (reserved), as the watch sends them. */
    private val rw5aBitmap = hex("FFFDF8FC")

    /** Slots 0..29 only: the watch leaves out the two trailing reserved slots. */
    private fun rw5aSummaryBody(): ByteArray = Le()
        .i32(8421).u16(612).u8(71).u8(55).u8(151).i32(1_760_030_000L).u8(47).i32(1_760_010_000L).u8(68)
        .u8(22).u8(64).u8(8).raw(byteArrayOf(0, 0xF8.toByte(), 0x3F)).u16(612).u16(0).u8(98)
        .u8(99).i32(1_760_001_000L).u8(95).i32(1_760_002_000L).u8(0)
        .u16(0).u16(0).u8(0).u8(1).u8(4).u8(2).u16(37).u8(2).u8(19)
        .bytes()

    private fun rw5aDailySummary() {
        val id = id(1_760_000_000L, -20, 5, HealthFileType.ACTIVITY, 0, HealthDetailType.SUMMARY)
        val body = rw5aSummaryBody()
        eq(body.size, 53, "rw5a summary body size")
        eq(file(id, rw5aBitmap + body).size, 69, "rw5a summary file size")
        daily(id, rw5aBitmap, body, "rw5a daily summary")?.let { s ->
            eq(s, DailySummary(
                time = 1_760_000_000L, tzOffsetSeconds = -18000, steps = 8421, activeCalories = 612, restingHeartRate = 55,
                maxHeartRate = 151, maxHeartRateTime = 1_760_030_000L, minHeartRate = 47, minHeartRateTime = 1_760_010_000L, avgHeartRate = 68,
                avgStress = 22, maxStress = 64, minStress = 8, standingHours = 0x3FF800, calories = 612, recoveryHours = null,
                maxSpo2 = 99, maxSpo2Time = 1_760_001_000L, minSpo2 = 95, minSpo2Time = 1_760_002_000L, avgSpo2 = null,
                trainingLoadDay = null, trainingLoadWeek = null, trainingLoadLevel = null,
                vitalityIncreaseLight = 1, vitalityIncreaseModerate = 4, vitalityIncreaseHigh = 2, vitalityCurrent = 37,
            ), "rw5a daily summary (trailing reserved slots absent, avg SpO2 0 is null)")
        }
        // A slot may only be missing when its bit is clear: bit 31 set means the body is short.
        invalid(file(id, hex("FFFDF8FD") + body), "Truncated: need 2 bytes at 65, have 0", "rw5a summary missing a valid slot")
        // Slot 29 is valid, so ending before it is truncation, not an early end.
        invalid(file(id, rw5aBitmap + body.copyOfRange(0, 52)), "Truncated: need 1 bytes at 64, have 0", "rw5a summary cut before slot 29")
        // Ending inside a slot is truncation whatever the bits say.
        invalid(file(id, hex("FFFDF8F0") + body.copyOfRange(0, 50)), "Truncated: need 2 bytes at 61, have 1", "rw5a summary cut inside slot 27")
        // The full 57-byte body still parses, reserved slots included.
        daily(id, rw5aBitmap, body + Le().u16(0x1111).u16(0x2222).bytes(), "rw5a summary with reserved slots")?.let {
            eq(it.vitalityCurrent, 37, "rw5a summary with reserved slots: vitality current")
        }
    }

    private fun rw5aDailyDetails() {
        val id = id(1_760_000_000L, -20, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS)
        // The watch's header: G0 present+second+third, G1 present+first+second, G2 present+first+second,
        // G3/G4 present+first, G5/G6 absent, G7/G8 present+first, G9 unused.
        val header = hex("BEECC00CC0")
        val m1 = Le().u16(42).u8((1 shl 6) or 3).u8(0).u16(30).u8(88).u8(0).u8(31).bytes()
        val m2 = Le().u16(0).u8(0).u8(0).u16(0).u8(0).u8(97).u8(255).bytes()
        val m3 = Le().u16((1 shl 14) or 5).u8(1).u8(2).u16(4).u8(101).u8(0).u8(40).u8(0x7F).bytes() // flag bit: one extra byte
        parsed<HealthData.DailyDetails>(file(id, header + m1 + m2 + m3), "rw5a daily details")?.let { d ->
            eq(d.tzOffsetSeconds, -18000, "rw5a details tz")
            eq(d.samples, listOf(
                MinuteSample(1_760_000_000L, 42, 3, 30, 88, null, null, 31, MinuteUnknown(0, 1, 0, null, null, null)),
                MinuteSample(1_760_000_060L, 0, 0, 0, null, null, 97, null, MinuteUnknown(0, 0, 0, null, null, null)),
                MinuteSample(1_760_000_120L, 5, 1, 4, 101, null, null, 40, MinuteUnknown(0, 0, 2, null, null, null)),
            ), "rw5a details minutes")
        }
    }

    private fun flagPacket(ts: Long, type: Int, flags: Int) = Le().raw(hex("FBFAFCFF")).u8(17).i64(ts).u8(0).u8(type).be16(flags).bytes()

    private fun sleepSummary16(total: Int, wake: Int, light: Int, rem: Int, deep: Int, flags: Int) =
        Le().u8(0x10).be16(total).be16(wake).be16(light).be16(rem).be16(deep).u8(flags).u8(0).bytes()

    private fun stages17(vararg s: Pair<Int, Int>) = Le().apply { for ((kind, minutes) in s) be16((kind shl 12) or minutes) }.bytes()

    private fun rw5aSleep() {
        val bed = 1_760_070_000L
        val wake = bed + 160 * 60
        val id = id(wake, -20, 2, HealthFileType.ACTIVITY, 8, HealthDetailType.DETAILS)
        // The watch rewrites the night as it goes: a short snapshot, then the full one, both from bed time.
        val body = Le().u8(0xFF) // header: HR and SpO2 blocks valid
            .u8(1).i32(bed).i32(wake)
            .u16(60).u16(3).i32(bed).raw(byteArrayOf(58, 57, 60))
            .u16(300).u16(2).i32(bed).raw(byteArrayOf(97, 98))
            .raw(stagePacket(0, 101, ByteArray(4)))
            .raw(stagePacket(bed, 0, ByteArray(6)))
            .raw(flagPacket(bed, 12, 1))
            .raw(stagePacket(bed, 1, byteArrayOf(80, 82, 79)))
            .raw(flagPacket(bed, 2, 0))
            .raw(flagPacket(bed, 3, 6))
            .raw(stagePacket(bed, 16, sleepSummary16(90, 0, 60, 0, 30, 0x15)))
            .raw(stagePacket(bed, 17, stages17(1 to 60, 2 to 30)))
            .raw(flagPacket(bed, 14, 3))
            .raw(stagePacket(bed, 10, ByteArray(4)))
            .raw(flagPacket(bed, 2, 0))
            .raw(flagPacket(bed, 3, 6))
            .raw(stagePacket(bed, 16, sleepSummary16(150, 10, 80, 20, 50, 0x16)))
            .raw(stagePacket(bed, 17, stages17(1 to 40, 2 to 50, 0 to 10, 3 to 20, 1 to 40)))
            .raw(flagPacket(bed, 9, 1))
            .bytes()
        parsed<HealthData.Sleep>(file(id, body), "rw5a sleep")?.let {
            eq(it.sessions, listOf(SleepSession(
                bedTime = bed, wakeupTime = wake, tzOffsetSeconds = -18000, finished = true, intoBedTime = null, sleepQuality = null,
                totalMinutes = 150, deepMinutes = 50, lightMinutes = 80, remMinutes = 20, awakeMinutes = 10,
                stages = listOf(
                    SleepStage(bed, SleepStageKind.LIGHT, 1, 40),
                    SleepStage(bed + 40 * 60, SleepStageKind.DEEP, 2, 50),
                    SleepStage(bed + 90 * 60, SleepStageKind.AWAKE, 0, 10),
                    SleepStage(bed + 100 * 60, SleepStageKind.REM, 3, 20),
                    SleepStage(bed + 120 * 60, SleepStageKind.LIGHT, 1, 40),
                ),
                stagesDropped = false,
            )), "rw5a sleep: last snapshot wins, isAwake 1 is finished")
        }
    }

    private fun rw5aWorkouts() {
        val freestyle = id(1_760_200_000L, -20, 8, HealthFileType.SPORTS, 0x08, HealthDetailType.SUMMARY)
        val freestyleBody = Le().raw(ByteArray(6) { -1 })
            .i32(1_760_200_000L).i32(1_760_200_600L).i32(590).u16(48).u8(97).u8(131).u8(74).raw(ByteArray(6))
            .f32(1.25f).u8(0).u8(0).u16(0).i32(0).i32(0).i32(20).i32(240).i32(180)
            .u16(60).i32(590).f32(0f).u8(0).u16(308).u16(0).i32(0).u16(0).u16(0).u8(0)
            .u8(2).u16(0).u16(0).u16(0).u16(0).raw(ByteArray(7))
            .bytes()
        eq(freestyleBody.size, 97, "rw5a freestyle v8 body size")
        workout(file(freestyle, freestyleBody), "rw5a freestyle v8")?.let { w ->
            eq(w, WorkoutSummary(1_760_200_000L, 1_760_200_600L, -18000, WorkoutSport.STRENGTH_TRAINING, 308, 590, 48, 60, 97, 131, 74, null, null,
                mapOf(M.TIME_START to 1_760_200_000.0, M.TRAINING_EFFECT_AEROBIC to 1.25, M.RECOVERY_TIME to 0.0,
                    M.HR_ZONE_EXTREME_SECONDS to 0.0, M.HR_ZONE_ANAEROBIC_SECONDS to 0.0, M.HR_ZONE_AEROBIC_SECONDS to 20.0,
                    M.HR_ZONE_FAT_BURN_SECONDS to 240.0, M.HR_ZONE_WARM_UP_SECONDS to 180.0, M.TRAINING_EFFECT_ANAEROBIC to 0.0,
                    M.TIME_GOAL_SECONDS to 0.0, M.CALORIES_GOAL to 0.0, M.WORKOUT_LOAD to 0.0, M.VITALITY_GAIN to 2.0,
                    M.PUNCH_TOTAL to 0.0, M.PUNCH_PERFECT to 0.0, M.PUNCH_GOOD to 0.0, M.PUNCH_MISS to 0.0)), "rw5a freestyle v8 summary")
        }

        val rope = id(1_760_300_000L, -20, 5, HealthFileType.SPORTS, 0x0E, HealthDetailType.SUMMARY)
        val ropeBody = Le().raw(ByteArray(5) { -1 })
            .i32(1_760_300_000L).i32(1_760_300_900L).i32(880).u16(95).u8(118).u8(160).u8(80)
            .f32(2.0f).u16(0).u16(4).i32(0).i32(60).i32(120).i32(300).i32(200)
            .u16(256).i32(400).u16(26).u16(0).u16(140).raw(ByteArray(27)).i32(0)
            .f32(0.75f).raw(ByteArray(3)).i32(600).u16(0).i32(0).u16(0).u8(0).u8(3)
            .bytes()
        eq(ropeBody.size, 114, "rw5a jump rope v5 body size")
        workout(file(rope, ropeBody), "rw5a jump rope v5")?.let { w ->
            eq(w, WorkoutSummary(1_760_300_000L, 1_760_300_900L, -18000, WorkoutSport.JUMP_ROPING, null, 880, 95, null, 118, 160, 80, null, null,
                mapOf(M.TIME_START to 1_760_300_000.0, M.TRAINING_EFFECT_AEROBIC to 2.0, M.RECOVERY_TIME to 4.0,
                    M.HR_ZONE_EXTREME_SECONDS to 0.0, M.HR_ZONE_ANAEROBIC_SECONDS to 60.0, M.HR_ZONE_AEROBIC_SECONDS to 120.0,
                    M.HR_ZONE_FAT_BURN_SECONDS to 300.0, M.HR_ZONE_WARM_UP_SECONDS to 200.0, M.JUMPS to 400.0, M.JUMP_RATE_AVG to 26.0,
                    M.JUMP_RATE_MAX to 140.0, M.TRAINING_EFFECT_ANAEROBIC to 0.75, M.TIME_GOAL_SECONDS to 600.0, M.CALORIES_GOAL to 0.0,
                    M.JUMPS_GOAL to 0.0, M.WORKOUT_LOAD to 0.0, M.VITALITY_GAIN to 3.0)), "rw5a jump rope v5 summary")
        }

        val walk = id(1_760_100_000L, -20, 5, HealthFileType.SPORTS, 0x16, HealthDetailType.SUMMARY)
        val walkBody = Le().raw(ByteArray(9) { -1 })
            .u16(2).i32(1_760_100_001L).i32(1_760_102_401L).i32(2400).i32(2390).i32(2800).u16(190).u16(140)
            .i32(857).i32(600).i32(1200).f32(4.25f).f32(6.5f).i32(3900).u16(72).u16(98).u16(121).u8(104).u8(138).u8(71)
            .raw(ByteArray(20)).f32(1.5f).u8(0).f32(0.5f).u8(0).u8(0).u16(0).u16(3).u8(0)
            .i32(0).i32(0).i32(60).i32(900).i32(1200)
            .raw(ByteArray(59) { it.toByte() }) // trailing bytes GB does not decode for v5
            .bytes()
        eq(walkBody.size, 183, "rw5a outdoor walking v2 v5 body size")
        workout(file(walk, walkBody), "rw5a walking v5")?.let { w ->
            eq(w, WorkoutSummary(1_760_100_000L, 1_760_102_401L, -18000, WorkoutSport.WALKING, 2, 2400, 140, 190, 104, 138, 71, 3900, 2800,
                mapOf(M.TIME_START to 1_760_100_001.0, M.PACE_AVG_SECONDS_PER_KM to 857.0, M.PACE_MAX to 600.0, M.PACE_MIN to 1200.0,
                    M.SPEED_AVG_KMH to 4.25, M.SPEED_MAX_KMH to 6.5, M.STEP_LENGTH_AVG_CM to 72.0, M.STEP_RATE_AVG to 98.0,
                    M.STEP_RATE_MAX to 121.0, M.TRAINING_EFFECT_AEROBIC to 1.5, M.TRAINING_EFFECT_ANAEROBIC to 0.5, M.VO2_MAX to 0.0,
                    M.RECOVERY_TIME to 3.0, M.HR_ZONE_EXTREME_SECONDS to 0.0, M.HR_ZONE_ANAEROBIC_SECONDS to 0.0,
                    M.HR_ZONE_AEROBIC_SECONDS to 60.0, M.HR_ZONE_FAT_BURN_SECONDS to 900.0, M.HR_ZONE_WARM_UP_SECONDS to 1200.0)),
                "rw5a walking v5 summary")
        }
    }
}
