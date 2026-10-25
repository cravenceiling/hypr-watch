package local.hyprwatch

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/** [HealthSql] over sqlite-jdbc, so host tests run HealthTables' SQL on real SQLite. */
class JdbcSql(val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")) : HealthSql {
    override fun exec(sql: String, vararg args: Any?) {
        update(sql, *args)
    }

    override fun update(sql: String, vararg args: Any?): Int =
        conn.prepareStatement(sql).use { s ->
            bind(s, args)
            s.executeUpdate()
        }

    override fun <T> insertAll(sql: String, rows: List<T>, values: (T) -> Array<Any?>) {
        conn.prepareStatement(sql).use { s ->
            for (r in rows) {
                s.clearParameters()
                bind(s, values(r))
                s.executeUpdate()
            }
        }
    }

    // Text arguments, as Android's rawQuery binds them.
    override fun <T> query(sql: String, args: List<String>, read: (HealthSql.Row) -> T): T =
        conn.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setString(i + 1, a) }
            s.executeQuery().use { r ->
                read(
                    object : HealthSql.Row {
                        override fun next() = r.next()

                        override fun isNull(i: Int) = r.getObject(i + 1) == null

                        override fun long(i: Int) = r.getLong(i + 1)

                        override fun string(i: Int): String = r.getString(i + 1)

                        override fun blob(i: Int): ByteArray = r.getBytes(i + 1)
                    },
                )
            }
        }

    override fun <T> transaction(block: () -> T): T {
        if (!conn.autoCommit) return block()
        conn.autoCommit = false
        try {
            return block().also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Each row of [sql] as its columns joined by '|', null as "null". */
    fun rows(sql: String): List<String> =
        conn.createStatement().use { s ->
            s.executeQuery(sql).use { r ->
                val n = r.metaData.columnCount
                val out = ArrayList<String>()
                while (r.next()) out.add((1..n).joinToString("|") { r.getString(it) ?: "null" })
                out
            }
        }

    private fun bind(s: PreparedStatement, args: Array<out Any?>) {
        for ((k, v) in args.withIndex()) {
            when (v) {
                null -> s.setObject(k + 1, null)
                is Long -> s.setLong(k + 1, v)
                is Int -> s.setLong(k + 1, v.toLong())
                is String -> s.setString(k + 1, v)
                is ByteArray -> s.setBytes(k + 1, v)
                else -> throw IllegalArgumentException("Unsupported SQL argument ${v.javaClass.simpleName}")
            }
        }
    }
}
