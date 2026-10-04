package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.domain.WorldPosition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LobbyServiceTest {

    @Test
    fun `setLobby persists position`() {
        val app = TestApp()
        val pos = WorldPosition.new("lobby", 5.0, 64.0, 5.0)
        app.lobby.setLobby(pos)
        assertEquals(pos, app.arenaRepository.lobbyPosition)
    }
}
