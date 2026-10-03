package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.infrastructure.persistence.fixtures.countRows
import net.ninebolt.onevsone.infrastructure.persistence.fixtures.store
import net.ninebolt.onevsone.infrastructure.persistence.fixtures.withStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteStoreTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `database file and schema are created`() = withStore(folder) { store ->
        assertTrue(File(folder, "data.db").exists())
        val tables = store.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(1) }
        assertTrue(
            tables.containsAll(
                listOf("arenas", "arena_kits", "arena_signs", "lobby", "backups", "player_stats"),
            ),
        )
        assertEquals("wal", store.queryOne("PRAGMA journal_mode") { it.getString(1) })
    }

    @Test
    fun `store open failure surfaces as PersistenceException`() {
        val blocker = File(folder, "not-a-dir")
        blocker.writeText("x")
        assertFailsWith<PersistenceException> { store(blocker) }
    }

    @Test
    fun `atomic rolls back every statement on failure`() = withStore(folder) { store ->
        assertFailsWith<PersistenceException> {
            store.atomic {
                store.exec("INSERT INTO lobby(id, world, x, y, z, yaw, pitch) VALUES (1, 'w', 0, 0, 0, 0, 0)")
                store.exec("INSERT INTO definitely_not_a_table VALUES (1)")
            }
        }
        assertEquals(0, countRows(store, "lobby"))
    }

    @Test
    fun `nested atomic is rejected and rolls back the outer transaction`() = withStore(folder) { store ->
        assertFailsWith<PersistenceException> {
            store.atomic {
                store.exec("INSERT INTO lobby(id, world, x, y, z, yaw, pitch) VALUES (1, 'w', 0, 0, 0, 0, 0)")
                store.atomic {
                    store.exec("INSERT INTO arenas(name, seq) VALUES ('a', 1)")
                }
            }
        }
        assertEquals(0, countRows(store, "lobby"))
        assertEquals(0, countRows(store, "arenas"))
    }

    @Test
    fun `store stays usable after a rejected nested atomic`() = withStore(folder) { store ->
        assertFailsWith<PersistenceException> {
            store.atomic { store.atomic { } }
        }
        store.atomic {
            store.exec("INSERT INTO lobby(id, world, x, y, z, yaw, pitch) VALUES (1, 'w', 0, 0, 0, 0, 0)")
        }
        assertEquals(1, countRows(store, "lobby"))
    }

    @Test
    fun `repositories never write config yml`() = withStore(folder) { store ->
        val file = File(folder, "config.yml")
        file.writeText("prefix: '&9[X] '\n")
        val before = file.readBytes()

        SqliteLobbyRepository(store).setLobby(WorldPosition.new("lobby", 1.0, 2.0, 3.0))
        SqliteArenaRepository(store).save(Arena.Disabled.new(arenaId("a1")))
        SqliteArenaSignRepository(store).setSign(arenaId("a1"), BlockPosition.new("world", 5, 64, 5))
        assertEquals(before.toList(), file.readBytes().toList())
    }
}
