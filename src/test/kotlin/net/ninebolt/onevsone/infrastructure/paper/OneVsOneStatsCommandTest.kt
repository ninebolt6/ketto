package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.contains
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.writeStats
import net.kyori.adventure.text.Component
import org.bukkit.OfflinePlayer
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.Command
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/** /1vs1 stats と引数なし/権限/未知サブコマンドの検証。 */
class OneVsOneStatsCommandTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv
    private val cmd = mockk<Command>(relaxed = true)

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `no args shows usage`() {
        val p = env.player("Alice")
        env.run(p)
        verify(exactly = 1) { p.sendMessage(contains("/1vs1 stats | /1vs1 stats [player]")) }
    }

    @Test
    fun `console cannot run player commands`() {
        val console = mockk<BlockCommandSender>(relaxed = true)
        env.run(console, "stats")
        verify(exactly = 1) { console.sendMessage(contains("このコマンドはプレイヤーのみ実行可能です")) }
        env.run(console, "leave")
        verify(exactly = 2) { console.sendMessage(contains("このコマンドはプレイヤーのみ実行可能です")) }
    }

    @Test
    fun `non op management commands denied`() {
        val p = env.player("Alice")
        every { p.isOp } returns false
        env.run(p, "setlobby")
        verify(exactly = 1) { p.sendMessage(contains("権限がありません！")) }
        env.run(p, "arena", "create", "x")
        verify(exactly = 2) { p.sendMessage(contains("権限がありません！")) }
    }

    @Test
    fun `stats shows own stats or missing message`() {
        val p = env.player("Alice")
        env.run(p, "stats")
        verify(exactly = 1) { p.sendMessage(contains("Statsが存在しません")) }

        env.writeStats(p.uuid, 3, 0)
        env.run(p, "stats")
        verify(exactly = 1) { p.sendMessage(contains("Win: 3")) }
        verify(exactly = 1) { p.sendMessage(contains("Lose: 0")) }
        verify(exactly = 1) { p.sendMessage(contains("W/L(勝率): 3.00")) }
    }

    @Test
    fun `stats of other online player by exact name`() {
        val viewer = env.player("Viewer")
        val target = env.player("Target")
        env.writeStats(target.uuid, 0, 2)
        env.run(viewer, "stats", "Target")
        verify(exactly = 1) { viewer.sendMessage(contains("Win: 0")) }
        verify(exactly = 1) { viewer.sendMessage(contains("Lose: 2")) }
        verify(exactly = 1) { viewer.sendMessage(contains("W/L(勝率): 0.00")) }
    }

    @Test
    fun `stats of offline cached player resolves uuid`() {
        val viewer = env.player("Viewer")
        val uuid = Uuid.random()
        env.writeStats(uuid, 5, 5)
        val offline = mockk<OfflinePlayer>(relaxed = true)
        every { offline.uniqueId } returns uuid.toJavaUuid()
        every { env.server.getOfflinePlayerIfCached("Ghost") } returns offline
        env.run(viewer, "stats", "Ghost")
        verify(exactly = 1) { viewer.sendMessage(contains("W/L(勝率): 1.00")) }
    }

    @Test
    fun `stats offline uncached resolves through async scheduler on main thread`() {
        val viewer = env.player("Viewer")
        val uuid = Uuid.random()
        env.writeStats(uuid, 2, 1)
        val offline = mockk<OfflinePlayer>(relaxed = true)
        every { offline.uniqueId } returns uuid.toJavaUuid()
        every { env.server.getOfflinePlayer("Ghost") } returns offline
        env.run(viewer, "stats", "Ghost")
        verify(exactly = 0) { viewer.sendMessage(contains("Win:")) }
        env.runOneShots()
        verify(exactly = 1) { viewer.sendMessage(contains("Win: 2")) }
    }

    @Test
    fun `stats offline lookup failure reports no stats`() {
        val viewer = env.player("Viewer")
        every { env.server.getOfflinePlayer("Ghost") } throws RuntimeException("lookup failed")
        env.run(viewer, "stats", "Ghost")
        env.runOneShots()
        verify(exactly = 1) { viewer.sendMessage(contains("Statsが存在しません")) }
    }

    @Test
    fun `stats offline callback skipped when plugin disabled`() {
        val viewer = env.player("Viewer")
        every { env.plugin.isEnabled } returns false
        env.run(viewer, "stats", "Ghost")
        env.runOneShots()
        verify(exactly = 0) { viewer.sendMessage(any<Component>()) }
    }

    @Test
    fun `unknown subcommand falls back to usage`() {
        val p = env.player("Alice")
        env.run(p, "bogus")
        verify(exactly = 1) { p.sendMessage(contains("/1vs1 stats | /1vs1 stats [player]")) }
        env.run(p, "arena", "bogus")
        verify(exactly = 1) { p.sendMessage(contains("/1vs1 arena info [arena]")) }
    }
}
