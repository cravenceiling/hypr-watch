/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo
    Copyright (C) 2026 Gadgetbridge contributors

    Ported from Gadgetbridge's XiaomiActivityFileId.java.

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

enum class HealthFileType(val code: Int) {
    ACTIVITY(0),
    SPORTS(1),
}

/** Two bits in the id byte; code 3 has no known meaning. */
enum class HealthDetailType(val code: Int, val fetchOrder: Int) {
    DETAILS(0, 1),
    SUMMARY(1, 0),
    GPS_TRACK(2, 2),
    UNKNOWN_3(3, 3),
}

enum class HealthSubtype(val type: HealthFileType, val code: Int) {
    ACTIVITY_DAILY(HealthFileType.ACTIVITY, 0x00),
    ACTIVITY_SLEEP_STAGES(HealthFileType.ACTIVITY, 0x03),
    ACTIVITY_MANUAL_SAMPLES(HealthFileType.ACTIVITY, 0x06),
    ACTIVITY_SLEEP(HealthFileType.ACTIVITY, 0x08),
    SPORTS_OUTDOOR_RUNNING(HealthFileType.SPORTS, 0x01),
    SPORTS_OUTDOOR_WALKING_V1(HealthFileType.SPORTS, 0x02),
    SPORTS_TREADMILL(HealthFileType.SPORTS, 0x03),
    SPORTS_OUTDOOR_CYCLING_V2(HealthFileType.SPORTS, 0x06),
    SPORTS_INDOOR_CYCLING(HealthFileType.SPORTS, 0x07),
    SPORTS_FREESTYLE(HealthFileType.SPORTS, 0x08),
    SPORTS_POOL_SWIMMING(HealthFileType.SPORTS, 0x09),
    SPORTS_ELLIPTICAL(HealthFileType.SPORTS, 0x0B),
    SPORTS_ROWING(HealthFileType.SPORTS, 0x0D),
    SPORTS_JUMP_ROPING(HealthFileType.SPORTS, 0x0E),
    SPORTS_HIIT(HealthFileType.SPORTS, 0x10),
    SPORTS_OUTDOOR_WALKING_V2(HealthFileType.SPORTS, 0x16),
    SPORTS_OUTDOOR_CYCLING(HealthFileType.SPORTS, 0x17),
    ;

    companion object {
        fun of(type: HealthFileType, code: Int): HealthSubtype? = entries.firstOrNull { it.type == type && it.code == code }
    }
}

/**
 * The 7-byte activity file id: i32 LE unix seconds, i8 timezone in 15-minute blocks, i8 version,
 * then one byte packing type (bit 7), subtype (bits 6..2) and detail type (bits 1..0).
 * Every field range matches its bit width, so [toBytes] always reproduces the received bytes.
 */
class HealthFileId(
    /** Unix seconds, UTC. */
    val timestamp: Long,
    /** Signed, in 15-minute blocks. */
    val timezone: Int,
    /** Unsigned file format version. */
    val version: Int,
    val type: HealthFileType,
    /** Raw 5-bit subtype; see [knownSubtype]. */
    val subtype: Int,
    val detailType: HealthDetailType,
) : Comparable<HealthFileId> {
    init {
        require(timestamp in Int.MIN_VALUE..Int.MAX_VALUE) { "timestamp out of range" }
        require(timezone in -128..127) { "timezone out of range" }
        require(version in 0..255) { "version out of range" }
        require(subtype in 0..31) { "subtype out of range" }
    }

    val tzOffsetSeconds: Int get() = timezone * 15 * 60

    val knownSubtype: HealthSubtype? get() = HealthSubtype.of(type, subtype)

    fun toBytes(): ByteArray {
        val t = timestamp.toInt()
        return byteArrayOf(
            t.toByte(), (t shr 8).toByte(), (t shr 16).toByte(), (t shr 24).toByte(),
            timezone.toByte(),
            version.toByte(),
            ((type.code shl 7) or (subtype shl 2) or detailType.code).toByte(),
        )
    }

    fun toHex(): String = toBytes().joinToString("") { HEX[(it.toInt() shr 4) and 15].toString() + HEX[it.toInt() and 15] }

    /**
     * GB order packed into one non-negative number: timestamp, timezone, type, subtype, detail fetch
     * order (summary first), version. Distinct ids have distinct ranks.
     */
    val rank: Long
        get() = ((timestamp - Int.MIN_VALUE) shl 24) or ((timezone + 128).toLong() shl 16) or (type.code.toLong() shl 15) or
            (subtype.toLong() shl 10) or (detailType.fetchOrder.toLong() shl 8) or version.toLong()

    override fun compareTo(other: HealthFileId): Int = rank.compareTo(other.rank)

    override fun equals(other: Any?): Boolean = other is HealthFileId && toBytes().contentEquals(other.toBytes())

    override fun hashCode(): Int = toBytes().contentHashCode()

    override fun toString(): String {
        val name = knownSubtype?.name ?: "${type.name}_0x%02X".format(subtype)
        return "HealthFileId(${toHex()} ts=$timestamp tz=$timezone v=$version $name/${detailType.name})"
    }

    companion object {
        const val SIZE = 7
        private const val HEX = "0123456789abcdef"

        /** Null when fewer than 7 bytes are available at [offset]. */
        fun parse(bytes: ByteArray, offset: Int = 0): HealthFileId? {
            if (offset < 0 || bytes.size - offset < SIZE) return null
            fun u(i: Int) = bytes[offset + i].toInt() and 255
            val ts = u(0) or (u(1) shl 8) or (u(2) shl 16) or (u(3) shl 24)
            val flags = u(6)
            return HealthFileId(
                timestamp = ts.toLong(),
                timezone = bytes[offset + 4].toInt(),
                version = u(5),
                type = if (flags and 0x80 != 0) HealthFileType.SPORTS else HealthFileType.ACTIVITY,
                subtype = (flags and 0x7F) shr 2,
                detailType = HealthDetailType.entries[flags and 3],
            )
        }

        /** Null unless [hex] is exactly 14 ASCII hex digits. */
        fun fromHex(hex: String): HealthFileId? {
            if (hex.length != SIZE * 2) return null
            val bytes = ByteArray(SIZE)
            for (i in 0 until SIZE) {
                val hi = digit(hex[i * 2])
                val lo = digit(hex[i * 2 + 1])
                if (hi < 0 || lo < 0) return null
                bytes[i] = ((hi shl 4) or lo).toByte()
            }
            return parse(bytes)
        }

        // Character.digit would also accept non-ASCII digits such as fullwidth or Arabic-Indic ones.
        private fun digit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
