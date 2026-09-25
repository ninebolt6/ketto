package net.ninebolt.onevsone.infrastructure.paper.command

import io.mockk.every
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.offlineId
import net.ninebolt.onevsone.infrastructure.paper.fixtures.runCommand
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.writeStats
import org.bukkit.Server
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

class StatsCommandTest {

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
    fun `console cannot run stats`() {
        val console = env.server.consoleSender
        env.runCommand(console, "stats")
        assertTrue(console.drainMessages().any { it.contains("This command can only be used by players") })
    }

    @Test
    fun `stats shows own stats or missing message`() {
        val p = env.player("Alice")
        env.runCommand(p, "stats")
        assertTrue(p.drainMessages().any { it.contains("No stats found") })

        env.writeStats(p.uuid, 3, 0)
        env.runCommand(p, "stats")
        val msgs = p.drainMessages()
        assertTrue(msgs.any { it.contains("Win: 3") })
        assertTrue(msgs.any { it.contains("Lose: 0") })
        assertTrue(msgs.any { it.contains("W/L(ratio): 3.00") })
    }

    @Test
    fun `stats of other online player by exact name`() {
        val viewer = env.player("Viewer")
        val target = env.player("Target")
        env.writeStats(target.uuid, 0, 2)
        env.runCommand(viewer, "stats", "Target")
        val msgs = viewer.drainMessages()
        assertTrue(msgs.any { it.contains("Win: 0") })
        assertTrue(msgs.any { it.contains("Lose: 2") })
        assertTrue(msgs.any { it.contains("W/L(ratio): 0.00") })
    }

    @Test
    fun `stats of offline cached player resolves uuid`() {
        val viewer = env.player("Viewer")
        val ghost = env.player("Ghost")
        ghost.disconnect()
        env.writeStats(ghost.uuid, 5, 5)
        env.runCommand(viewer, "stats", "Ghost")
        assertTrue(viewer.drainMessages().any { it.contains("W/L(ratio): 1.00") })
    }

    @Test
    fun `stats offline uncached resolves through async scheduler on main thread`() {
        val viewer = env.player("Viewer")
        // getOfflinePlayer produces a deterministic OfflinePlayerMock for uncached names
        val uuid = env.offlineId("Ghost")
        env.writeStats(uuid, 2, 1)
        env.runCommand(viewer, "stats", "Ghost")
        assertTrue(viewer.drainMessages().none { it.contains("Win:") })
        env.runOneShots()
        assertTrue(viewer.drainMessages().any { it.contains("Win: 2") })
    }

    @Test
    fun `stats offline lookup failure reports no stats`() {
        val viewer = env.player("Viewer")
        every { (env.server as Server).getOfflinePlayer("Ghost") } throws RuntimeException("lookup failed")
        env.runCommand(viewer, "stats", "Ghost")
        env.runOneShots()
        assertTrue(viewer.drainMessages().any { it.contains("No stats found") })
    }

    @Test
    fun `stats offline callback skipped when plugin disabled`() {
        val viewer = env.player("Viewer")
        every { env.plugin.isEnabled } returns false
        env.runCommand(viewer, "stats", "Ghost")
        env.runOneShots()
        assertTrue(viewer.drainMessages().isEmpty())
    }

    @Test
    fun `stats callback is skipped when the requester went offline`() {
        val viewer = env.player("Viewer")
        env.runCommand(viewer, "stats", "Ghost")
        viewer.disconnect()
        env.runOneShots()
        assertTrue(viewer.drainMessages().none { it.contains("stats") })
    }

    @Test
    fun `repeated stats lookup is rate limited`() {
        val viewer = env.player("Viewer")
        val target = env.player("Target")
        env.writeStats(target.uuid, 1, 0)
        env.runCommand(viewer, "stats", "Target")
        assertTrue(viewer.drainMessages().any { it.contains("Win: 1") })

        env.runCommand(viewer, "stats", "Target")
        assertTrue(viewer.drainMessages().any { it.contains("wait a moment") })

        env.runCommand(viewer, "stats")
        assertTrue(viewer.drainMessages().any { it.contains("No stats found") })
    }

    @Test
    fun `stats read failure reports no stats`() {
        val p = env.player("Alice")
        env.store.exec("DROP TABLE player_stats")
        env.runCommand(p, "stats")
        assertTrue(p.drainMessages().any { it.contains("No stats found") })
    }
}
