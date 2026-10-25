package local.hyprwatch

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A backup that cannot be read; [message] is shown to the user as is. */
class BackupException(message: String) : Exception(message)

/**
 * The encrypted backup file, apart from Android so host tests run it. Format (API.md, "Backup and
 * restore"): a 52-byte header, then the archive in AES-256-GCM segments of [SEGMENT] plaintext bytes,
 * each sealed with the header as associated data and the nonce `prefix || index || last`, so a cut,
 * reordered or extended file fails. The archive is a list of named files.
 */
object Backup {
    const val VERSION = 1
    const val ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 8

    /** Every name a backup may hold; both databases are required. */
    val NAMES = listOf("notes.db", "health.db", "watch.json", "notification-filter.json")
    val REQUIRED = setOf("notes.db", "health.db")

    const val NOT_BACKUP = "This file isn’t a Hypr Watch backup."
    const val NEWER = "This backup is from a newer version of Hypr Watch. Update the app, then restore."
    const val WRONG_PASSPHRASE = "Wrong passphrase."
    const val DAMAGED = "The backup file is damaged or incomplete."

    private val MAGIC = "HYPRWBAK".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 52
    private const val SALT = 16
    private const val PREFIX = 7
    private const val CHECK = 16
    private const val TAG = 16
    internal const val SEGMENT = 65536
    private const val MAX_ITERATIONS = 10_000_000

