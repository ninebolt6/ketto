package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.infrastructure.persistence.fixtures.store
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqliteMigrationsTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `newer user_version fails fast without rewriting`() {
        store(folder).use { }
        DriverManager.getConnection("jdbc:sqlite:${File(folder, "data.db").absolutePath}").use { conn ->
            conn.createStatement().use { it.execute("PRAGMA user_version=99") }
        }
        assertFailsWith<PersistenceException> { store(folder) }
        DriverManager.getConnection("jdbc:sqlite:${File(folder, "data.db").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    rs.next()
                    assertEquals(99, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `missing migration resource surfaces as PersistenceException`() {
        DriverManager.getConnection("jdbc:sqlite:${File(folder, "data.db").absolutePath}").use { conn ->
            conn.createStatement().use { it.execute("PRAGMA user_version=-1") }
        }
        assertFailsWith<PersistenceException> { store(folder) }
    }

    @Test
    fun `failed migration statement rolls back the whole step`() {
        val file = File(folder, "data.db")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { real ->
            val conn = sabotagedConnection(real, "player_stats")
            assertFailsWith<PersistenceException> { SqliteMigrations(conn).migrate() }
        }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) AS c FROM sqlite_master WHERE type = 'table'").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt("c"))
                }
                st.executeQuery("PRAGMA user_version").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `a PersistenceException inside a migration is rethrown without rewrapping`() {
        DriverManager.getConnection("jdbc:sqlite:${File(folder, "data.db").absolutePath}").use { real ->
            val conn = sabotagedConnection(real, "player_stats") { PersistenceException("inner failure") }
            val failure = assertFailsWith<PersistenceException> { SqliteMigrations(conn).migrate() }
            assertEquals("inner failure", failure.message)
        }
    }

    @Test
    fun `a non Exception thrown inside a migration propagates unchanged`() {
        DriverManager.getConnection("jdbc:sqlite:${File(folder, "data.db").absolutePath}").use { real ->
            val conn = sabotagedConnection(real, "player_stats") { Error("injected error") }
            val failure = assertFailsWith<Error> { SqliteMigrations(conn).migrate() }
            assertEquals("injected error", failure.message)
        }
    }

    private fun sabotagedConnection(
        real: Connection,
        failingFragment: String,
        injected: () -> Throwable = { SQLException("injected migration failure") },
    ): Connection = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, args ->
        val result = method.invoke(real, *(args ?: emptyArray()))
        if (method.name == "createStatement") {
            sabotagedStatement(result as Statement, failingFragment, injected)
        } else {
            result
        }
    } as Connection

    private fun sabotagedStatement(
        real: Statement,
        failingFragment: String,
        injected: () -> Throwable,
    ): Statement = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Statement::class.java),
    ) { _, method, args ->
        val sql = args?.firstOrNull() as? String
        if (method.name == "execute" && sql != null && failingFragment in sql) {
            throw injected()
        }
        method.invoke(real, *(args ?: emptyArray()))
    } as Statement
}
