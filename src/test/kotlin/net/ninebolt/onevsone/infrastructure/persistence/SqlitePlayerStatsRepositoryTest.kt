package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.infrastructure.persistence.fixtures.withStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class SqlitePlayerStatsRepositoryTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `stats save and load per uuid`() = withStore(folder) { store ->
        val repo = SqlitePlayerStatsRepository(store)
        val uuid = Uuid.random()
        assertNull(repo.find(uuid))
        repo.save(PlayerStats.restored(uuid, wins = 1, losses = 2))
        val loaded = repo.find(uuid)!!
        assertEquals(uuid, loaded.playerId)
        assertEquals(1, loaded.wins)
        assertEquals(2, loaded.losses)
    }

    @Test
    fun `save overwrites existing stats`() = withStore(folder) { store ->
        val repo = SqlitePlayerStatsRepository(store)
        val uuid = Uuid.random()
        repo.save(PlayerStats.new(uuid))
        repo.save(PlayerStats.restored(uuid, wins = 5, losses = 3))
        val loaded = repo.find(uuid)!!
        assertEquals(5, loaded.wins)
        assertEquals(3, loaded.losses)
    }
}
