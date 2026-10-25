package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.TreeSet

/** The few SQLite calls health storage makes, so the same SQL runs on Android and, in host tests, over JDBC. */
interface HealthSql {
    /** Arguments are Long, Int, String, ByteArray or null. */
    fun exec(sql: String, vararg args: Any?)

    /** Rows changed. */
    fun update(sql: String, vararg args: Any?): Int

    /** Runs [sql] once per row, binding [values] of it. */
    fun <T> insertAll(sql: String, rows: List<T>, values: (T) -> Array<Any?>)

    /** Query arguments are bound as text; column affinity converts them where a column is numeric. */
    fun <T> query(sql: String, args: List<String>, read: (Row) -> T): T

    fun <T> transaction(block: () -> T): T

    /** A cursor before its first row; columns count from 0. */
    interface Row {
        fun next(): Boolean

        fun isNull(i: Int): Boolean

        fun long(i: Int): Long

        fun string(i: Int): String

        fun blob(i: Int): ByteArray

        fun int(i: Int): Int? = if (isNull(i)) null else long(i).toInt()
    }
}

/**
 * Raw health files, derived rows and settings in `health.db`, apart from Android so host tests run
 * this SQL on real SQLite. A file id can have several versions, because today's files grow under
 * the same id; a version is its exact bytes. Its key (column `crc`) is the CRC-32 HealthCapture
 * names, plus n * 2^32 for the n-th other version with that CRC-32 (a damaged file's CRC-32 can
 * repeat). `complete` marks a version fetched from the past list (see [HealthCapture]). Callers
 * serialize access (see HealthStore).
 */
class HealthTables(private val sql: HealthSql) : HealthIngest.Store, HealthConnectExport.Source {
    /**
     * On each open. A stored file is acked and the watch then deletes it, so a commit must reach the
     * disk before it returns: in WAL mode NORMAL can lose the last commits on a power cut, FULL cannot.
     */
    fun configure() = sql.exec("PRAGMA synchronous=FULL")

