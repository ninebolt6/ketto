package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.domain.WorldPosition
import net.ninebolt.ketto.infrastructure.persistence.fixtures.withStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteLobbyRepositoryTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `lobby round trips`() = withStore(folder) { store ->
        val lobby = SqliteLobbyRepository(store)
        assertNull(lobby.findLobby())
        lobby.setLobby(WorldPosition.new("lobby", 1.0, 2.0, 3.0, 45.5f, 10.25f))
        val loaded = lobby.findLobby()!!
        assertEquals("lobby", loaded.world)
        assertEquals(45.5f, loaded.yaw, 0.001f)
    }
}
