package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.fixtures.arenaId
import net.ninebolt.ketto.infrastructure.paper.PaperInventorySnapshot
import net.ninebolt.ketto.infrastructure.persistence.fixtures.withStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class SqliteKitStoreTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `kit round trips`() = withStore(folder) { store ->
        SqliteArenaRepository(store).save(Arena.Disabled.new(arenaId("a1")))
        val kits = SqliteKitStore(store)
        assertEquals(PaperInventorySnapshot(), kits.loadArenaKit("a1"))
        kits.saveArenaKit("a1", PaperInventorySnapshot(items = listOf(null, null)))
        assertEquals(2, kits.loadArenaKit("a1").items.size)
    }
}
