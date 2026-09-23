package net.ninebolt.onevsone.infrastructure.paper.command

import io.mockk.every
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.offlineId
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.writeStats
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.bukkit.Server
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Verifies /1vs1 stats: own/other player lookups, offline resolution, and rate limiting. */
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
        env.run(console, "stats")
        assertTrue(console.drainMessages().any { it.contains("このコマンドはプレイヤーのみ実行可能です") })
    }

    @Test
    fun `stats shows own stats or missing message`() {
        val p = env.player("Alice")
        env.run(p, "stats")
        assertTrue(p.drainMessages().any { it.contains("Statsが存在しません") })

        env.writeStats(p.uuid, 3, 0)
        env.run(p, "stats")
        val msgs = p.drainMessages()
        assertTrue(msgs.any { it.contains("Win: 3") })
        assertTrue(msgs.any { it.contains("Lose: 0") })
        assertTrue(msgs.any { it.contains("W/L(勝率): 3.00") })
    }

    @Test
    fun `stats of other online player by exact name`() {
        val viewer = env.player("Viewer")
        val target = env.player("Target")
        env.writeStats(target.uuid, 0, 2)
        env.run(viewer, "stats", "Target")
        val msgs = viewer.drainMessages()
        assertTrue(msgs.any { it.contains("Win: 0") })
        assertTrue(msgs.any { it.contains("Lose: 2") })
        assertTrue(msgs.any { it.contains("W/L(勝率): 0.00") })
    }

    @Test
    fun `stats of offline cached player resolves uuid`() {
        val viewer = env.player("Viewer")
        // Online and disconnected players go through the cache-hit path
        val ghost = env.player("Ghost")
        env.writeStats(ghost.uuid, 5, 5)
        env.run(viewer, "stats", "Ghost")
        assertTrue(viewer.drainMessages().any { it.contains("W/L(勝率): 1.00") })
    }

    @Test
    fun `stats offline uncached resolves through async scheduler on main thread`() {
        val viewer = env.player("Viewer")
        // For uncached names, getOfflinePlayer produces a deterministic OfflinePlayerMock
        val uuid = env.offlineId("Ghost")
        env.writeStats(uuid, 2, 1)
        env.run(viewer, "stats", "Ghost")
        assertTrue(viewer.drainMessages().none { it.contains("Win:") })
        env.runOneShots()
        assertTrue(viewer.drainMessages().any { it.contains("Win: 2") })
    }

    @Test
    fun `stats offline lookup failure reports no stats`() {
        val viewer = env.player("Viewer")
        every { (env.server as Server).getOfflinePlayer("Ghost") } throws RuntimeException("lookup failed")
        env.run(viewer, "stats", "Ghost")
        env.runOneShots()
        assertTrue(viewer.drainMessages().any { it.contains("Statsが存在しません") })
    }

    @Test
    fun `stats offline callback skipped when plugin disabled`() {
        val viewer = env.player("Viewer")
        every { env.plugin.isEnabled } returns false
        env.run(viewer, "stats", "Ghost")
        env.runOneShots()
        assertTrue(viewer.drainMessages().isEmpty())
    }

    @Test
    fun `repeated stats lookup is rate limited`() {
        val viewer = env.player("Viewer")
        val target = env.player("Target")
        env.writeStats(target.uuid, 1, 0)
        env.run(viewer, "stats", "Target")
        assertTrue(viewer.drainMessages().any { it.contains("Win: 1") })

        env.run(viewer, "stats", "Target")
        assertTrue(viewer.drainMessages().any { it.contains("連続で実行できません") })

        // Viewing oneself involves no resolution, so it is not rate-limited
        env.run(viewer, "stats")
        assertTrue(viewer.drainMessages().any { it.contains("Statsが存在しません") })
    }
}
