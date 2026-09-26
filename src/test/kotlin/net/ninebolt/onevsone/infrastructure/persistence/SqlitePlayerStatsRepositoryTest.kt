package net.ninebolt.onevsone.infrastructure.persistence

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
    fun `stats record win and lose per uuid`() = withStore(folder) { store ->
        val repo = SqlitePlayerStatsRepository(store)
        val uuid = Uuid.random()
        assertNull(repo.find(uuid))
        repo.recordWin(uuid)
        repo.recordLoss(uuid)
        repo.recordLoss(uuid)
        val loaded = repo.find(uuid)!!
        assertEquals(1, loaded.wins)
        assertEquals(2, loaded.losses)
    }
}
