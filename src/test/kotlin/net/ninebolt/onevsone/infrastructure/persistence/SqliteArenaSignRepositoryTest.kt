package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.infrastructure.persistence.fixtures.countRows
import net.ninebolt.onevsone.infrastructure.persistence.fixtures.withStore
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
        SqliteArenaSignRepository(store).setSign("a1", BlockPosition.new("world", 5, 64, 5))
        val fresh = SqliteArenaSignRepository(store)
        assertEquals("a1", fresh.signOwner(BlockPosition.new("world", 5, 64, 5)))
        assertEquals(5, fresh.signLocation("a1")!!.x)
    }

    @Test
    fun `setSign releases old position and clearSign removes it`() = withStore(folder) { store ->
        SqliteArenaRepository(store).save(Arena.Disabled.new(arenaId("a1")))
        val repo = SqliteArenaSignRepository(store)
        repo.setSign("a1", BlockPosition.new("world", 5, 64, 5))
        repo.setSign("a1", BlockPosition.new("world", 9, 64, 9))
        assertNull(repo.signOwner(BlockPosition.new("world", 5, 64, 5)))
        assertEquals("a1", repo.signOwner(BlockPosition.new("world", 9, 64, 9)))
        assertEquals(9, repo.signLocation("a1")!!.x)
        repo.clearSign("a1")
        assertNull(repo.signOwner(BlockPosition.new("world", 9, 64, 9)))
        assertEquals(0, countRows(store, "arena_signs"))
    }
}
