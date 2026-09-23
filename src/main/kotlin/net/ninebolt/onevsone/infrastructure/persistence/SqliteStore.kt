package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceFailure
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.logging.Logger

/**
 * The plugin's single SQLite database (data.db). One file per plugin is the
 * ordinary deployment shape: bounded contexts are separated by tables, not
 * files, so cross-table statements and foreign-key cascades stay inside one
 * transactional unit. This class is JDBC plumbing only — schema lives in
 * SqliteMigrations and payload codecs next to their repositories.
 *
 * Transaction boundary = one public repository method = one `atomic` call.
 * Nested `atomic` blocks join the ambient transaction (depth counter); an
 * exception raised by an inner block marks the whole unit rollback-only, so
 * the outermost commit always rolls back — never catch inside an atomic
 * block and keep going.
 *
 * Every exception crossing the store boundary exits as PersistenceFailure:
 * SQL errors, codec failures, and stored-data validation errors alike, so
 * callers degrading on PersistenceFailure cannot be bypassed.
 *
 * WAL mode leaves recent commits in data.db-wal/-shm; back up all three files
 * or copy after a clean shutdown (or after wal_checkpoint).
 */
class SqliteStore(folder: File, private val logger: Logger) : AutoCloseable {

    private val connection: Connection
    private var txDepth = 0
    private var txRollbackOnly = false

    init {
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (e: ClassNotFoundException) {
            throw PersistenceFailure("sqlite-jdbc driver not found (Paper bundles it); SQLite persistence unavailable", e)
        }
        folder.mkdirs()
        val file = File(folder, "data.db")
        connection = try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        } catch (e: SQLException) {
            throw PersistenceFailure("Could not open database ${file.path}", e)
        }
        try {
            logger.info("SQLite ${connection.metaData.driverVersion} at ${file.name}")
            val migrations = SqliteMigrations(connection)
            // A newer file must be rejected before pragmas touch it (even
            // journal_mode=WAL would rewrite the header of an unknown schema).
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
            // Readers are not blocked by writers in WAL, and the only plausible
            // contender is an external sqlite CLI, so keep the busy window short
            // rather than freezing the main thread for seconds.
            st.execute("PRAGMA busy_timeout=500")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.executeQuery("PRAGMA journal_mode=WAL").use { it.next() }
        }
    }

    /**
     * Runs block inside one transaction. The outermost call commits or rolls
     * back; nested calls join the ambient transaction, and any exception they
     * raise marks it rollback-only so the outer commit still discards
     * everything.
     */
    internal fun <T> atomic(block: () -> T): T {
        if (txDepth > 0) {
            txDepth++
            try {
                return block()
            } catch (e: Throwable) {
                txRollbackOnly = true
                throw asFailure(e)
            } finally {
                txDepth--
            }
        }
        txDepth = 1
        txRollbackOnly = false
        connection.autoCommit = false
        try {
            val result = block()
            if (txRollbackOnly) {
                // An inner block failed and was swallowed by the caller; the
                // unit still rolls back, and callers must not see a silent
                // rollback as a successful commit.
                rollbackQuietly()
                throw PersistenceFailure("Transaction rolled back: an inner operation failed")
            }
            connection.commit()
            return result
        } catch (e: Throwable) {
            rollbackQuietly()
            throw asFailure(e)
        } finally {
            txDepth = 0
            txRollbackOnly = false
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
                    val rows = ArrayList<T>()
                    while (rs.next()) rows += map(rs)
                    return rows
                }
            }
        } catch (e: Throwable) {
            throw asFailure(e)
        }
    }

    internal fun <T> queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
        query(sql, *params, map = map).firstOrNull()

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

    /** Repository implementations never leak non-PersistenceFailure exceptions. */
    private fun asFailure(e: Throwable): Throwable =
        if (e is Exception) e as? PersistenceFailure ?: PersistenceFailure("SQLite operation failed", e) else e

    override fun close() {
        try {
            // Fold the WAL back into the main file so the database directory is self-contained
            connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        } catch (e: SQLException) {
            logger.warning("WAL checkpoint failed on close: ${e.message}")
        }
        connection.close()
    }
}
