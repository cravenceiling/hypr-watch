package local.hyprwatch

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class NotesStore(c: Context) : SQLiteOpenHelper(c, "notes.db", null, VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    // NotesSync commits an intent before each watch write; FULL keeps that commit through a power cut.
    override fun onConfigure(db: SQLiteDatabase) = db.execSQL("PRAGMA synchronous=FULL")

    // A request still running when the service stops must not reopen the file a restore is replacing.
    private var closed = false

    @Synchronized
    override fun close() {
        closed = true
        super.close()
    }

    override fun getWritableDatabase(): SQLiteDatabase {
        check(!closed) { "Notes database is closed" }
        return super.getWritableDatabase()
    }

    override fun getReadableDatabase(): SQLiteDatabase {
        check(!closed) { "Notes database is closed" }
        return super.getReadableDatabase()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes (id TEXT PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL DEFAULT '',done INTEGER NOT NULL DEFAULT 0," +
                "watch_enabled INTEGER NOT NULL DEFAULT 0,watch_title TEXT NOT NULL DEFAULT '',watch_id INTEGER,watch_fingerprint TEXT," +
                "watch_state TEXT NOT NULL DEFAULT 'not_sent',updated_at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Unsupported database upgrade")
    }

    @Synchronized
    fun list(): JSONArray {
        val a = JSONArray()
        readableDatabase.rawQuery("SELECT * FROM notes ORDER BY done,updated_at DESC", null).use { c ->
            while (c.moveToNext()) {
                a.put(row(c))
            }
        }
        return a
    }

    private fun row(c: Cursor): JSONObject {
        val n = JSONObject()
        for (col in arrayOf("id", "title", "body", "watch_title", "watch_fingerprint", "watch_state")) {
            val i = c.getColumnIndexOrThrow(col)
            n.put(col, if (c.isNull(i)) JSONObject.NULL else c.getString(i))
        }
        for (col in arrayOf("done", "watch_enabled")) {
            n.put(col, c.getInt(c.getColumnIndexOrThrow(col)) != 0)
        }
        n.put("updated_at", c.getLong(c.getColumnIndexOrThrow("updated_at")))
        val i = c.getColumnIndexOrThrow("watch_id")
        n.put("watch_id", if (c.isNull(i)) JSONObject.NULL else c.getLong(i))
        return n
    }

    @Synchronized
    fun get(id: String): JSONObject? =
        readableDatabase.rawQuery("SELECT * FROM notes WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) row(c) else null
        }

    @Synchronized
    fun save(id: String?, input: JSONObject): JSONObject? {
        val old = if (id == null) null else get(id)
        if (id != null && old == null) throw IllegalArgumentException("Note not found")
        if (old != null && input.has("if_updated_at") && input.getLong("if_updated_at") != old.getLong("updated_at")) {
            throw IllegalArgumentException("This note changed on another device. Reopen it before saving.")
        }
        val noteId = id ?: UUID.randomUUID().toString()
        val title = javaTrim(input.optString("title", old?.getString("title") ?: ""))
        val body = input.optString("body", old?.getString("body") ?: "")
        if (title.isEmpty() || title.length > 200) throw IllegalArgumentException("Use a title between 1 and 200 characters")
        if (body.length > 20000) throw IllegalArgumentException("Note body exceeds 20000 characters")
        val shortTitle = javaTrim(input.optString("watch_title", if (old == null) shorten(title, 20) else old.getString("watch_title")))
        if (shortTitle.codePointCount(0, shortTitle.length) > 20) throw IllegalArgumentException("Watch title exceeds 20 characters")
        val v = ContentValues()
        v.put("id", noteId)
        v.put("title", title)
        v.put("body", body)
        v.put("watch_title", shortTitle)
        v.put("done", if (input.optBoolean("done", old != null && old.getBoolean("done"))) 1 else 0)
        v.put("watch_enabled", if (input.optBoolean("watch_enabled", old != null && old.getBoolean("watch_enabled"))) 1 else 0)
        v.put("updated_at", maxOf(System.currentTimeMillis(), if (old == null) 0L else old.getLong("updated_at") + 1))
        if (old == null) {
            writableDatabase.insertOrThrow("notes", null, v)
        } else {
            writableDatabase.update("notes", v, "id=?", arrayOf(noteId))
        }
        return get(noteId)
    }

    @Synchronized
    fun setWatch(id: String, rid: Long?, fingerprint: String?, state: String) {
        val v = ContentValues()
        if (rid == null) v.putNull("watch_id") else v.put("watch_id", rid)
        if (fingerprint == null) v.putNull("watch_fingerprint") else v.put("watch_fingerprint", fingerprint)
        v.put("watch_state", state)
        writableDatabase.update("notes", v, "id=?", arrayOf(id))
    }

    @Synchronized
    fun markDone(id: String) {
        val v = ContentValues()
        v.put("done", 1)
        v.putNull("watch_id")
        v.putNull("watch_fingerprint")
        v.put("watch_state", "completed_on_watch")
        v.put("updated_at", System.currentTimeMillis())
        writableDatabase.update("notes", v, "id=?", arrayOf(id))
    }

    @Synchronized
    fun delete(id: String) {
        writableDatabase.delete("notes", "id=?", arrayOf(id))
    }

    @Synchronized
    fun meta(key: String, value: String) {
        val v = ContentValues()
        v.put("key", key)
        v.put("value", value)
        writableDatabase.insertWithOnConflict("metadata", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    @Synchronized
    fun removeMeta(key: String) {
        writableDatabase.delete("metadata", "key=?", arrayOf(key))
    }

    /** A consistent copy for a backup: every write goes through this lock, so none runs meanwhile. */
    @Synchronized
    fun snapshot(to: File) = PhoneBackup.copyDatabase(writableDatabase, to)

    companion object {
        const val VERSION = 1

        fun shorten(s: String, count: Int): String {
            val n = s.codePointCount(0, s.length)
            return if (n <= count) s else s.substring(0, s.offsetByCodePoints(0, count))
        }

        /** java.lang.String.trim(): strips chars <= U+0020, unlike Kotlin's Unicode-whitespace trim(). */
        fun javaTrim(s: String): String = s.trim { it <= ' ' }
    }
}
