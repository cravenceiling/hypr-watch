package local.hyprwatch

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

object ProtocolTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun hex(b: ByteArray): String = b.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }

    private fun check(condition: Boolean, name: String) {
        if (!condition) throw AssertionError(name)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty() && args[0] == "ccm") {
            // Vector mode for cross-checking AES-CCM: each stdin line is "key nonce plaintext" in hex.
            val input = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val line = input.readLine() ?: break
                val p = line.split(" ")
                println(hex(WatchCrypto.ccm(hex(p[0]), hex(p[1]), hex(p[2]))))
            }
            return
        }
        check(Frames.crc("123456789".toByteArray(Charsets.US_ASCII)) == 0xbb3d, "CRC-16/ARC known answer")
        val packet = Frames.encode(3, 7, hex("01010811100e"))
        var reader = Frames.Reader()
        for (i in 0 until packet.size - 1) {
            reader.add(byteArrayOf(packet[i]), 1)
            check(reader.next(true) == null, "Partial frame must not emit")
        }
        reader.add(byteArrayOf(packet[packet.size - 1]), 1)
        val p = reader.next(true)!!
        check(p.type == 3 && p.seq == 7 && p.channel == 1 && p.opcode == 1 && hex(p.payload) == "0811100e", "SPP frame reconstruction")
        val corrupt = packet.copyOf()
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toInt() xor 1).toByte()
        val dropped = ArrayList<String>()
        reader = Frames.Reader { dropped.add(it) }
        val next = Frames.encode(3, 8, hex("01010811100e"))
        reader.add(corrupt + next, corrupt.size + next.size)
        val after = reader.next(true)
        check(after != null && after.seq == 8 && reader.next(true) == null, "Corrupt frame skipped; the next frame still parses")
        check(dropped == listOf("Dropped frame 7 with a bad checksum"), "Corrupt frame reported")
        var bad = false
        val cmd = Proto.decode(hex("08141000a00601"))
        check(Proto.number(cmd, 100, 0) == 1L && !cmd.containsKey(22), "Nonzero status without payload remains a failure")
        bad = false
        try {
            Proto.decode(hex("0a7f00"))
        } catch (e: IllegalArgumentException) {
            bad = true
        }
        check(bad, "Truncated protobuf rejected")
        val combined = Proto.join(Proto.num(1, 17), Proto.num(2, 14), Proto.bytes(19, Proto.bytes(10, Proto.num(2, 50))))
        check(Proto.number(Proto.message(Proto.message(Proto.decode(combined), 19), 10), 2, 0) == 50L, "Nested reminder capacity")
        check(hex(Proto.u32(1, -1)) == "08ffffffff0f", "Negative int encodes as 5-byte uint32")
        check(hex(Proto.u32(1, 300)) == "08ac02", "Positive uint32 unchanged")
        check(Proto.number(Proto.decode(Proto.u32(1, Int.MIN_VALUE)), 1, 0) == 0x80000000L, "uint32 round trip of Int.MIN_VALUE")
        check(hex(Proto.s32(1, -1)) == "0801", "sint32 -1 zigzags to 1")
        check(hex(Proto.s32(1, 1)) == "0802", "sint32 1 zigzags to 2")
        check(hex(Proto.s32(1, -18000)) == "089f9902", "sint32 -18000 zigzags to 35999")
        check(hex(Proto.s32(1, Int.MIN_VALUE)) == "08ffffffff0f", "sint32 Int.MIN_VALUE zigzags to 0xFFFFFFFF")
        check(hex(Proto.float(2, 70f)) == "1500008c42", "Float as fixed32 little-endian")
        check(hex(Proto.float(2, 35f)) == "1500000c42", "Float matches the auth step's hand-written weight")
        check(
            Frames.sessionConfig(Frames.SESSION) == "opcode 1: version 1.0.0, max packet 64512 bytes, tx window 32 frames, send timeout 10000 ms",
            "Session config limits are readable",
        )
        check(Frames.sessionConfig(hex("0209010000")) == "opcode 2: key 9 = 00", "Unknown session config key is shown raw")
        println("Protocol framing, corruption, bounded parsing, status handling, uint32/float: passed")
    }
}
