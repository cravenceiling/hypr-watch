package local.hyprwatch

import java.util.zip.CRC32

/** HealthTables-only checks on real SQLite: migrations, query plans, and how few sleep rows a pass rewrites. */
object HealthTablesTest {
    private lateinit var eq: (Any?, Any?, String) -> Unit

    fun run(eq: (Any?, Any?, String) -> Unit) {
        this.eq = eq
        migrations()
        durability()
        plans()
        sleepUpdates()
        invalidVersions()
    }

    private fun file(id: HealthFileId, body: ByteArray): ByteArray {
        val b = id.toBytes() + byteArrayOf(0) + body + ByteArray(4)
        val crc = CRC32()
        crc.update(b, 0, b.size - 4)
        for (i in 0 until 4) b[b.size - 4 + i] = (crc.value ushr (8 * i)).toByte()
        return b
    }

    private val sample = file(HealthFileId(1_767_222_000, 4, 3, HealthFileType.ACTIVITY, 0, HealthDetailType.DETAILS), byteArrayOf(1, 2, 3))
    private val sampleId = hexOf(sample.copyOf(7))
    private val sampleCrc = MemHealth.crcOf(sample)

    /** Columns of every table and indexes, so two databases with the same shape compare equal. */
    private fun shape(sql: JdbcSql): List<String> =
        sql.rows("SELECT type,name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name").map { row ->
            val (type, name) = row.split("|")
            val pragma = if (type == "table") "table_info" else "index_info"
            "$row: " + sql.rows("PRAGMA $pragma($name)").joinToString(";")
        }

    private fun migrations() {
        val fresh = JdbcSql()
        HealthTables(fresh).create()
        val expected = shape(fresh)

        // Version 5: no version counts in health_ingest, no index on health_files.
        val v5 = JdbcSql()
        val t5 = HealthTables(v5)
        t5.create()
        v5.exec("DROP INDEX health_files_versions")
        v5.exec("DROP TABLE health_ingest")
        v5.exec(
            "CREATE TABLE health_ingest (id_hex TEXT PRIMARY KEY,crc INTEGER NOT NULL,parser_version INTEGER NOT NULL,status TEXT NOT NULL," +
                "reason TEXT,ingested_at INTEGER NOT NULL)",
        )
        t5.save(sample.copyOf(7), sampleCrc, sample, true, 1_000)
        v5.exec("INSERT INTO health_ingest VALUES (?,?,?,'parsed',NULL,5)", sampleId, sampleCrc, HealthIngest.PARSER_VERSION)
        t5.upgrade(5)
        eq(shape(v5), expected, "version 5 upgrades to the current schema")
        eq(v5.rows("SELECT id_hex,crc,status,versions FROM health_ingest"), listOf("$sampleId|$sampleCrc|parsed|0"), "marks kept without a version count")
        eq(HealthIngest.pending(t5.versions(), t5.ingested(), HealthIngest.PARSER_VERSION).map { it.size }, listOf(1), "so each id is ingested once more")

        // Version 6: no ingest numbers, so marks from before count as ingested before every export.
        val v6 = JdbcSql()
        val t6 = HealthTables(v6)
        t6.create()
        v6.exec("ALTER TABLE health_ingest DROP COLUMN seq")
        v6.exec("INSERT INTO health_ingest VALUES (?,?,?,'parsed',NULL,5,1)", sampleId, sampleCrc, HealthIngest.PARSER_VERSION)
        t6.upgrade(6)
        eq(shape(v6), expected, "version 6 upgrades to the current schema")
        eq(v6.rows("SELECT id_hex,versions,seq FROM health_ingest"), listOf("$sampleId|1|0"), "marks kept, numbered 0")

        // Version 1: one copy per id, CRC-32 in the file's last 4 bytes, and a made-up profile.
        val v1 = JdbcSql()
        v1.exec(
            "CREATE TABLE health_files (id BLOB PRIMARY KEY,id_hex TEXT NOT NULL UNIQUE,size INTEGER NOT NULL,raw BLOB NOT NULL," +
                "first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL)",
        )
        v1.exec("CREATE TABLE settings (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        v1.exec("INSERT INTO health_files VALUES (?,?,?,?,7,9)", sample.copyOf(7), sampleId, sample.size, sample)
        v1.exec("INSERT INTO settings VALUES ('user_info','{}'),('unlock_sync_minutes','30')")
        val t1 = HealthTables(v1)
        t1.upgrade(1)
        eq(shape(v1), expected, "version 1 upgrades to the current schema")
        eq(v1.rows("SELECT id_hex,crc,size,first_seen,last_seen,complete FROM health_files"), listOf("$sampleId|$sampleCrc|${sample.size}|7|9|0"), "file kept with its CRC")
        eq(v1.rows("SELECT key FROM settings"), listOf("unlock_sync_minutes"), "made-up profile removed")
        eq(t1.raw(sampleId, sampleCrc)?.toList(), sample.toList(), "raw bytes intact")
    }

    /** A file is acked, and the watch deletes it, once its commit returns: that commit must survive a power cut. */
    private fun durability() {
        val dir = java.nio.file.Files.createTempDirectory("health-db")
        val sql = JdbcSql(java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("health.db")))
        // As Android opens a WAL database on many builds.
        eq(sql.rows("PRAGMA journal_mode=WAL"), listOf("wal"), "write-ahead log")
        sql.exec("PRAGMA synchronous=NORMAL")
        HealthTables(sql).configure()
        eq(sql.rows("PRAGMA synchronous"), listOf("2"), "configure syncs each commit (FULL)")
        sql.conn.close()
        dir.toFile().deleteRecursively()
    }

