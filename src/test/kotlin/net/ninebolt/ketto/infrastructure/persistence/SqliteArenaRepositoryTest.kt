package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.application.port.PersistenceException
import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.BlockPosition
import net.ninebolt.ketto.domain.WorldPosition
import net.ninebolt.ketto.domain.fixtures.arenaId
import net.ninebolt.ketto.infrastructure.paper.PaperInventorySnapshot
import net.ninebolt.ketto.infrastructure.persistence.fixtures.countRows
import net.ninebolt.ketto.infrastructure.persistence.fixtures.enabledArena
import net.ninebolt.ketto.infrastructure.persistence.fixtures.withStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteArenaRepositoryTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `arena round trip keeps fractional yaw pitch and enabled`() = withStore(folder) { store ->
        val repo = SqliteArenaRepository(store)
        val def = Arena.Enabled.restored(
            arenaId("a1"),
            WorldPosition.new("world", 1.5, 64.25, -3.75, 12.34f, -56.78f),
            WorldPosition.new("world", 2.0, 64.0, 2.0),
        )
        repo.save(def)

        val loaded = repo.loadAll().single()
        assertTrue(loaded.enabled)
        val spawn1 = loaded.spawn1!!
        assertEquals(1.5, spawn1.x)
        assertEquals(12.34f, spawn1.yaw, 0.001f)
        assertEquals(-56.78f, spawn1.pitch, 0.001f)
    }

    @Test
    fun `loadAll follows insertion order and updates keep the slot`() = withStore(folder) { store ->
        val repo = SqliteArenaRepository(store)
        repo.save(Arena.Disabled.new(arenaId("b1")))
        repo.save(enabledArena("a1"))
        repo.save(enabledArena("b1"))

        val loaded = repo.loadAll()
        assertEquals(listOf("b1", "a1"), loaded.map { it.name })
        assertTrue(loaded[0].enabled)

        repo.delete(arenaId("b1"))
        assertEquals(listOf("a1"), repo.loadAll().map { it.name })
    }

    @Test
    fun `arena names collide case insensitively`() = withStore(folder) { store ->
        val repo = SqliteArenaRepository(store)
        repo.save(Arena.Disabled.new(arenaId("Arena1")))
        repo.save(enabledArena("arena1"))
        val loaded = repo.loadAll()
        assertEquals(1, loaded.size)
        assertTrue(loaded[0].enabled)
    }

    @Test
    fun `arena delete cascades kit and sign`() = withStore(folder) { store ->
        val arenas = SqliteArenaRepository(store)
        arenas.save(enabledArena("a1"))
        SqliteKitStore(store).saveArenaKit("a1", PaperInventorySnapshot())
        SqliteArenaSignRepository(store).setSign(arenaId("a1"), BlockPosition.new("world", 1, 2, 3))

        arenas.delete(arenaId("a1"))
        assertEquals(0, countRows(store, "arena_kits"))
        assertEquals(0, countRows(store, "arena_signs"))
    }

    @Test
    fun `corrupt arena row is skipped on load`() = withStore(folder) { store ->
        store.exec(
            "INSERT INTO arenas(name, enabled, spawn1_world, spawn1_x, spawn1_y, spawn1_z, seq) VALUES ('a1', 1, '', 0, 0, 0, 1)",
        )
        assertEquals(emptyList(), SqliteArenaRepository(store).loadAll())
    }

    @Test
    fun `an enabled arena row missing a spawn loads as disabled with a warning`() = withStore(folder) { store ->
        store.exec(
            "INSERT INTO arenas(name, enabled, spawn1_world, spawn1_x, spawn1_y, spawn1_z, seq) VALUES ('a1', 1, 'world', 0, 0, 0, 1)",
        )
        val messages = mutableListOf<String>()
        val logger = Logger.getAnonymousLogger().apply {
            addHandler(
                object : Handler() {
                    override fun publish(record: LogRecord) {
                        messages += record.message
                    }

                    override fun flush() {}
                    override fun close() {}
                },
            )
        }
        val repo = SqliteArenaRepository(store, logger)

        val arena = repo.loadAll().single()
        assertFalse(arena.enabled)
        assertEquals("world", arena.spawn1?.world)
        assertNull(arena.spawn2)
        assertTrue(messages.any { it.contains("loading as disabled") })
        assertFalse(repo.loadAll().single().enabled)
    }

    @Test
    fun `loadAll skips an arena row whose name is not a valid id`() = withStore(folder) { store ->
        val repo = SqliteArenaRepository(store)
        repo.save(Arena.Disabled.new(arenaId("a1")))
        store.exec("UPDATE arenas SET name='create' WHERE name='a1'")
        assertEquals(emptyList(), repo.loadAll())
    }
}
