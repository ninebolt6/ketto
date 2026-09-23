package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceFailure
import java.sql.Connection
import java.sql.SQLException

/**
 * Schema ownership for data.db. Migrations are ordered (target version ->
 * statements) steps applied only when the file's user_version is behind;
 * each step commits atomically and bumps user_version afterwards so an
 * interrupted step is retried on next open (all DDL stays idempotent).
 * A file newer than this build is never modified — not even pragmas.
 */
internal class SqliteMigrations(private val connection: Connection) {

    /** Fail fast on a file newer than this build knows how to read. */
    fun checkSupported() {
        val version = userVersion()
        if (version > LATEST_VERSION) {
            throw PersistenceFailure(
                "data.db has schema version $version, newer than supported $LATEST_VERSION; not modifying it"
            )
        }
    }

    /** Applies pending migration steps in ascending version order. */
    fun migrate() {
        var version = userVersion()
        MIGRATIONS.forEach { (target, statements) ->
            if (version < target) {
                applyStep(statements)
                connection.createStatement().use { it.execute("PRAGMA user_version=$target") }
                version = target
            }
        }
    }

    private fun applyStep(statements: List<String>) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { st -> statements.forEach(st::execute) }
            connection.commit()
        } catch (e: Throwable) {
            try {
                connection.rollback()
            } catch (_: SQLException) {
            }
            throw if (e is Exception) {
                e as? PersistenceFailure ?: PersistenceFailure("Schema migration failed", e)
            } else {
                e
            }
        } finally {
            try {
                connection.autoCommit = true
            } catch (_: SQLException) {
            }
        }
    }

    private fun userVersion(): Int =
        connection.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private companion object {
        const val LATEST_VERSION = 1

        /**
         * Table layout by bounded context. match_status is a lifecycle-dependent
         * projection of arenas (CASCADE); registrations deliberately has no FK
         * because membership is removed by the abort/leave flow before arena
         * deletion, and it must not silently re-point at a recreated arena.
         */
        val MIGRATIONS = listOf(
            1 to listOf(
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
        )
    }
}
