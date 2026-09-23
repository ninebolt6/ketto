package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
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
 * transactional unit.
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
            // A newer file must be rejected before pragmas touch it (even
            // journal_mode=WAL would rewrite the header of an unknown schema).
            checkVersion()
            applyPragmas()
            migrate()
        } catch (e: Throwable) {
            runCatching { connection.close() }
            throw e
        }
    }

    private fun checkVersion() {
        val version = userVersion()
        if (version > SCHEMA_VERSION) {
            throw PersistenceFailure("data.db has schema version $version, newer than supported $SCHEMA_VERSION; not modifying it")
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

    /** user_version guards schema migrations; a newer file is never rewritten. */
    private fun migrate() {
        if (userVersion() == SCHEMA_VERSION) return
        connection.createStatement().use { st ->
            SCHEMA.forEach(st::execute)
            st.execute("PRAGMA user_version=$SCHEMA_VERSION")
        }
    }

    private fun userVersion(): Int =
        connection.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs ->
                rs.next()
                rs.getInt(1)
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

    internal fun warn(message: String) = logger.warning(message)

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

    // ---- snapshot codec ------------------------------------------------

    /** Inventory payload as a YAML string; the column stores ItemStacks without exposing them. */
    internal fun encodeSnapshot(snapshot: PaperInventorySnapshot): String {
        val yaml = YamlConfiguration()
        yaml.set("armor", snapshot.armor)
        yaml.set("item", snapshot.items)
        return yaml.saveToString()
    }

    internal fun decodeSnapshot(payload: String): PaperInventorySnapshot {
        val yaml = YamlConfiguration()
        yaml.loadFromString(payload)
        val armor = (yaml.getList("armor") ?: emptyList()).map { it as? ItemStack }
        val items = (yaml.getList("item") ?: emptyList()).map { it as? ItemStack }
        return PaperInventorySnapshot(armor, items)
    }

    override fun close() {
        try {
            // Fold the WAL back into the main file so the database directory is self-contained
            connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        } catch (e: SQLException) {
            logger.warning("WAL checkpoint failed on close: ${e.message}")
        }
        connection.close()
    }

    private companion object {
        const val SCHEMA_VERSION = 1

        /**
         * Table layout by bounded context. match_status is a lifecycle-dependent
         * projection of arenas (CASCADE); registrations deliberately has no FK
         * because membership is removed by the abort/leave flow before arena
         * deletion, and it must not silently re-point at a recreated arena.
         */
        val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS arenas(
              name TEXT PRIMARY KEY COLLATE NOCASE,
              enabled INTEGER NOT NULL DEFAULT 0,
              spawn1_world TEXT, spawn1_x REAL, spawn1_y REAL, spawn1_z REAL, spawn1_yaw REAL, spawn1_pitch REAL,
              spawn2_world TEXT, spawn2_x REAL, spawn2_y REAL, spawn2_z REAL, spawn2_yaw REAL, spawn2_pitch REAL,
              seq INTEGER NOT NULL UNIQUE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS arena_kits(
              arena_name TEXT PRIMARY KEY REFERENCES arenas(name) ON DELETE CASCADE,
              payload TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS arena_signs(
              arena_name TEXT PRIMARY KEY REFERENCES arenas(name) ON DELETE CASCADE,
              world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS arena_signs_pos ON arena_signs(world, x, y, z)",
            """
            CREATE TABLE IF NOT EXISTS match_status(
              arena_name TEXT PRIMARY KEY REFERENCES arenas(name) ON DELETE CASCADE,
              state TEXT NOT NULL,
              players TEXT NOT NULL DEFAULT '',
              wins TEXT NOT NULL DEFAULT ''
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS lobby(
              id INTEGER PRIMARY KEY CHECK (id = 1),
              world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
              yaw REAL NOT NULL, pitch REAL NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS registrations(
              player_uuid TEXT PRIMARY KEY,
              player_name TEXT NOT NULL,
              arena_name TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS backups(
              backup_id TEXT PRIMARY KEY,
              match_id TEXT NOT NULL,
              player_uuid TEXT,
              player_name TEXT NOT NULL,
              payload TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS player_stats(
              player_uuid TEXT PRIMARY KEY,
              wins INTEGER NOT NULL DEFAULT 0,
              losses INTEGER NOT NULL DEFAULT 0
            )
            """
        )
    }
}
