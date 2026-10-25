package local.hyprwatch

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Backup and restore of the whole app, started only from the phone's own UI (Backup.kt has the
 * file format). It holds the watch key, so no HTTP route or MCP tool reaches it.
 */
object PhoneBackup {
    private const val TAG = "HyprWatch"
    private const val STOP_MS = 30_000L

    /**
     * True from stopping the service until the restored files are in place. A service created meanwhile
     * (the Activity starts one whenever it is recreated) waits for the restore's start command.
     */
    @Volatile
    var restoring = false
        private set

    /** True while a restore decrypts into `restore/new`, which [recover] otherwise deletes. */
    @Volatile
    private var staging = false

    /**
     * A service start a restore could not make (Android refuses one from the background): whether it
     * should connect the watch. [startIfStopped] makes it once the Activity is in front.
     */
    @Volatile
    private var pendingStart: Boolean? = null

    /** Names other than the databases, kept in the app's files folder. */
    private val FILES = Backup.NAMES.filterNot { it.endsWith(".db") }

    /** Writes an encrypted backup of both databases, the watch key and the notification filter to [out]. */
    fun create(context: Context, out: OutputStream, passphrase: String) {
        val service = NotesService.instance ?: throw BackupException("Hypr Watch isn’t running. Close and reopen it, then try again.")
        val dir = File(context.cacheDir, "backup")
        dir.deleteRecursively()
        dir.mkdirs()
        try {
            service.snapshot(dir)
            val files = LinkedHashMap<String, File>()
            for (name in listOf("notes.db", "health.db")) {
                val copy = File(dir, name)
                if (databaseVersion(copy) == null) throw IOException("The copy of $name failed its check")
                files[name] = copy
            }
            for (name in FILES) File(context.filesDir, name).takeIf { it.exists() }?.let { files[name] = it }
            Backup.write(out, passphrase, files)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Replaces everything [create] backs up with the backup in [input]. The backup is decrypted and its
     * databases checked before anything changes; then the service stops, the files swap (see
     * [Backup.Swap]) and the service starts again, connecting the watch when a key is in place
     * and the watch was not released (see [WatchReconnect.restoreMayConnect]).
     * [beforeStop] runs once the backup checked out, just before the service stops.
     */
    fun restore(context: Context, input: InputStream, passphrase: String, beforeStop: () -> Unit) {
        val dir = File(context.filesDir, "restore")
        val staged = File(dir, "new")
        staging = true
        staged.deleteRecursively()
        staged.mkdirs()
        try {
            val names = Backup.read(input, passphrase, staged)
            val notes = databaseVersion(File(staged, "notes.db"))
            val health = databaseVersion(File(staged, "health.db"))
            if (notes == null || health == null) throw BackupException(Backup.DAMAGED)
            if (notes > NotesStore.VERSION || health > HealthTables.VERSION) throw BackupException(Backup.NEWER)
            // On the copy, so a failing upgrade changes nothing. notes.db has one version.
            prepareHealth(File(staged, "health.db"), health)
            val running = NotesService.instance
            val wasConnected = running?.watchConnected() == true
            var swapped = false
            beforeStop()
            restoring = true
            try {
                context.stopService(Intent(context, NotesService::class.java))
                if (running != null && !running.awaitDestroyed(STOP_MS)) throw IOException("Hypr Watch couldn’t stop in time")
                WristNotificationListener.replacingFilter {
                    Backup.Swap(dir).run(targets(context), names.associate { target(context, it) to File(staged, it) })
                }
                swapped = true
            } finally {
                restoring = false
                // After a failed swap the old files are back: reconnect only if the watch was connected.
                start(context, if (swapped) File(context.filesDir, "watch.json").exists() else wasConnected)
            }
        } finally {
            staged.deleteRecursively()
            staging = false
        }
    }

    /**
     * Before the stores open: puts the old files back if the process died during a restore's swap, and
     * deletes the decrypted files a restore that died left behind.
     */
    fun recover(context: Context) {
        val dir = File(context.filesDir, "restore")
        // The notification listener may have read the filter halfway through the swap.
        WristNotificationListener.replacingFilter { Backup.Swap(dir).recover(targets(context)) }
        if (!staging) File(dir, "new").deleteRecursively()
    }

    /** From the Activity in front: makes the start a restore could not, or starts a stopped service. True when it started one. */
    fun startIfStopped(context: Context): Boolean {
        val connect = pendingStart
        if (restoring || (connect == null && NotesService.instance != null)) return false
        context.startForegroundService(Intent(context, NotesService::class.java).putExtra(NotesService.EXTRA_CONNECT, connect == true))
        pendingStart = null
        return true
    }

    /**
     * Copies [db] to [to] once its WAL is checkpointed into the main file. The caller keeps every
     * writer out, so the copy is the database as of the last commit.
     */
    fun copyDatabase(db: SQLiteDatabase, to: File) {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
            // busy, frames in the WAL, frames checkpointed
            if (!c.moveToFirst() || c.getInt(0) != 0 || c.getInt(1) != c.getInt(2)) throw IOException("The database is busy; try again")
        }
        File(db.path).copyTo(to, overwrite = true)
    }

    /** The schema version of a database copy that passes SQLite's quick check, else null. */
    private fun databaseVersion(file: File): Int? {
        val version = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                val ok = db.rawQuery("PRAGMA quick_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
                if (ok) db.version else null
            }
        } catch (e: SQLiteException) {
            null
        }
        // Closing checkpoints a WAL; one left behind would hold data the file lacks.
        return version?.takeIf { it >= 1 && File(file.path + "-wal").length() == 0L }
    }

    private fun target(context: Context, name: String): File =
        if (name.endsWith(".db")) context.getDatabasePath(name) else File(context.filesDir, name)

    /** Every file a restore replaces, the databases' journals included so none is replayed into a restored file. */
    private fun targets(context: Context): List<File> =
        Backup.NAMES.flatMap { name ->
            val f = target(context, name)
            if (name.endsWith(".db")) listOf(f, File(f.path + "-wal"), File(f.path + "-shm"), File(f.path + "-journal")) else listOf(f)
        }

    /**
     * Brings [file], a copy at schema [from], to the current health schema as HealthStore would on
     * open, and starts its Health Connect export over: this phone's Health Connect may lack every day
     * the backed-up phone sent. Its alarm labels are checked against the watch's first list.
     */
    private fun prepareHealth(file: File, from: Int) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.beginTransaction()
            try {
                if (from < HealthTables.VERSION) {
                    HealthStore.upgrade(db, from)
                    db.version = HealthTables.VERSION
                }
                HealthStore.prepareRestored(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (databaseVersion(file) != HealthTables.VERSION) throw IOException("The prepared health.db failed its check")
    }

    private fun start(context: Context, connect: Boolean) {
        try {
            context.startForegroundService(Intent(context, NotesService::class.java).putExtra(NotesService.EXTRA_CONNECT, connect))
            pendingStart = null
        } catch (e: Exception) {
            // Android refuses while the app is in the background; the Activity starts it when it comes to the front.
            pendingStart = connect
            Log.i(TAG, "Service restart after restore failed: ${e.javaClass.simpleName}")
        }
    }
}
