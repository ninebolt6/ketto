package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.BlockPosition
import net.ninebolt.ketto.domain.fixtures.arenaId
import net.ninebolt.ketto.infrastructure.persistence.fixtures.countRows
import net.ninebolt.ketto.infrastructure.persistence.fixtures.withStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteArenaSignRepositoryTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `sign index is rebuilt from the table by a new instance`() = withStore(folder) { store ->
        SqliteArenaRepository(store).save(Arena.Disabled.new(arenaId("a1")))
        SqliteArenaSignRepository(store).setSign(arenaId("a1"), BlockPosition.new("world", 5, 64, 5))
        val fresh = SqliteArenaSignRepository(store)
        assertEquals(arenaId("a1"), fresh.findSignOwner(BlockPosition.new("world", 5, 64, 5)))
        assertEquals(5, fresh.findSignLocation(arenaId("a1"))!!.x)
    }

    @Test
    fun `setSign releases old position and clearSign removes it`() = withStore(folder) { store ->
        SqliteArenaRepository(store).save(Arena.Disabled.new(arenaId("a1")))
        val repo = SqliteArenaSignRepository(store)
        repo.setSign(arenaId("a1"), BlockPosition.new("world", 5, 64, 5))
        repo.setSign(arenaId("a1"), BlockPosition.new("world", 9, 64, 9))
        assertNull(repo.findSignOwner(BlockPosition.new("world", 5, 64, 5)))
        assertEquals(arenaId("a1"), repo.findSignOwner(BlockPosition.new("world", 9, 64, 9)))
        assertEquals(9, repo.findSignLocation(arenaId("a1"))!!.x)
        repo.clearSign(arenaId("a1"))
        assertNull(repo.findSignOwner(BlockPosition.new("world", 9, 64, 9)))
        assertEquals(0, countRows(store, "arena_signs"))
    }

    @Test
    fun `rows with an invalid arena name are skipped`() = withStore(folder) { store ->
        store.exec("INSERT INTO arenas(name, enabled, seq) VALUES ('create', 0, 1)")
        store.exec("INSERT INTO arena_signs(arena_name, world, x, y, z) VALUES ('create', 'world', 5, 64, 5)")

        val repo = SqliteArenaSignRepository(store)

        assertNull(repo.findSignOwner(BlockPosition.new("world", 5, 64, 5)))
    }
}
