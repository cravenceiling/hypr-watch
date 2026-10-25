package local.hyprwatch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The backup file: layout, round trip, and every way a read must fail without leaving files behind; then the swap. */
object BackupTest {
    private var assertions = 0
    private const val PASS = "correct horse battery"

    // The read side accepts any count; 100,000 is the writer's floor and keeps the suite quick.
    private const val ITERATIONS = 100_000

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun tempDir(): File = Files.createTempDirectory("backup-test").toFile().also { it.deleteOnExit() }

    private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 31 + seed).toByte() }

    private fun source(files: Map<String, ByteArray>): Map<String, File> {
        val dir = tempDir()
        return files.mapValues { (name, data) -> File(dir, name).also { it.writeBytes(data) } }
    }

    private fun backup(files: Map<String, ByteArray>, passphrase: String = PASS): ByteArray =
        ByteArrayOutputStream().also { Backup.write(it, passphrase, source(files), ITERATIONS) }.toByteArray()

    /** The message a read fails with, or null when it succeeds; [dir] gets what it wrote. */
    private fun failure(file: ByteArray, dir: File = tempDir(), passphrase: String = PASS): String? =
        try {
            Backup.read(ByteArrayInputStream(file), passphrase, dir)
            null
        } catch (e: BackupException) {
            e.message
        }

    private fun failsCleanly(file: ByteArray, expected: String, label: String, passphrase: String = PASS) {
        val dir = tempDir()
        val message = failure(file, dir, passphrase)
        check(message == expected, "$label: got $message")
        check(dir.list()!!.isEmpty(), "$label: left ${dir.list()!!.toList()}")
    }

    private fun edited(file: ByteArray, at: Int, value: Int? = null) = file.copyOf().also { it[at] = (value ?: (it[at].toInt() xor 1)).toByte() }

    @JvmStatic
    fun main(args: Array<String>) {
        // RFC 7914, section 11: PBKDF2-HMAC-SHA256("passwd", "salt", 1), first 32 bytes.
        check(
            hex(Backup.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1)) == "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            "PBKDF2 known answer",
        )
        for (iterations in listOf(2, 4096)) {
            val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("pass phrase".toCharArray(), "NaCl".toByteArray(), iterations, 256)).encoded
            check(Backup.pbkdf2("pass phrase".toByteArray(), "NaCl".toByteArray(), iterations).contentEquals(jdk), "PBKDF2 matches the JDK at $iterations iterations")
        }

        // Round trip across several segments, one of the files exactly a segment long.
        val all = linkedMapOf(
            "notes.db" to bytes(200_000, 1),
            "health.db" to bytes(Backup.SEGMENT, 2),
            "watch.json" to "{\"auth_key\":\"00112233445566778899aabbccddeeff\"}".toByteArray(),
            "notification-filter.json" to "{\"calls\":true,\"allowed\":[]}".toByteArray(),
        )
        val file = backup(all)
        check(hex(file.copyOf(13)) == "485950525742414b01000186a0", "Header starts with magic, version 1 and the iteration count")
        val plain = all.entries.sumOf { 1 + it.key.length + 8 + it.value.size } + 1
        val segments = plain / Backup.SEGMENT + 1
        check(file.size == 52 + plain + 16 * segments, "Size is header, archive and one tag per segment: ${file.size}")
        check(backup(all).copyOfRange(13, 52).contentEquals(file.copyOfRange(13, 52)).not(), "Salt, nonce prefix and check are fresh per backup")
        val dir = tempDir()
        check(Backup.read(ByteArrayInputStream(file), PASS, dir) == all.keys, "Read returns every name")
        for ((name, data) in all) check(File(dir, name).readBytes().contentEquals(data), "$name restored byte for byte")

        // Optional files may be missing; the databases may not.
        val minimal = linkedMapOf("notes.db" to bytes(10, 3), "health.db" to bytes(0, 4))
        val small = tempDir()
        check(Backup.read(ByteArrayInputStream(backup(minimal)), PASS, small) == setOf("notes.db", "health.db"), "Only the databases")
        check(small.list()!!.toSet() == setOf("notes.db", "health.db") && File(small, "health.db").length() == 0L, "An empty database file round-trips")
        check(
            runCatching { backup(mapOf("notes.db" to bytes(1, 5))) }.exceptionOrNull() is IllegalArgumentException,
            "A backup without health.db is refused",
        )
        check(runCatching { backup(minimal, "short") }.exceptionOrNull() is IllegalArgumentException, "A short passphrase is refused")

        // A passphrase is compared after NFC normalization: é typed as one code point or as e + accent.
        val accented = backup(minimal, "café au lait")
        check(failure(accented, passphrase = "café au lait") == null, "Normalized passphrase opens the backup")

        // Every failure leaves nothing in the target folder.
        failsCleanly(file, Backup.WRONG_PASSPHRASE, "Wrong passphrase", passphrase = "correct horse battery!")
        failsCleanly(edited(file, 13), Backup.WRONG_PASSPHRASE, "Changed salt")
        failsCleanly(edited(file, 40), Backup.WRONG_PASSPHRASE, "Changed key check")
        failsCleanly(edited(file, 30), Backup.DAMAGED, "Changed nonce prefix")
        failsCleanly(edited(file, 100), Backup.DAMAGED, "Changed byte in the first segment")
        failsCleanly(edited(file, file.size - 20), Backup.DAMAGED, "Changed byte in the last segment")
        failsCleanly(edited(file, file.size - 1), Backup.DAMAGED, "Changed tag")
        failsCleanly(edited(file, 8, 2), Backup.NEWER, "Version 2")
        failsCleanly(edited(file, 8, 0), Backup.NOT_BACKUP, "Version 0")
        failsCleanly(edited(file, 0, 'X'.code), Backup.NOT_BACKUP, "Other magic")
        failsCleanly(ByteArray(0), Backup.NOT_BACKUP, "Empty file")
        failsCleanly("{\"notes\":[]}".toByteArray(), Backup.NOT_BACKUP, "A notes export")
        failsCleanly(file.copyOf(4), Backup.NOT_BACKUP, "Cut inside the magic")
        failsCleanly(file.copyOf(30), Backup.DAMAGED, "Cut inside the header")
        failsCleanly(file.copyOf(52), Backup.DAMAGED, "Header only")
        failsCleanly(file.copyOf(file.size - 1), Backup.DAMAGED, "Last byte cut")
        failsCleanly(file.copyOf(52 + Backup.SEGMENT + 16), Backup.DAMAGED, "Cut at a segment boundary")
        check(segments == 5, "Five segments")
        failsCleanly(file.copyOf(52 + 4 * (Backup.SEGMENT + 16)), Backup.DAMAGED, "Last segment dropped")
        failsCleanly(file + byteArrayOf(0), Backup.DAMAGED, "A byte appended")
        failsCleanly(file + ByteArray(Backup.SEGMENT + 16), Backup.DAMAGED, "A segment-sized block appended")

        // An archive of exactly two segments: the last one is full and still marked last.
        val exact = linkedMapOf("notes.db" to bytes(2 * Backup.SEGMENT - (1 + 8 + 8) - (1 + 9 + 8) - 1, 6), "health.db" to bytes(0, 7))
        val exactFile = backup(exact)
        check(exactFile.size == 52 + 2 * (Backup.SEGMENT + 16), "Two full segments: ${exactFile.size}")
        check(failure(exactFile) == null, "A full last segment reads")
        failsCleanly(exactFile.copyOf(52 + Backup.SEGMENT + 16), Backup.DAMAGED, "A full last segment dropped")
        val first = file.copyOfRange(52, 52 + Backup.SEGMENT + 16)
        val second = file.copyOfRange(52 + Backup.SEGMENT + 16, 52 + 2 * (Backup.SEGMENT + 16))
        val swapped = file.copyOf(52) + second + first + file.copyOfRange(52 + 2 * (Backup.SEGMENT + 16), file.size)
        failsCleanly(swapped, Backup.DAMAGED, "Segments reordered")
        val otherFile = backup(all)
        failsCleanly(file.copyOf(52) + otherFile.copyOfRange(52, otherFile.size), Backup.DAMAGED, "Another backup's segments under this header")
        val iterationsZero = file.copyOf().also { for (i in 9..12) it[i] = 0 }
        failsCleanly(iterationsZero, Backup.DAMAGED, "Zero iterations")

        // The swap: the old state survives any failure, crash or lost rename, and a finished swap leaves no trace.
        swapSucceeds()
        for (at in 0..COMMIT) swapFails(at)
        for (at in SUCCESS.indices) swapCrashes(at)
        rollBackCrashes()
        markerStays(always = true)
        markerStays(always = false)
        unreadableMarker()
        renamesLost()
        println("Backup format, crypto and swap: $assertions assertions passed")
    }

    /** The app's files in one folder, the restored ones in another, and the swap's own folder. */
    private class Layout {
        val root = tempDir()
        val app = File(root, "app").also { it.mkdirs() }
        val new = File(root, "new").also { it.mkdirs() }
        val swapDir = File(root, "restore").also { it.mkdirs() }
        val old = File(swapDir, "old")
        val marker = File(swapDir, "swapping")
        val targets = listOf("notes.db", "notes.db-wal", "health.db", "watch.json", "notification-filter.json").map { File(app, it) }
        val staged = LinkedHashMap<File, File>()

        init {
            File(app, "notes.db").writeText("old notes")
            File(app, "notes.db-wal").writeText("old wal")
            File(app, "health.db").writeText("old health")
            File(app, "watch.json").writeText("old key")
            for ((name, text) in listOf("notes.db" to "new notes", "health.db" to "new health", "notification-filter.json" to "new filter")) {
                staged[File(app, name)] = File(new, name).also { it.writeText(text) }
            }
        }

        fun state(): Map<String, String?> = targets.associate { it.name to if (it.exists()) it.readText() else null }

        fun clean() = swapDir.list()!!.isEmpty()

        /** The marker [Backup.Swap] writes once the old files are aside. */
        fun placing() = "placing new" + staged.entries.joinToString("") { (t, s) -> "\n${t.name}\t${s.absolutePath}" }
    }

    private val OLD = mapOf("notes.db" to "old notes", "notes.db-wal" to "old wal", "health.db" to "old health", "watch.json" to "old key", "notification-filter.json" to null)
    private val NEW = mapOf("notes.db" to "new notes", "notes.db-wal" to null, "health.db" to "new health", "watch.json" to null, "notification-filter.json" to "new filter")

    /** Every disk change of a swap that succeeds; the marker's removal at [COMMIT] makes the new files stay. */
    private val SUCCESS = listOf(
        "write restore/swapping.tmp: moving old",
        "move restore/swapping.tmp restore/swapping",
        "sync restore",
        "move app/notes.db restore/old/notes.db",
        "move app/notes.db-wal restore/old/notes.db-wal",
        "move app/health.db restore/old/health.db",
        "move app/watch.json restore/old/watch.json",
        "sync app",
        "sync new",
        "sync restore/old",
        "write restore/swapping.tmp: placing new notes.db=new/notes.db health.db=new/health.db notification-filter.json=new/notification-filter.json",
        "move restore/swapping.tmp restore/swapping",
        "sync restore",
        "move new/notes.db app/notes.db",
        "move new/health.db app/health.db",
        "move new/notification-filter.json app/notification-filter.json",
        "sync app",
        "sync new",
        "sync restore/old",
        "delete restore/swapping",
        "sync restore",
        "delete tree restore/old",
    )
    private const val COMMIT = 19

    /** Logs every change relative to [root]; the change at an index in [faults] throws instead of happening. */
    private class FaultyDisk(val root: File, val faults: Map<Int, () -> Throwable> = emptyMap()) : Backup.Disk() {
        val log = ArrayList<String>()

        private fun step(op: String) {
            val fault = faults[log.size]
            log.add(op)
            if (fault != null) throw fault()
        }

        private fun rel(f: File) = f.relativeTo(root).path

        override fun move(from: File, to: File) {
            step("move ${rel(from)} ${rel(to)}")
            super.move(from, to)
        }

        override fun write(file: File, text: String) {
            step("write ${rel(file)}: " + text.replace(root.path + File.separator, "").replace('\n', ' ').replace('\t', '='))
            super.write(file, text)
        }

        override fun delete(file: File): Boolean {
            step("delete ${rel(file)}")
            return super.delete(file)
        }

        override fun deleteTree(dir: File) {
            step("delete tree ${rel(dir)}")
            super.deleteTree(dir)
        }

        override fun sync(dir: File) {
            step("sync ${rel(dir)}")
            super.sync(dir)
        }
    }

    private class Crash : Error()

    private val diskFull = { java.io.IOException("disk full") }

    private fun swapSucceeds() {
        val l = Layout()
        val disk = FaultyDisk(l.root)
        Backup.Swap(l.swapDir, disk).run(l.targets, l.staged)
        check(l.state() == NEW, "Swap: new files in place, the old WAL and key gone: ${l.state()}")
        check(l.clean() && l.new.list()!!.isEmpty(), "Swap: nothing left over")
        // Each phase's renames are synced (both folders) before the marker changes, and the marker's removal before `old` goes.
        check(disk.log == SUCCESS, "Swap: disk changes in order: ${disk.log}")
        val plain = Layout()
        Backup.Swap(plain.swapDir).run(plain.targets, plain.staged)
        check(plain.state() == NEW && plain.clean(), "Swap with real directory syncs: ${plain.state()}")
    }

    private fun swapFails(at: Int) {
        val l = Layout()
        val error = runCatching { Backup.Swap(l.swapDir, FaultyDisk(l.root, mapOf(at to diskFull))).run(l.targets, l.staged) }.exceptionOrNull()
        check(error?.message == "disk full", "Swap failing at ${SUCCESS[at]} reports it")
        check(l.state() == OLD && l.clean(), "Swap failing at ${SUCCESS[at]} keeps the old files: ${l.state()}")
    }

    private fun swapCrashes(at: Int) {
        val l = Layout()
        check(runCatching { Backup.Swap(l.swapDir, FaultyDisk(l.root, mapOf(at to { Crash() }))).run(l.targets, l.staged) }.exceptionOrNull() is Crash, "Crash $at")
        Backup.Swap(l.swapDir).recover(l.targets)
        val expected = if (at <= COMMIT) OLD else NEW
        check(l.state() == expected && l.clean(), "Recovery after a crash at ${SUCCESS[at]}: ${l.state()}")
    }

    /** Placing the third new file fails, and the process dies at each change of the roll back. */
    private fun rollBackCrashes() {
        val failAt = SUCCESS.indexOf("move new/notification-filter.json app/notification-filter.json")
        val probe = Layout()
        val disk = FaultyDisk(probe.root, mapOf(failAt to diskFull))
        runCatching { Backup.Swap(probe.swapDir, disk).run(probe.targets, probe.staged) }
        check(probe.state() == OLD && probe.clean(), "Roll back after a failed placing move")
        val rollBack = listOf(
            "delete app/notes.db",
            "delete app/health.db",
            "sync app",
            "sync restore/old",
            "write restore/swapping.tmp: moving old",
            "move restore/swapping.tmp restore/swapping",
            "sync restore",
            "move restore/old/notes.db app/notes.db",
            "move restore/old/notes.db-wal app/notes.db-wal",
            "move restore/old/health.db app/health.db",
            "move restore/old/watch.json app/watch.json",
            "sync app",
            "sync restore/old",
            "delete restore/swapping",
            "sync restore",
            "delete tree restore/old",
        )
        // Only the two placed files are deleted, before the marker goes back to moving old.
        check(disk.log.drop(failAt + 1) == rollBack, "Roll back changes: ${disk.log.drop(failAt + 1)}")
        for (at in failAt + 1 until disk.log.size) {
            val l = Layout()
            val faults = mapOf(failAt to diskFull, at to { Crash() })
            check(runCatching { Backup.Swap(l.swapDir, FaultyDisk(l.root, faults)).run(l.targets, l.staged) }.exceptionOrNull() is Crash, "Crash at roll back change $at")
            Backup.Swap(l.swapDir).recover(l.targets)
            check(l.state() == OLD && l.clean(), "Recovery finishes a roll back stopped at ${disk.log[at]}: ${l.state()}")
            Backup.Swap(l.swapDir).recover(l.targets)
            check(l.state() == OLD, "Recovery is idempotent")
        }
    }

    /** The marker can't be removed: the swap rolls back and never deletes `old` while the marker stays. */
    private fun markerStays(always: Boolean) {
        val l = Layout()
        var refusals = 0
        val disk = object : Backup.Disk() {
            override fun delete(file: File): Boolean =
                if (file == l.marker && (always || refusals == 0)) {
                    refusals++
                    false
                } else {
                    super.delete(file)
                }
        }
        val error = runCatching { Backup.Swap(l.swapDir, disk).run(l.targets, l.staged) }.exceptionOrNull()
        check(error?.message == "Could not remove the swap marker", "Marker kept (always=$always): $error")
        check(l.state() == OLD, "Marker kept (always=$always): old files back: ${l.state()}")
        check(l.clean() != always && (!always || l.old.isDirectory), "Marker kept (always=$always): `old` goes only with the marker")
        Backup.Swap(l.swapDir).recover(l.targets)
        check(l.state() == OLD && l.clean(), "Marker kept (always=$always): the next start finishes: ${l.state()}")
    }

    /** An empty or cut marker deletes nothing: old files move back and a placed file without one stays. */
    private fun unreadableMarker() {
        for (text in listOf("", "placing n", "placing new\nnotes.d")) {
            val l = Layout()
            l.old.mkdirs()
            for (t in l.targets) if (t.exists()) Backup.Disk().move(t, File(l.old, t.name))
            for (name in listOf("notes.db", "notification-filter.json")) Backup.Disk().move(File(l.new, name), File(l.app, name))
            l.marker.writeText(text)
            Backup.Swap(l.swapDir).recover(l.targets)
            check(l.state() == OLD + ("notification-filter.json" to "new filter") && l.clean(), "Marker '$text': ${l.state()}")
        }
    }

    /** Power lost after the marker said placing, but the renames into `old` never reached the disk. */
    private fun renamesLost() {
        val l = Layout()
        l.old.mkdirs()
        l.marker.writeText(l.placing())
        Backup.Swap(l.swapDir).recover(l.targets)
        check(l.state() == OLD && l.clean(), "Lost renames: every old file kept: ${l.state()}")
    }
}
