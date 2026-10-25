package local.hyprwatch

import java.io.ByteArrayOutputStream

/** Small bounded protobuf codec for the documented Xiaomi commands. */
object Proto {
    fun join(vararg parts: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        for (p in parts) {
            b.write(p, 0, p.size)
        }
        return b.toByteArray()
    }

    fun vint(value: Long): ByteArray {
        if (value < 0) throw IllegalArgumentException("Negative varint")
        val b = ByteArrayOutputStream()
        var n = value
        while (n > 127) {
            b.write((n.toInt() and 127) or 128)
            n = n ushr 7
        }
        b.write(n.toInt())
        return b.toByteArray()
    }

    fun num(f: Int, n: Long): ByteArray = join(vint(f.toLong() shl 3), vint(n))

    /** uint32 field from a signed Int, as protobuf-java writes it: negative values become 5-byte varints. */
    fun u32(f: Int, n: Int): ByteArray = num(f, n.toLong() and 0xFFFFFFFFL)

    /** sint32 field: zigzag-encoded so negative values (timezone offsets, temperatures) stay short. */
    fun s32(f: Int, n: Int): ByteArray = num(f, ((n shl 1) xor (n shr 31)).toLong() and 0xFFFFFFFFL)

    /** float field: wire type 5, IEEE 754 bits little-endian. */
    fun float(f: Int, v: Float): ByteArray {
        val b = v.toBits()
        return join(vint((f.toLong() shl 3) or 5), byteArrayOf(b.toByte(), (b ushr 8).toByte(), (b ushr 16).toByte(), (b ushr 24).toByte()))
    }

    fun bytes(f: Int, v: ByteArray): ByteArray = join(vint((f.toLong() shl 3) or 2), vint(v.size.toLong()), v)

    fun text(f: Int, v: String): ByteArray = bytes(f, v.toByteArray(Charsets.UTF_8))

    fun command(type: Int, sub: Int, vararg payload: ByteArray): ByteArray =
        join(num(1, type.toLong()), num(2, sub.toLong()), join(*payload))

    /** Field number to values in wire order; each value is a Long (varint) or a ByteArray. */
    fun decode(b: ByteArray): Map<Int, List<Any>> {
        if (b.size > 65536) throw IllegalArgumentException("Oversize protobuf")
        val out = LinkedHashMap<Int, MutableList<Any>>()
        val pos = intArrayOf(0)
        while (pos[0] < b.size) {
            val tag = read(b, pos)
            val f = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (f < 1) throw IllegalArgumentException("Invalid field")
            val value: Any = when (wire) {
                0 -> read(b, pos)
                2, 1, 5 -> {
                    val len = when (wire) {
                        2 -> read(b, pos)
                        1 -> 8L
                        else -> 4L
                    }
                    if (len > (b.size - pos[0]).toLong() || len < 0) throw IllegalArgumentException("Truncated protobuf")
                    val bytes = b.copyOfRange(pos[0], pos[0] + len.toInt())
                    pos[0] += len.toInt()
                    bytes
                }
                else -> throw IllegalArgumentException("Unsupported wire type")
            }
            out.getOrPut(f) { ArrayList() }.add(value)
        }
        return out
    }

    /** Each field of [b] as its number and its bytes on the wire, tag included, in wire order. */
    fun fields(b: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        val pos = intArrayOf(0)
        while (pos[0] < b.size) {
            val start = pos[0]
            val tag = read(b, pos)
            val len = when ((tag and 7).toInt()) {
                0 -> {
                    read(b, pos)
                    0L
                }
                1 -> 8L
                2 -> read(b, pos)
                5 -> 4L
                else -> throw IllegalArgumentException("Unsupported wire type")
            }
            if (len > (b.size - pos[0]).toLong() || len < 0) throw IllegalArgumentException("Truncated protobuf")
            pos[0] += len.toInt()
            out.add((tag ushr 3).toInt() to b.copyOfRange(start, pos[0]))
        }
        return out
    }

    private fun read(b: ByteArray, p: IntArray): Long {
        var v = 0L
        for (shift in 0 until 64 step 7) {
            if (p[0] >= b.size) throw IllegalArgumentException("Truncated varint")
            val x = b[p[0]++].toInt() and 255
            if (shift == 63 && (x and 254) != 0) throw IllegalArgumentException("Varint overflow")
            v = v or ((x and 127).toLong() shl shift)
            if ((x and 128) == 0) return v
        }
        throw IllegalArgumentException("Varint overflow")
    }

    fun first(m: Map<Int, List<Any>>, f: Int): Any? = m[f]?.get(0)

    fun number(m: Map<Int, List<Any>>, f: Int, fallback: Long): Long = first(m, f) as? Long ?: fallback

    fun blob(m: Map<Int, List<Any>>, f: Int): ByteArray = first(m, f) as? ByteArray ?: ByteArray(0)

    fun message(m: Map<Int, List<Any>>, f: Int): Map<Int, List<Any>> = decode(blob(m, f))

    fun string(m: Map<Int, List<Any>>, f: Int): String = String(blob(m, f), Charsets.UTF_8)
}