    /**
     * A damaged file is keyed by the CRC-32 of all its bytes, and two damaged files can share it (the
     * same bit flipped in their own CRCs). Different bytes are a second version under the next key,
     * never folded into the first, so capture never acks a file whose bytes were not stored.
     */
    private fun invalidVersions() {
        val sql = JdbcSql()
        val t = HealthTables(sql)
        t.create()
        val id = sample.copyOf(7)
        fun damaged(body: ByteArray): ByteArray = file(HealthFileId.fromHex(sampleId)!!, body).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val one = damaged(byteArrayOf(1, 2, 3))
        val two = damaged(byteArrayOf(9, 8, 7, 6, 5, 4))
        val crc = CRC32().also { it.update(one) }.value
        eq(CRC32().also { it.update(two) }.value, crc, "two damaged files share the CRC-32 of all their bytes")
        eq(HealthTables.crcHex(crc), "5643ef8a", "that CRC-32")
        eq(t.save(id, crc, one, false, 1), true, "the first is a new version")
        eq(t.save(id, crc, two, false, 2), true, "different bytes with the same CRC-32: another new version")
        eq(t.save(id, crc, two, true, 3), false, "the same bytes again are the same version")
        eq(
            sql.rows("SELECT crc,size,first_seen,last_seen,complete FROM health_files ORDER BY crc"),
            listOf("$crc|${one.size}|1|1|0", "${crc + (1L shl 32)}|${two.size}|2|3|1"),
            "the second under the CRC-32 plus 2^32, seen again and complete",
        )
        eq(t.raw(sampleId, crc)?.toList(), one.toList(), "each keeps its own bytes")
        eq(t.raw(sampleId, crc + (1L shl 32))?.toList(), two.toList(), "byte for byte")
        val versions = t.list().getJSONObject(0).getJSONArray("versions")
        eq(List(versions.length()) { versions.getJSONObject(it).getString("crc32") }, listOf("5643ef8a", "15643ef8a"), "listed by key")
    }

    /** /api/status runs versions() on every poll; it and seen() must not touch the rows behind the raw BLOBs. */
    private fun plans() {
        val sql = JdbcSql()
        HealthTables(sql).create()
        eq(
            sql.rows("EXPLAIN QUERY PLAN SELECT id_hex,crc,first_seen,rowid FROM health_files INDEXED BY health_files_versions").map { it.substringAfterLast("|") },
            listOf("SCAN health_files USING COVERING INDEX health_files_versions"),
            "versions() reads the covering index",
        )
        eq(
            sql.rows("EXPLAIN QUERY PLAN SELECT rowid FROM health_files WHERE id_hex='x' AND complete=1 ORDER BY first_seen DESC LIMIT 1").map { it.substringAfterLast("|") },
            listOf("SEARCH health_files USING COVERING INDEX health_files_versions (id_hex=?)"),
            "seen() finds the newest complete version in the index",
        )
    }

    /** A new sleep file rewrites only the rows whose win flag changes. */
    private fun sleepUpdates() {
        val store = SqlHealth()
        store.sql.exec("CREATE TEMP TABLE sleep_updates (bed_time INTEGER)")
        store.sql.exec("CREATE TEMP TRIGGER sleep_updated AFTER UPDATE ON health_sleep BEGIN INSERT INTO sleep_updates VALUES (old.bed_time); END")
        val wake = 1_767_247_200L
        fun night(bed: Long, total: Int) = SleepSession(bed, wake, 3600, true, null, null, total, null, null, null, null, emptyList(), false)
        val sleepId = HealthFileId(wake - 36_000, 4, 2, HealthFileType.ACTIVITY, 8, HealthDetailType.DETAILS)
        val stagesId = HealthFileId(wake - 36_000, 4, 2, HealthFileType.ACTIVITY, 3, HealthDetailType.DETAILS)
        val given = HashMap<String, HealthParseResult>()
        fun add(id: HealthFileId, n: Int, vararg nights: SleepSession) {
            val raw = file(id, byteArrayOf(n.toByte()))
            given[hexOf(raw)] = HealthParseResult.Parsed(id, HealthData.Sleep(nights.toList()))
            store.add(raw, n.toLong())
        }
        val ingest = HealthIngest(store) { given.getValue(hexOf(it)) }
        // Bed times of the rows each pass updated.
        fun updates(): List<String> = store.sql.rows("SELECT bed_time FROM sleep_updates ORDER BY bed_time").also { store.sql.exec("DELETE FROM sleep_updates") }
        val bedSleep = wake - 8 * 3600
        val bedStages = bedSleep + 120

        add(stagesId, 1, night(bedStages, 470))
        ingest.runNow { 1 }
        eq(updates(), listOf("$bedStages"), "the only night is marked the winner")
        add(sleepId, 2, night(bedSleep, 480))
        ingest.runNow { 2 }
        eq(updates(), listOf("$bedSleep", "$bedStages"), "SLEEP takes the night: exactly the two rows whose flag changes")
        add(sleepId, 3)
        ingest.runNow { 3 }
        eq(updates(), listOf("$bedStages"), "SLEEP drops the night: only SLEEP_STAGES' row changes back")
        add(stagesId, 4, night(bedStages, 471))
        ingest.runNow { 4 }
        eq(updates(), listOf("$bedStages"), "a replaced winner: only its new row is marked")
        ingest.shutdown()
    }
}