    fun create() {
        createFiles("health_files")
        sql.exec("CREATE TABLE settings (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        createDerived()
        sql.exec(FILES_INDEX)
    }

    fun upgrade(oldVersion: Int) {
        when (oldVersion) {
            1 -> {
                // Version 1 kept one copy per id. Its CRC-32 is the file's last 4 bytes.
                createFiles("health_files_v2")
                val ids = sql.query("SELECT id_hex,substr(raw,size-3,4) FROM health_files", emptyList()) { c ->
                    val out = ArrayList<Pair<String, Long>>()
                    while (c.next()) out.add(c.string(0) to HealthCapture.u32le(c.blob(1), 0))
                    out
                }
                for ((id, crc) in ids) {
                    sql.exec(
                        "INSERT INTO health_files_v2(id_hex,crc,size,raw,first_seen,last_seen) " +
                            "SELECT id_hex,?,size,raw,first_seen,last_seen FROM health_files WHERE id_hex=?",
                        crc, id,
                    )
                }
                sql.exec("DROP TABLE health_files")
                sql.exec("ALTER TABLE health_files_v2 RENAME TO health_files")
                // Version 1 stored a made-up profile here; only a profile the user enters is sent now.
                sql.exec("DELETE FROM settings WHERE key='user_info'")
            }
            // Whether older versions were complete is unknown, so each past file is fetched once more.
            2 -> sql.exec("ALTER TABLE health_files ADD COLUMN complete INTEGER NOT NULL DEFAULT 0")
            3 -> {}
            // Version 4 kept one row per key, so overlapping files depended on ingest order; ingest rebuilds them.
            4 -> for (t in DERIVED + "health_ingest") sql.exec("DROP TABLE $t")
            // Marks without a version count make every id pending once.
            5 -> sql.exec("ALTER TABLE health_ingest ADD COLUMN versions INTEGER NOT NULL DEFAULT 0")
            6 -> {}
            else -> throw IllegalStateException("Unsupported database upgrade")
        }
        if (oldVersion < 5) createDerived()
        // Marks from before ingest numbers count as ingested before every export.
        if (oldVersion in 5..6) sql.exec("ALTER TABLE health_ingest ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
        sql.exec(FILES_INDEX)
    }

    // Rows parsed from raw files, every file's own, tagged with its HealthFileId.rank. Reads show per
    // key the row from the highest-ranked file (latest()), and for sleep the rows marked win=1 by
    // HealthIngest.sleepWinners, so the tables depend only on which rows each file id has. Ingest can
    // rebuild every table from health_files.
    private fun createDerived() {
        sql.exec(
            "CREATE TABLE health_minutes (time INTEGER NOT NULL,file_rank INTEGER NOT NULL,date TEXT NOT NULL,tz_offset INTEGER NOT NULL,steps INTEGER," +
                "active_calories INTEGER,distance_m INTEGER,heart_rate INTEGER,spo2 INTEGER,stress INTEGER,PRIMARY KEY(time,file_rank))",
        )
        sql.exec(
            "CREATE TABLE health_daily (date TEXT NOT NULL,file_rank INTEGER NOT NULL,time INTEGER NOT NULL,tz_offset INTEGER NOT NULL,steps INTEGER," +
                "active_calories INTEGER,calories INTEGER,resting_hr INTEGER,avg_hr INTEGER,min_hr INTEGER,max_hr INTEGER,avg_spo2 INTEGER,min_spo2 INTEGER," +
                "avg_stress INTEGER,max_stress INTEGER,standing_hours INTEGER,PRIMARY KEY(date,file_rank))",
        )
        sql.exec(
            "CREATE TABLE health_sleep (bed_time INTEGER NOT NULL,file_rank INTEGER NOT NULL,wakeup_time INTEGER NOT NULL,date TEXT NOT NULL," +
                "tz_offset INTEGER NOT NULL,finished INTEGER NOT NULL,quality INTEGER,total_minutes INTEGER,deep_minutes INTEGER,light_minutes INTEGER," +
                "rem_minutes INTEGER,awake_minutes INTEGER,stages TEXT NOT NULL,source INTEGER NOT NULL,win INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(bed_time,file_rank))",
        )
        sql.exec(
            "CREATE TABLE health_manual (time INTEGER NOT NULL,kind TEXT NOT NULL,file_rank INTEGER NOT NULL,date TEXT NOT NULL,tz_offset INTEGER NOT NULL," +
                "value INTEGER NOT NULL,PRIMARY KEY(time,kind,file_rank))",
        )
        sql.exec(
            "CREATE TABLE health_workouts (start INTEGER NOT NULL,file_rank INTEGER NOT NULL,end_time INTEGER NOT NULL,date TEXT NOT NULL," +
                "tz_offset INTEGER NOT NULL,sport TEXT NOT NULL,active_seconds INTEGER,calories INTEGER,avg_hr INTEGER,max_hr INTEGER,min_hr INTEGER," +
                "steps INTEGER,distance_m INTEGER,PRIMARY KEY(start,file_rank))",
        )
        for (t in DERIVED) {
            sql.exec("CREATE INDEX ${t}_file ON $t(file_rank)")
            if (t != "health_daily") sql.exec("CREATE INDEX ${t}_date ON $t(date)")
        }
        // versions: how many versions the id had when ingested; a new one makes the id pending again.
        // seq: the ingest's number (see ingestSeq).
        sql.exec(
            "CREATE TABLE health_ingest (id_hex TEXT PRIMARY KEY,crc INTEGER NOT NULL,parser_version INTEGER NOT NULL,status TEXT NOT NULL," +
                "reason TEXT,ingested_at INTEGER NOT NULL,versions INTEGER NOT NULL DEFAULT 0,seq INTEGER NOT NULL DEFAULT 0)",
        )
    }

    private fun createFiles(table: String) {
        sql.exec(
            "CREATE TABLE $table (id_hex TEXT NOT NULL,crc INTEGER NOT NULL,size INTEGER NOT NULL,raw BLOB NOT NULL," +
                "first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL,complete INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(id_hex,crc))",
        )
    }

    /** HealthCapture.Files.seen. */
    fun seen(ids: List<ByteArray>, at: Long): Set<String> =
        sql.transaction {
            val found = HashSet<String>()
            for (id in ids) {
                val hex = HealthCapture.hex(id)
                val n = sql.update(
                    "UPDATE health_files SET last_seen=? WHERE rowid=(SELECT rowid FROM health_files WHERE id_hex=? AND complete=1 ORDER BY first_seen DESC LIMIT 1)",
                    at, hex,
                )
                if (n > 0) found.add(hex)
            }
            found
        }

    /**
     * HealthCapture.Files.save: true when [data] is a new version. The same bytes again only update
     * the stored version; other bytes with the CRC-32 [crc] get the next free key. Either way [data]
     * is stored, byte for byte, when this returns.
     */
    fun save(id: ByteArray, crc: Long, data: ByteArray, complete: Boolean, at: Long): Boolean {
        val hex = HealthCapture.hex(id)
        val flag = if (complete) 1L else 0L
        // SQLite compares BLOBs byte for byte; size first, so a version of another size is not read.
        val same = "WHERE id_hex=? AND (crc & $CRC_MASK)=? AND size=? AND raw=?"
        if (sql.update("UPDATE health_files SET last_seen=?,complete=max(complete,?) $same", at, flag, hex, crc, data.size, data) > 0) return false
        val slot = sql.query("SELECT coalesce(max(crc >> 32) + 1,0) FROM health_files WHERE id_hex=? AND (crc & $CRC_MASK)=$crc", listOf(hex)) { c ->
            c.next()
            c.long(0)
        }
        sql.exec("INSERT INTO health_files(id_hex,crc,size,raw,first_seen,last_seen,complete) VALUES (?,?,?,?,?,?,?)", hex, crc + (slot shl 32), data.size, data, at, at, flag)
        return true
    }

    /** Files with their versions, oldest first. */
    fun list(): JSONArray {
        val files = LinkedHashMap<String, JSONArray>()
        sql.query("SELECT id_hex,crc,size,first_seen,last_seen,complete FROM health_files ORDER BY first_seen,id_hex", emptyList()) { c ->
            while (c.next()) {
                files.getOrPut(c.string(0)) { JSONArray() }.put(
                    JSONObject().put("crc32", crcHex(c.long(1))).put("size", c.long(2)).put("first_seen", c.long(3)).put("last_seen", c.long(4))
                        .put("complete", c.long(5) != 0L),
                )
            }
        }
        val a = JSONArray()
        for ((id, versions) in files) {
            a.put(JSONObject().put("id", id).put("versions", versions))
        }
        return a
    }

    /** Distinct file ids. */
    fun count(): Long = sql.query("SELECT COUNT(DISTINCT id_hex) FROM health_files", emptyList()) { c -> if (c.next()) c.long(0) else 0L }

    /** One version with its raw bytes as base64, the newest when [crc] is null; null if absent. */
    fun file(idHex: String, crc: Long?): JSONObject? {
        val filter = if (crc == null) listOf(idHex) else listOf(idHex, crc.toString())
        var version = 0L
        val j = sql.query(
            "SELECT crc,size,first_seen,last_seen,complete FROM health_files WHERE id_hex=?" + (if (crc == null) "" else " AND crc=?") + " ORDER BY first_seen DESC LIMIT 1",
            filter,
        ) { c ->
            if (!c.next()) {
                null
            } else {
                version = c.long(0)
                JSONObject().put("id", idHex).put("crc32", crcHex(version)).put("size", c.long(1)).put("first_seen", c.long(2)).put("last_seen", c.long(3))
                    .put("complete", c.long(4) != 0L)
            }
        } ?: return null
        val raw = slices(idHex, version, j.getLong("size")) ?: return null
        return j.put("raw_base64", Base64.getEncoder().encodeToString(raw))
    }

    // Read in slices: an Android cursor window holds about 2 MB and files may be up to 4 MB.
    private fun slices(idHex: String, crc: Long, size: Long): ByteArray? {
        val key = listOf(idHex, crc.toString())
        val out = ByteArrayOutputStream()
        var offset = 0L
        while (offset < size) {
            val slice = sql.query("SELECT substr(raw,${offset + 1},$SLICE) FROM health_files WHERE id_hex=? AND crc=?", key) { c ->
                if (c.next()) c.blob(0) else null
            } ?: return null
            out.write(slice)
            offset += SLICE
        }
        return out.toByteArray()
    }

    override fun setting(key: String): String? = sql.query("SELECT value FROM settings WHERE key=?", listOf(key)) { c -> if (c.next()) c.string(0) else null }

    override fun setting(key: String, value: String) = sql.exec("INSERT OR REPLACE INTO settings(key,value) VALUES (?,?)", key, value)

    // ---- ingest (HealthIngest.Store) ----

    // Reads only health_files_versions, never the rows, whose raw BLOB puts these columns on overflow pages.
    override fun versions(): List<FileVersion> =
        sql.query("SELECT id_hex,crc,first_seen,rowid FROM health_files INDEXED BY health_files_versions", emptyList()) { c ->
            val out = ArrayList<FileVersion>()
            while (c.next()) out.add(FileVersion(c.string(0), c.long(1), c.long(2), c.long(3)))
            out
        }

    override fun ingested(): Map<String, IngestMark> =
        sql.query("SELECT id_hex,crc,parser_version,versions FROM health_ingest", emptyList()) { c ->
            val out = HashMap<String, IngestMark>()
            while (c.next()) out[c.string(0)] = IngestMark(c.long(1), c.long(2).toInt(), c.long(3).toInt())
            out
        }

    override fun raw(idHex: String, crc: Long): ByteArray? {
        val size = sql.query("SELECT size FROM health_files WHERE id_hex=? AND crc=?", listOf(idHex, crc.toString())) { c -> if (c.next()) c.long(0) else null }
            ?: return null
        return slices(idHex, crc, size)
    }

    override fun forgetIngested() = sql.exec("DELETE FROM health_ingest")

    override fun replace(idHex: String, mark: IngestMark, rows: HealthRows, outcome: IngestOutcome, at: Long) {
        val rank = requireNotNull(HealthFileId.fromHex(idHex)) { "Invalid file id" }.rank
        sql.transaction {
            val hadSleep = sql.query("SELECT 1 FROM health_sleep WHERE file_rank=? LIMIT 1", listOf(rank.toString())) { c -> c.next() }
            for (t in DERIVED) sql.exec("DELETE FROM $t WHERE file_rank=?", rank)
            insert("health_minutes(time,file_rank,date,tz_offset,steps,active_calories,distance_m,heart_rate,spo2,stress)", rows.minutes) { m ->
                arrayOf(m.time, rank, m.date, m.tzOffset, m.steps, m.activeCalories, m.distanceM, m.heartRate, m.spo2, m.stress)
            }
            insert(
                "health_daily(date,file_rank,time,tz_offset,steps,active_calories,calories,resting_hr,avg_hr,min_hr,max_hr,avg_spo2,min_spo2,avg_stress," +
                    "max_stress,standing_hours)",
                rows.daily,
            ) { d ->
                arrayOf(
                    d.date, rank, d.time, d.tzOffset, d.steps, d.activeCalories, d.calories, d.restingHr, d.avgHr, d.minHr, d.maxHr, d.avgSpo2, d.minSpo2,
                    d.avgStress, d.maxStress, d.standingHours,
                )
            }
            insert(
                "health_sleep(bed_time,file_rank,wakeup_time,date,tz_offset,finished,quality,total_minutes,deep_minutes,light_minutes,rem_minutes," +
                    "awake_minutes,stages,source)",
                rows.sleep,
            ) { r ->
                val stages = JSONArray()
                for (st in r.stages) stages.put(JSONArray().put(st.start).put(st.kind).put(st.minutes))
                arrayOf(
                    r.bedTime, rank, r.wakeupTime, r.date, r.tzOffset, if (r.finished) 1 else 0, r.quality, r.totalMinutes, r.deepMinutes, r.lightMinutes,
                    r.remMinutes, r.awakeMinutes, stages.toString(), r.source,
                )
            }
            insert("health_manual(time,kind,file_rank,date,tz_offset,value)", rows.manual) { m -> arrayOf(m.time, m.kind, rank, m.date, m.tzOffset, m.value) }
            insert(
                "health_workouts(start,file_rank,end_time,date,tz_offset,sport,active_seconds,calories,avg_hr,max_hr,min_hr,steps,distance_m)",
                rows.workouts,
            ) { w ->
                arrayOf(w.start, rank, w.end, w.date, w.tzOffset, w.sport, w.activeSeconds, w.calories, w.avgHr, w.maxHr, w.minHr, w.steps, w.distanceM)
            }
            if (hadSleep || rows.sleep.isNotEmpty()) markSleepWinners()
            val seq = ingestSeq() + 1
            setting(INGEST_SEQ, seq.toString())
            sql.exec(
                "INSERT OR REPLACE INTO health_ingest(id_hex,crc,parser_version,status,reason,ingested_at,versions,seq) VALUES (?,?,?,?,?,?,?,?)",
                idHex, mark.crc, mark.parserVersion, outcome.status, outcome.reason, at, mark.versions, seq,
            )
        }
    }

    /** Sets win=1 on exactly the rows [HealthIngest.sleepWinners] picks from all sleep rows, writing only rows that change. */
    private fun markSleepWinners() {
        val rows = sql.query("SELECT file_rank,bed_time,wakeup_time,finished,source,win FROM health_sleep", emptyList()) { c ->
            val out = ArrayList<Pair<SleepCandidate, Boolean>>()
            while (c.next()) out.add(SleepCandidate(c.long(0), c.long(1), c.long(2), c.long(3) != 0L, c.long(4).toInt()) to (c.long(5) != 0L))
            out
        }
        val winners = HealthIngest.sleepWinners(rows.map { it.first }).toHashSet()
        for ((c, won) in rows) {
            val wins = c in winners
            if (wins != won) sql.exec("UPDATE health_sleep SET win=? WHERE bed_time=? AND file_rank=?", if (wins) 1 else 0, c.bedTime, c.fileRank)
        }
    }

    private fun <T> insert(into: String, rows: List<T>, values: (T) -> Array<Any?>) {
        if (rows.isEmpty()) return
        val columns = into.substringAfter('(').count { it == ',' } + 1
        sql.insertAll("INSERT OR REPLACE INTO $into VALUES (" + List(columns) { "?" }.joinToString(",") + ")", rows, values)
    }

    // ---- queries (HealthApi.Source) ----

    override fun dailySummaries(from: String, to: String): List<DailyRow> =
        sql.query(
            "SELECT date,time,tz_offset,steps,active_calories,calories,resting_hr,avg_hr,min_hr,max_hr,avg_spo2,min_spo2,avg_stress,max_stress,standing_hours " +
                "FROM health_daily d WHERE date BETWEEN ? AND ? AND ${latest("health_daily", "d", "date")} ORDER BY date",
            listOf(from, to),
        ) { c ->
            val out = ArrayList<DailyRow>()
            while (c.next()) {
                out.add(
                    DailyRow(
                        c.string(0), c.long(1), c.long(2).toInt(), c.int(3), c.int(4), c.int(5), c.int(6), c.int(7), c.int(8), c.int(9),
                        c.int(10), c.int(11), c.int(12), c.int(13), c.int(14),
                    ),
                )
            }
            out
        }

    override fun forEachMinute(from: String, to: String, block: (MinuteRow) -> Unit) =
        sql.query(
            "SELECT time,tz_offset,steps,active_calories,distance_m,heart_rate,spo2,stress FROM health_minutes m " +
                "WHERE date BETWEEN ? AND ? AND ${latest("health_minutes", "m", "time")} ORDER BY time",
            listOf(from, to),
        ) { c ->
            while (c.next()) block(MinuteRow(c.long(0), c.long(1).toInt(), c.int(2), c.int(3), c.int(4), c.int(5), c.int(6), c.int(7)))
        }

    override fun sleepSessions(from: String, to: String, limit: Int): List<SleepRow> =
        sql.query("SELECT $SLEEP_COLUMNS FROM health_sleep WHERE win=1 AND date BETWEEN ? AND ? ORDER BY bed_time LIMIT $limit", listOf(from, to)) { c ->
            val out = ArrayList<SleepRow>()
            while (c.next()) out.add(sleepRow(c))
            out
        }

    override fun manualSamples(from: String, to: String, limit: Int): List<ManualRow> =
        sql.query(
            "SELECT time,tz_offset,kind,value FROM health_manual m WHERE date BETWEEN ? AND ? AND ${latest("health_manual", "m", "time", "kind")} " +
                "ORDER BY time,kind LIMIT $limit",
            listOf(from, to),
        ) { c ->
            val out = ArrayList<ManualRow>()
            while (c.next()) out.add(ManualRow(c.long(0), c.long(1).toInt(), c.string(2), c.long(3).toInt()))
            out
        }

    override fun workouts(from: String, to: String, limit: Int): List<WorkoutRow> =
        sql.query(
            "SELECT start,end_time,tz_offset,sport,active_seconds,calories,avg_hr,max_hr,min_hr,steps,distance_m FROM health_workouts w " +
                "WHERE date BETWEEN ? AND ? AND ${latest("health_workouts", "w", "start")} ORDER BY start LIMIT $limit",
            listOf(from, to),
        ) { c ->
            val out = ArrayList<WorkoutRow>()
            while (c.next()) {
                out.add(WorkoutRow(c.long(0), c.long(1), c.long(2).toInt(), c.string(3), c.int(4), c.int(5), c.int(6), c.int(7), c.int(8), c.int(9), c.int(10)))
            }
            out
        }

    // Kept in settings, which forgetIngested leaves alone, so the number never starts over.
    override fun ingestSeq(): Long = setting(INGEST_SEQ)?.toLong() ?: 0L

    override fun exportDates(ingestedAfter: Long?): List<String> {
        val dates = TreeSet<String>()
        if (ingestedAfter == null) {
            sql.query(DERIVED.joinToString(" UNION ") { "SELECT date FROM $it" }, emptyList()) { c -> while (c.next()) dates.add(c.string(0)) }
            return dates.toList()
        }
        val ranks = sql.query("SELECT id_hex FROM health_ingest WHERE seq>?", listOf(ingestedAfter.toString())) { c ->
            val out = ArrayList<Long>()
            while (c.next()) HealthFileId.fromHex(c.string(0))?.let { out.add(it.rank) }
            out
        }
        // Ranks are numbers, so they go into the SQL as literals.
        for (chunk in ranks.chunked(500)) {
            val list = chunk.joinToString(",")
            sql.query(DERIVED.joinToString(" UNION ") { "SELECT date FROM $it WHERE file_rank IN ($list)" }, emptyList()) { c ->
                while (c.next()) dates.add(c.string(0))
            }
        }
        return dates.toList()
    }

    override fun parseReport(): List<ReportRow> =
        sql.query("SELECT id_hex,crc,parser_version,status,reason FROM health_ingest ORDER BY id_hex", emptyList()) { c ->
            val out = ArrayList<ReportRow>()
            while (c.next()) out.add(ReportRow(c.string(0), c.long(1), c.long(2).toInt(), c.string(3), if (c.isNull(4)) null else c.string(4)))
            out
        }

    private fun sleepRow(c: HealthSql.Row): SleepRow {
        val a = JSONArray(c.string(11))
        val stages = List(a.length()) { i -> a.getJSONArray(i).let { SleepStageRow(it.getLong(0), it.getString(1), it.getInt(2)) } }
        return SleepRow(c.long(0), c.long(1), c.long(2).toInt(), c.long(3) != 0L, c.int(4), c.int(5), c.int(6), c.int(7), c.int(8), c.int(9), stages, c.long(10).toInt())
    }

    companion object {
        const val VERSION = 7
        private const val SLICE = 1024 * 1024
        private const val CRC_MASK = 0xffffffffL
        private const val INGEST_SEQ = "ingest_seq"
        private val DERIVED = listOf("health_minutes", "health_daily", "health_sleep", "health_manual", "health_workouts")
        private const val SLEEP_COLUMNS =
            "bed_time,wakeup_time,tz_offset,finished,quality,total_minutes,deep_minutes,light_minutes,rem_minutes,awake_minutes,source,stages"

        // Covers versions() and seen(), which would otherwise read past each row's raw BLOB.
        private const val FILES_INDEX = "CREATE INDEX IF NOT EXISTS health_files_versions ON health_files(id_hex,first_seen,crc,complete)"

        /** True for a row of [table] (aliased [row]) unless a higher-ranked file has a row with the same [key]. */
        private fun latest(table: String, row: String, vararg key: String): String =
            "NOT EXISTS (SELECT 1 FROM $table o WHERE " + key.joinToString("") { "o.$it=$row.$it AND " } + "o.file_rank>$row.file_rank)"

        /** A version key as hex: 8 digits for a CRC-32, more for a later version that shares one. */
        fun crcHex(crc: Long): String = "%08x".format(crc)
    }
}
