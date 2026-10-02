package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceException
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.logging.Level
import java.util.logging.Logger

// every exception crossing the store boundary exits as PersistenceException so lenient callers cannot be bypassed
// WAL leaves recent commits in data.db-wal/-shm; back up all three files or copy after a clean shutdown
class SqliteStore(folder: File, private val logger: Logger) : AutoCloseable {

    private val connection: Connection
    private var txActive = false

    init {
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (e: ClassNotFoundException) {
            throw PersistenceException("sqlite-jdbc driver not found (Paper bundles it); SQLite persistence unavailable", e)
        }
        folder.mkdirs()
        val file = File(folder, "data.db")
        connection = try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        } catch (e: SQLException) {
            throw PersistenceException("Could not open database ${file.path}", e)
        }
        try {
            logger.info("SQLite ${connection.metaData.driverVersion} at ${file.name}")
            val migrations = SqliteMigrations(connection)
            // a newer file must be rejected before pragmas touch it: even journal_mode=WAL rewrites an unknown schema's header
            migrations.checkSupported()
            applyPragmas()
            migrations.migrate()
        } catch (e: Throwable) {
            runCatching { connection.close() }
            throw e
        }
    }

    private fun applyPragmas() {
        connection.createStatement().use { st ->
            // the busy window stays short: the only plausible writer contention is an external sqlite CLI
            st.execute("PRAGMA busy_timeout=500")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.executeQuery("PRAGMA journal_mode=WAL").use { it.next() }
        }
    }

    internal fun <T> atomic(block: () -> T): T {
        if (txActive) {
            throw PersistenceException("atomic blocks must not nest")
        }
        txActive = true
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            rollbackQuietly()
            throw asFailure(e)
        } finally {
            txActive = false
            try {
                connection.autoCommit = true
            } catch (_: SQLException) {
            }
        }
    }

    internal fun exec(sql: String, vararg params: Any?) {
        try {
            connection.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeUpdate()
            }
        } catch (e: Throwable) {
            throw asFailure(e)
        }
    }

    internal fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> {
        try {
            connection.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeQuery().use { rs ->
                    return buildList {
                        while (rs.next()) add(map(rs))
                    }
                }
            }
        } catch (e: Throwable) {
            throw asFailure(e)
        }
    }

    internal fun <T> queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? = query(sql, *params, map = map).firstOrNull()

    private fun bind(ps: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { i, value ->
            when (value) {
                is Boolean -> ps.setInt(i + 1, if (value) 1 else 0)
                else -> ps.setObject(i + 1, value)
            }
        }
    }

    private fun rollbackQuietly() {
        try {
            connection.rollback()
        } catch (_: SQLException) {
        }
    }

    private fun asFailure(e: Throwable): Throwable = if (e is Exception) e as? PersistenceException ?: PersistenceException("SQLite operation failed", e) else e

    override fun close() {
        try {
            // fold the WAL back so the database directory is self-contained
            connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        } catch (e: SQLException) {
            logger.log(Level.WARNING, "WAL checkpoint failed on close", e)
        }
        connection.close()
    }
}
