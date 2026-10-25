package local.hyprwatch

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * [HealthTables] in `health.db`, in their own database so the Bluetooth reader thread never waits
 * on the notes lock that NotesSync holds across watch requests.
 */
class HealthStore(c: Context) : SQLiteOpenHelper(c, "health.db", null, HealthTables.VERSION), HealthFeature.Store, SettingsStore, HealthIngest.Store, HealthConnectExport.Source {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    private val tables = HealthTables(AndroidSql { db() })

    // getWritableDatabase() takes this object's lock, which save() holds while waiting for SQLite's
    // write connection. Resolving it per statement inside an ingest transaction would deadlock, so
    // resolve it once and reuse it.
    @Volatile private var database: SQLiteDatabase? = null

    private fun db(): SQLiteDatabase = database ?: synchronized(this) { database ?: writableDatabase.also { database = it } }

    // Every method runs inside open(): close() waits for those already running, and later calls
    // throw instead of reopening the database.
    private val gate = ReentrantReadWriteLock()
    private var closed = false

    private inline fun <T> open(block: () -> T): T {
        gate.readLock().lock()
        try {
            check(!closed) { "Health database is closed" }
            return block()
        } finally {
            gate.readLock().unlock()
        }
    }

    // seen, save, the file listing and settings also share this object's lock, always taken inside
    // the gate: SQLiteOpenHelper locks the same object, so the other order could deadlock with close().
    private inline fun <T> locked(block: () -> T): T = open { synchronized(this) { block() } }

    override fun close() {
        gate.writeLock().lock()
        try {
            closed = true
            super.close()
        } finally {
            gate.writeLock().unlock()
        }
    }

    /** A consistent copy for a backup: holding the gate's write lock keeps every other call out meanwhile. */
    fun snapshot(to: File) {
        gate.writeLock().lock()
        try {
            check(!closed) { "Health database is closed" }
            PhoneBackup.copyDatabase(db(), to)
        } finally {
            gate.writeLock().unlock()
        }
    }

    /** Called on the saving thread after a new version is stored; must not block. */
    @Volatile
    var onNewVersion: (() -> Unit)? = null

    // Android prepares a PRAGMA on the primary connection, which does every write, after it set WAL.
    override fun onConfigure(db: SQLiteDatabase) = HealthTables(AndroidSql { db }).configure()

    override fun onCreate(db: SQLiteDatabase) = HealthTables(AndroidSql { db }).create()

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = upgrade(db, oldVersion)

    override fun seen(ids: List<ByteArray>, at: Long): Set<String> = locked { tables.seen(ids, at) }

    override fun save(id: ByteArray, crc: Long, data: ByteArray, complete: Boolean, at: Long): Boolean =
        locked { tables.save(id, crc, data, complete, at) }.also { if (it) onNewVersion?.invoke() }

    override fun list(): JSONArray = locked { tables.list() }

    override fun count(): Long = locked { tables.count() }

    override fun file(idHex: String, crc: Long?): JSONObject? = locked { tables.file(idHex, crc) }

    override fun setting(key: String): String? = locked { tables.setting(key) }

    override fun setting(key: String, value: String) = locked { tables.setting(key, value) }

    override fun profile(): JSONObject? = setting("profile")?.let { JSONObject(it) }

    override fun profile(p: JSONObject) = setting("profile", p.toString())

    fun unlockSyncMinutes(): Long = setting("unlock_sync_minutes")?.toLongOrNull() ?: 15L

    // Ingest and queries are not locked(): SQLite serializes the writes, and WAL keeps long reads
    // from blocking the Bluetooth thread's saves.

    override fun versions(): List<FileVersion> = open { tables.versions() }

    override fun ingested(): Map<String, IngestMark> = open { tables.ingested() }

    override fun raw(idHex: String, crc: Long): ByteArray? = open { tables.raw(idHex, crc) }

    override fun forgetIngested() = open { tables.forgetIngested() }

    override fun replace(idHex: String, mark: IngestMark, rows: HealthRows, outcome: IngestOutcome, at: Long) =
        open { tables.replace(idHex, mark, rows, outcome, at) }

    override fun dailySummaries(from: String, to: String): List<DailyRow> = open { tables.dailySummaries(from, to) }

    override fun forEachMinute(from: String, to: String, block: (MinuteRow) -> Unit) = open { tables.forEachMinute(from, to, block) }

    override fun sleepSessions(from: String, to: String, limit: Int): List<SleepRow> = open { tables.sleepSessions(from, to, limit) }

    override fun manualSamples(from: String, to: String, limit: Int): List<ManualRow> = open { tables.manualSamples(from, to, limit) }

    override fun workouts(from: String, to: String, limit: Int): List<WorkoutRow> = open { tables.workouts(from, to, limit) }

    override fun parseReport(): List<ReportRow> = open { tables.parseReport() }

    override fun ingestSeq(): Long = open { tables.ingestSeq() }

    override fun exportDates(ingestedAfter: Long?): List<String> = open { tables.exportDates(ingestedAfter) }

    companion object {
        /** Also run by a restore on its copy of health.db, before the copy replaces this one. */
        fun upgrade(db: SQLiteDatabase, oldVersion: Int) = HealthTables(AndroidSql { db }).upgrade(oldVersion)

        /** Run by a restore on its copy of health.db; see HealthConnectExport.forgetProgress and markAlarmsRestored. */
        fun prepareRestored(db: SQLiteDatabase) {
            val tables = HealthTables(AndroidSql { db })
            HealthConnectExport.forgetProgress(tables)
            markAlarmsRestored(tables::setting)
        }
    }
}

/** [HealthSql] on an Android database; [db] is read on each call. */
private class AndroidSql(private val db: () -> SQLiteDatabase) : HealthSql {
    override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db().execSQL(sql) else update(sql, *args)
    }

    override fun update(sql: String, vararg args: Any?): Int =
        db().compileStatement(sql).use { s ->
            bind(s, args)
            s.executeUpdateDelete()
        }

    override fun <T> insertAll(sql: String, rows: List<T>, values: (T) -> Array<Any?>) {
        db().compileStatement(sql).use { s ->
            for (r in rows) {
                s.clearBindings()
                bind(s, values(r))
                s.executeInsert()
            }
        }
    }

    override fun <T> query(sql: String, args: List<String>, read: (HealthSql.Row) -> T): T =
        db().rawQuery(sql, args.toTypedArray()).use { c ->
            read(
                object : HealthSql.Row {
                    override fun next() = c.moveToNext()

                    override fun isNull(i: Int) = c.isNull(i)

                    override fun long(i: Int) = c.getLong(i)

                    override fun string(i: Int): String = c.getString(i)

                    override fun blob(i: Int): ByteArray = c.getBlob(i)
                },
            )
        }

    override fun <T> transaction(block: () -> T): T {
        val d = db()
        d.beginTransaction()
        try {
            return block().also { d.setTransactionSuccessful() }
        } finally {
            d.endTransaction()
        }
    }

    private fun bind(s: SQLiteStatement, args: Array<out Any?>) {
        for ((k, v) in args.withIndex()) {
            val i = k + 1
            when (v) {
                null -> s.bindNull(i)
                is Long -> s.bindLong(i, v)
                is Int -> s.bindLong(i, v.toLong())
                is String -> s.bindString(i, v)
                is ByteArray -> s.bindBlob(i, v)
                else -> throw IllegalArgumentException("Unsupported SQL argument ${v.javaClass.simpleName}")
            }
        }
    }
}
