package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceFailure
import java.sql.Connection
import java.sql.SQLException

/**
 * Schema ownership for data.db. Adding a migration means bumping
 * LATEST_VERSION and dropping a db/migration/V{n}.sql resource; the
 * contiguous version probe makes the file name the registration, so a
 * missing or skipped file fails loudly instead of drifting silently.
 * Each file commits atomically and bumps user_version afterwards, so an
 * interrupted file is retried on next open (all DDL stays idempotent).
 * A database newer than this build is never modified — not even pragmas.
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

    /** Applies pending migration files V{version+1}..V{LATEST_VERSION} in order. */
    fun migrate() {
        var version = userVersion()
        while (version < LATEST_VERSION) {
            val target = version + 1
            applyStep(loadStatements(target))
            connection.createStatement().use { it.execute("PRAGMA user_version=$target") }
            version = target
        }
    }

    /** '--' comment lines are stripped; statements split on ';'. */
    private fun loadStatements(target: Int): List<String> {
        val text = SqliteMigrations::class.java.getResource("/db/migration/V$target.sql")?.readText()
            ?: throw PersistenceFailure("Migration resource db/migration/V$target.sql is missing")
        return text
            .lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
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
    }
}