    /** Writes [files] (backup name to file) to [out], encrypted with [passphrase]. Does not close [out]. */
    fun write(out: OutputStream, passphrase: String, files: Map<String, File>, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()) {
        require(passphrase.length >= MIN_PASSPHRASE) { "Use a passphrase of at least $MIN_PASSPHRASE characters" }
        require(iterations in 100_000..MAX_ITERATIONS) { "Unsupported iteration count" }
        require(files.keys.all { it in NAMES } && files.keys.containsAll(REQUIRED)) { "Unexpected backup files ${files.keys}" }
        val salt = ByteArray(SALT).also { random.nextBytes(it) }
        val prefix = ByteArray(PREFIX).also { random.nextBytes(it) }
        val keys = Keys(passphrase, salt, iterations)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION.toByte()).putInt(iterations).put(salt).put(prefix).put(keys.check).array()
        out.write(header)
        val sealed = Sealer(out, keys.cipher, header, prefix)
        val archive = DataOutputStream(sealed)
        val buffer = ByteArray(SEGMENT)
        for ((name, file) in files) {
            val bytes = name.toByteArray(Charsets.US_ASCII)
            FileInputStream(file).use { input ->
                // The size of the file as opened, in case it is replaced meanwhile.
                var left = input.channel.size()
                archive.writeByte(bytes.size)
                archive.write(bytes)
                archive.writeLong(left)
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("$name changed while it was being backed up")
                    archive.write(buffer, 0, n)
                    left -= n
                }
            }
        }
        archive.writeByte(0)
        archive.flush()
        sealed.finish()
    }

    /**
     * Decrypts [input] into [dir] and returns the names it held. Throws [BackupException] for a file
     * that is not a backup, a newer version, a wrong passphrase, or one cut or changed; then [dir] holds
     * none of its files. Succeeds only once the last segment is authenticated.
     */
    fun read(input: InputStream, passphrase: String, dir: File): Set<String> {
        val written = LinkedHashSet<String>()
        try {
            val header = ByteArray(HEADER)
            val got = readUpTo(input, header)
            if (got < MAGIC.size || !header.copyOf(MAGIC.size).contentEquals(MAGIC)) throw BackupException(NOT_BACKUP)
            if (got < HEADER) throw BackupException(DAMAGED)
            val h = ByteBuffer.wrap(header, MAGIC.size, HEADER - MAGIC.size)
            val version = h.get().toInt() and 255
            if (version > VERSION) throw BackupException(NEWER)
            if (version != VERSION) throw BackupException(NOT_BACKUP)
            val iterations = h.int
            if (iterations !in 1..MAX_ITERATIONS) throw BackupException(DAMAGED)
            val salt = ByteArray(SALT).also { h.get(it) }
            val prefix = ByteArray(PREFIX).also { h.get(it) }
            val check = ByteArray(CHECK).also { h.get(it) }
            val keys = Keys(passphrase, salt, iterations)
            if (!MessageDigest.isEqual(keys.check, check)) throw BackupException(WRONG_PASSPHRASE)
            val archive = DataInputStream(Opener(input, keys.cipher, header, prefix))
            val buffer = ByteArray(SEGMENT)
            while (true) {
                val length = archive.readUnsignedByte()
                if (length == 0) break
                val name = String(ByteArray(length).also { archive.readFully(it) }, Charsets.US_ASCII)
                if (name !in NAMES || name in written) throw BackupException(DAMAGED)
                var left = archive.readLong()
                if (left < 0) throw BackupException(DAMAGED)
                written.add(name)
                FileOutputStream(File(dir, name)).use { out ->
                    while (left > 0) {
                        val n = archive.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n < 0) throw BackupException(DAMAGED)
                        out.write(buffer, 0, n)
                        left -= n
                    }
                    // On disk before the swap deletes the old copy.
                    out.fd.sync()
                }
            }
            // Reading past the end authenticates the last segment and rejects anything after the archive.
            if (archive.read() != -1 || !written.containsAll(REQUIRED)) throw BackupException(DAMAGED)
            return written
        } catch (e: Exception) {
            for (name in written) File(dir, name).delete()
            throw when (e) {
                is BackupException -> e
                is EOFException -> BackupException(DAMAGED)
                else -> e
            }
        }
    }

    private fun readUpTo(input: InputStream, into: ByteArray): Int {
        var got = 0
        while (got < into.size) {
            val n = input.read(into, got, into.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    /**
     * PBKDF2-HMAC-SHA256 of the NFC-normalized UTF-8 passphrase gives a master key; HMAC-SHA256 of it
     * with fixed labels gives the AES key and a 16-byte check that tells a wrong passphrase apart from
     * a damaged file. Computed here, not by SecretKeyFactory, so every platform encodes the passphrase alike.
     */
    private class Keys(passphrase: String, salt: ByteArray, iterations: Int) {
        val cipher: SecretKeySpec
        val check: ByteArray

        init {
            val master = pbkdf2(Normalizer.normalize(passphrase, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8), salt, iterations)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(master, "HmacSHA256"))
            cipher = SecretKeySpec(mac.doFinal("hypr-watch backup key".toByteArray(Charsets.US_ASCII)), "AES")
            check = mac.doFinal("hypr-watch backup check".toByteArray(Charsets.US_ASCII)).copyOf(CHECK)
            master.fill(0)
        }
    }

    /** RFC 8018 PBKDF2 with HMAC-SHA256, one 32-byte block. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty HMAC key is valid, but SecretKeySpec refuses one; HMAC pads short keys with zeros anyway.
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        password.fill(0)
        mac.update(salt)
        val u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        for (i in 1 until iterations) {
            mac.update(u)
            mac.doFinal(u, 0)
            for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
        }
        return t
    }

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).put(if (last) 1 else 0).array()

    private fun gcm(mode: Int, key: SecretKeySpec, header: ByteArray, prefix: ByteArray, index: Int, last: Boolean): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(mode, key, GCMParameterSpec(TAG * 8, nonce(prefix, index, last)))
            it.updateAAD(header)
        }

    /** Seals what it is given into segments; [finish] seals the last one, empty if need be. */
    private class Sealer(private val out: OutputStream, private val key: SecretKeySpec, private val header: ByteArray, private val prefix: ByteArray) : OutputStream() {
        private val buffer = ByteArray(SEGMENT)
        private var size = 0
        private var index = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                // A full segment is sealed only once more data follows, so the last one is never empty.
                if (size == SEGMENT) seal(false)
                val n = minOf(left, SEGMENT - size)
                System.arraycopy(b, o, buffer, size, n)
                size += n
                o += n
                left -= n
            }
        }

        fun finish() {
            seal(true)
            out.flush()
        }

        private fun seal(last: Boolean) {
            if (index == Int.MAX_VALUE) throw IOException("Backup is too large")
            out.write(gcm(Cipher.ENCRYPT_MODE, key, header, prefix, index, last).doFinal(buffer, 0, size))
            index++
            size = 0
        }
    }

    /** The plaintext of [input]'s segments, each authenticated before any of it is read. */
    private class Opener(private val input: InputStream, private val key: SecretKeySpec, private val header: ByteArray, private val prefix: ByteArray) : InputStream() {
        private val segment = ByteArray(SEGMENT + TAG + 1)
        private var held = 0
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var done = false

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (pos == plain.size) {
                if (done) return -1
                next()
            }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        private fun next() {
            // One byte beyond a full segment tells whether another follows.
            var got = held
            while (got < segment.size) {
                val n = input.read(segment, got, segment.size - got)
                if (n < 0) break
                got += n
            }
            val last = got <= SEGMENT + TAG
            val length = if (last) got else SEGMENT + TAG
            if (length < TAG) throw BackupException(DAMAGED)
            plain = try {
                gcm(Cipher.DECRYPT_MODE, key, header, prefix, index, last).doFinal(segment, 0, length)
            } catch (e: GeneralSecurityException) {
                throw BackupException(DAMAGED)
            }
            pos = 0
            index++
            if (last) {
                done = true
                held = 0
            } else {
                segment[0] = segment[SEGMENT + TAG]
                held = 1
            }
        }
    }

    /**
     * Replaces the app's files with restored ones so that a failure, the process dying or the power
     * going at any step leaves the old ones: every existing target first moves into `old` under [dir],
     * then the new files move into place. A marker file records the phase, and each phase's renames
     * are synced to disk before the marker changes. Paths are on one file system.
     */
    class Swap(private val dir: File, private val disk: Disk = Disk()) {
        private val old = File(dir, "old")
        private val marker = File(dir, "swapping")
        private val tmp = File(dir, "swapping.tmp")

        /** [targets]: every file that makes up the state, present or not. [staged]: target to its new file. */
        fun run(targets: List<File>, staged: Map<File, File>) {
            require(targets.map { it.name }.toSet().size == targets.size && targets.containsAll(staged.keys)) { "Bad swap targets" }
            recover(targets)
            old.mkdirs()
            try {
                phase(MOVING)
                for (t in targets) if (t.exists()) disk.move(t, File(old, t.name))
                // On disk before the marker says new files are being placed: a roll back then deletes them.
                syncDirs(targets + staged.values)
                phase(PLACING + staged.entries.joinToString("") { (t, s) -> "\n${t.name}\t${s.absolutePath}" })
                for ((t, s) in staged) disk.move(s, t)
                syncDirs(targets + staged.values)
                removeMarker()
            } catch (e: Exception) {
                rollBack(targets)
                throw e
            }
            disk.deleteTree(old)
        }

        /** Puts the old files back after a swap the process did not finish; nothing to do otherwise. */
        fun recover(targets: List<File>) {
            if (marker.exists()) {
                rollBack(targets)
            } else {
                if (tmp.exists()) disk.delete(tmp)
                if (old.exists()) disk.deleteTree(old)
            }
        }

        private fun rollBack(targets: List<File>) {
            if (tmp.exists()) disk.delete(tmp)
            // A marker that is missing or unreadable deletes nothing; the old files only move back.
            val lines = if (marker.exists()) marker.readText().split('\n') else emptyList()
            if (lines.firstOrNull() == PLACING) {
                val placed = lines.drop(1).map { it.split('\t') }.filter { it.size == 2 }.associate { it[0] to File(it[1]) }
                for (t in targets) {
                    // Only a file known to be new: its old copy is in `old`, or its staged file moved here.
                    val new = File(old, t.name).exists() || placed[t.name]?.exists() == false
                    if (new && t.exists() && !disk.delete(t)) throw IOException("Could not remove ${t.name}")
                }
                syncDirs(targets)
                // Before any old file moves back, so a second roll back cannot delete it.
                phase(MOVING)
            }
            for (t in targets) {
                val saved = File(old, t.name)
                if (saved.exists()) disk.move(saved, t)
            }
            syncDirs(targets)
            removeMarker()
            disk.deleteTree(old)
        }

        /** Replaces the marker whole, so it is never empty or half written. */
        private fun phase(text: String) {
            disk.write(tmp, text)
            disk.move(tmp, marker)
            disk.sync(dir)
        }

        /** `old` stays while the marker does: a roll back without the old files would lose them. */
        private fun removeMarker() {
            if (!disk.delete(marker) && marker.exists()) throw IOException("Could not remove the swap marker")
            disk.sync(dir)
        }

        private fun syncDirs(files: Collection<File>) {
            for (d in (files.map { it.absoluteFile.parentFile } + old).distinct()) disk.sync(d)
        }

        private companion object {
            const val MOVING = "moving old"
            const val PLACING = "placing new"
        }
    }

    /** Every change [Swap] makes on disk, so tests can fail or stop it at each one. */
    open class Disk {
        open fun move(from: File, to: File) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        /** Writes [text] to [file] and syncs it. */
        open fun write(file: File, text: String) {
            FileOutputStream(file).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }

        open fun delete(file: File): Boolean = file.delete()

        open fun deleteTree(dir: File) {
            dir.deleteRecursively()
        }

        /** Makes the renames in [dir] durable. Where a directory can't be opened (Windows), it does nothing. */
        open fun sync(dir: File) {
            try {
                FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (ignored: IOException) {
            }
        }
    }
}
