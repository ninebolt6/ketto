package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import org.bukkit.Location
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Verifies the /1vs1 lobby namespace. */
class LobbyCommandTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `lobby set writes config`() {
        val p = env.opPlayer("Alice")
        p.setLocation(Location(env.world(), 7.5, 64.0, -2.5, 90f, 0f))
        env.run(p, "lobby", "set")
        assertTrue(p.drainMessages().any { it.contains("ロビーを設定しました") })
        val lobby = env.lobbyRepo.lobby()!!
        assertEquals(7.5, lobby.x)
        assertEquals(90f, lobby.yaw, 0.001f)
    }

    @Test
    fun `bare lobby shows usage`() {
        val p = env.opPlayer("Alice")
        env.run(p, "lobby")
        assertTrue(p.drainMessages().any { it.contains("/1vs1 lobby set") })
    }
}
