// SPDX-License-Identifier: AGPL-3.0-or-later
// Derived from Gadgetbridge's XiaomiAuthService and XiaomiSppProtocolV2; see NOTICE.
package local.hyprwatch

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/** Xiaomi miwear-auth and SPP v2 crypto, derived from Gadgetbridge's protocol. */
class WatchCrypto(secret: ByteArray) {
    private val secret: ByteArray
    private var phoneNonce: ByteArray? = null

    // Null until finish(); passed through as null so early use fails inside SecretKeySpec as before.
    private var encKey: ByteArray? = null
    private var decKey: ByteArray? = null
    private var encNonce: ByteArray? = null

    init {
        if (secret.size != 16) throw IllegalArgumentException("Invalid watch key")
        this.secret = secret.clone()
    }

    fun begin(): ByteArray {
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        phoneNonce = nonce
        return Proto.command(1, 26, Proto.bytes(3, Proto.bytes(30, Proto.bytes(1, nonce))))
    }

    @Throws(GeneralSecurityException::class)
    fun finish(watchNonce: ByteArray, watchHmac: ByteArray): ByteArray {
        if (watchNonce.size != 16 || watchHmac.size != 32) throw GeneralSecurityException("Invalid watch challenge")
        val phone = phoneNonce!!
        val salt = hmac(Proto.join(phone, watchNonce), secret)
        var expansion = ByteArray(0)
        var prev = ByteArray(0)
        for (i in 1..2) {
            prev = hmac(salt, Proto.join(prev, "miwear-auth".toByteArray(Charsets.US_ASCII), byteArrayOf(i.toByte())))
            expansion = Proto.join(expansion, prev)
        }
        val dec = expansion.copyOfRange(0, 16)
        val enc = expansion.copyOfRange(16, 32)
        val nonce = expansion.copyOfRange(36, 40)
        decKey = dec
        encKey = enc
        encNonce = nonce
        if (!MessageDigest.isEqual(hmac(dec, Proto.join(watchNonce, phone)), watchHmac)) {
            throw GeneralSecurityException("Watch authentication failed")
        }
        val f = 35f.toBits()
        val info = Proto.join(
            Proto.num(1, 0),
            byteArrayOf(0x15, f.toByte(), (f ushr 8).toByte(), (f ushr 16).toByte(), (f ushr 24).toByte()),
            Proto.text(3, "Hypr Watch"),
            Proto.num(4, 224),
            Proto.text(5, "CO"),
        )
        val step = Proto.join(
            Proto.bytes(1, hmac(enc, Proto.join(phone, watchNonce))),
            Proto.bytes(2, ccm(enc, Proto.join(nonce, ByteArray(8)), info)),
        )
        return Proto.command(1, 27, Proto.bytes(3, Proto.bytes(32, step)))
    }

    @Throws(GeneralSecurityException::class)
    fun encrypt(p: ByteArray): ByteArray = ctr(encKey, encKey, p)

    @Throws(GeneralSecurityException::class)
    fun decrypt(p: ByteArray): ByteArray = ctr(decKey, decKey, p)

    companion object {
        internal fun hmac(k: ByteArray, m: ByteArray): ByteArray {
            val h = Mac.getInstance("HmacSHA256")
            h.init(SecretKeySpec(k, "HmacSHA256"))
            return h.doFinal(m)
        }

        internal fun ctr(key: ByteArray?, iv: ByteArray?, p: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return c.doFinal(p)
        }

        /** CCM with 12-byte nonce, 4-byte tag, and no associated data. */
        internal fun ccm(key: ByteArray, nonce: ByteArray, p: ByteArray): ByteArray {
            if (nonce.size != 12 || p.size > 0xffffff) throw GeneralSecurityException("Invalid CCM parameters")
            val aes = Cipher.getInstance("AES/ECB/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val block = ByteArray(16)
            block[0] = 10
            nonce.copyInto(block, 1, 0, 12)
            block[13] = (p.size ushr 16).toByte()
            block[14] = (p.size ushr 8).toByte()
            block[15] = p.size.toByte()
            var mac = aes.doFinal(block)
            for (i in p.indices step 16) {
                block.fill(0)
                p.copyInto(block, 0, i, i + min(16, p.size - i))
                for (j in 0 until 16) {
                    block[j] = (block[j].toInt() xor mac[j].toInt()).toByte()
                }
                mac = aes.doFinal(block)
            }
            val counter = ByteArray(16)
            counter[0] = 2
            nonce.copyInto(counter, 1, 0, 12)
            val s0 = aes.doFinal(counter)
            val out = ByteArray(p.size + 4)
            for (i in p.indices step 16) {
                val n = i / 16 + 1
                counter[13] = (n ushr 16).toByte()
                counter[14] = (n ushr 8).toByte()
                counter[15] = n.toByte()
                val stream = aes.doFinal(counter)
                for (j in 0 until min(16, p.size - i)) {
                    out[i + j] = (p[i + j].toInt() xor stream[j].toInt()).toByte()
                }
            }
            for (j in 0 until 4) {
                out[p.size + j] = (mac[j].toInt() xor s0[j].toInt()).toByte()
            }
            return out
        }
    }
}
