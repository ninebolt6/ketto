package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceException
import java.sql.Connection
import java.sql.SQLException

// add a migration by bumping LATEST_VERSION and dropping a db/migration/V{n}.sql resource
// migration DDL must be idempotent: an interrupted file is retried on next open
internal class SqliteMigrations(private val connection: Connection) {

    fun checkSupported() {
        val version = userVersion()
        if (version > LATEST_VERSION) {
            throw PersistenceException(
                "data.db has schema version $version, newer than supported $LATEST_VERSION; not modifying it",
            )
        }
    }

    fun migrate() {
        var version = userVersion()
        while (version < LATEST_VERSION) {
            val target = version + 1
            applyStep(loadStatements(target))
            connection.createStatement().use { it.execute("PRAGMA user_version=$target") }
            version = target
        }
    }

    // migration SQL: '--' lines are stripped and statements are split on ';'
    private fun loadStatements(target: Int): List<String> {
        val text = SqliteMigrations::class.java.getResource("/db/migration/V$target.sql")?.readText()
            ?: throw PersistenceException("Migration resource db/migration/V$target.sql is missing")
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
                e as? PersistenceException ?: PersistenceException("Schema migration failed", e)
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

    private fun userVersion(): Int = connection.createStatement().use { st ->
        st.executeQuery("PRAGMA user_version").use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

    private companion object {
        const val LATEST_VERSION = 1
    }
}
