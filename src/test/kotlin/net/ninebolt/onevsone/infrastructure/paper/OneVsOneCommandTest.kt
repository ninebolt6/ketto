package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.ninebolt.onevsone.domain.DefeatCause
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class OneVsOneCommandTest {

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

    private fun MockKMatcherScope.contains(part: String): String = match { it.contains(part) }

    private fun run(sender: org.bukkit.command.CommandSender, vararg args: String) =
        env.command.onCommand(sender, cmd, "1vs1", arrayOf(*args))

    private fun writeStats(uuid: UUID, win: Int, lose: Int) {
        repeat(win) { env.statsRepo.recordWin(uuid) }
        repeat(lose) { env.statsRepo.recordLoss(uuid) }
    }

    private fun opPlayer(name: String): Player {
        val p = env.player(name)
        every { p.isOp } returns true
        return p
    }

    @Test
    fun `no args shows usage`() {
        val p = env.player("Alice")
        run(p)
        verify(exactly = 1) { p.sendMessage(contains("/1vs1 stats | /1vs1 stats [player]")) }
    }

    @Test
    fun `console cannot run player commands`() {
        val console = mockk<BlockCommandSender>(relaxed = true)
        run(console, "stats")
        verify(exactly = 1) { console.sendMessage(contains("このコマンドはプレイヤーのみ実行可能です")) }
        run(console, "leave")
        verify(exactly = 2) { console.sendMessage(contains("このコマンドはプレイヤーのみ実行可能です")) }
    }

    @Test
    fun `non op management commands denied`() {
        val p = env.player("Alice")
        every { p.isOp } returns false
        run(p, "setlobby")
        verify(exactly = 1) { p.sendMessage(contains("権限がありません！")) }
        run(p, "arena", "create", "x")
        verify(exactly = 2) { p.sendMessage(contains("権限がありません！")) }
    }

    @Test
    fun `stats shows own stats or missing message`() {
        val p = env.player("Alice")
        run(p, "stats")
        verify(exactly = 1) { p.sendMessage(contains("Statsが存在しません")) }

        writeStats(p.uniqueId, 3, 0)
        run(p, "stats")
        verify(exactly = 1) { p.sendMessage(contains("Win: §b3")) }
        verify(exactly = 1) { p.sendMessage(contains("Lose: §b0")) }
        verify(exactly = 1) { p.sendMessage(contains("W/L(勝率): §b3.00")) }
    }

    @Test
    fun `stats of other online player by exact name`() {
        val viewer = env.player("Viewer")
        val target = env.player("Target")
        writeStats(target.uniqueId, 0, 2)
        run(viewer, "stats", "Target")
        verify(exactly = 1) { viewer.sendMessage(contains("Win: §b0")) }
        verify(exactly = 1) { viewer.sendMessage(contains("Lose: §b2")) }
        verify(exactly = 1) { viewer.sendMessage(contains("W/L(勝率): §b0.00")) }
    }

    @Test
    fun `stats of offline cached player resolves uuid`() {
        val viewer = env.player("Viewer")
        val uuid = UUID.randomUUID()
        writeStats(uuid, 5, 5)
        val offline = mockk<org.bukkit.OfflinePlayer>(relaxed = true)
        every { offline.uniqueId } returns uuid
        every { env.server.getOfflinePlayerIfCached("Ghost") } returns offline
        run(viewer, "stats", "Ghost")
        verify(exactly = 1) { viewer.sendMessage(contains("W/L(勝率): §b1.00")) }
    }

    @Test
    fun `stats offline uncached resolves through future on main thread`() {
        val viewer = env.player("Viewer")
        val uuid = UUID.randomUUID()
        writeStats(uuid, 2, 1)
        val command = OneVsOneCommand(env.plugin, env.service, env.admin, env.messages) {
            java.util.concurrent.CompletableFuture.completedFuture(uuid)
        }
        command.onCommand(viewer, cmd, "1vs1", arrayOf("stats", "Ghost"))
        verify(exactly = 0) { viewer.sendMessage(contains("Win:")) }
        env.runOneShots()
        verify(exactly = 1) { viewer.sendMessage(contains("Win: §b2")) }
    }

    @Test
    fun `stats offline lookup failure reports no stats`() {
        val viewer = env.player("Viewer")
        val command = OneVsOneCommand(env.plugin, env.service, env.admin, env.messages) {
            java.util.concurrent.CompletableFuture.failedFuture<UUID>(RuntimeException("lookup failed"))
        }
        command.onCommand(viewer, cmd, "1vs1", arrayOf("stats", "Ghost"))
        env.runOneShots()
        verify(exactly = 1) { viewer.sendMessage(contains("Statsが存在しません")) }
    }

    @Test
    fun `stats offline callback skipped when plugin disabled`() {
        val viewer = env.player("Viewer")
        every { env.plugin.isEnabled } returns false
        val command = OneVsOneCommand(env.plugin, env.service, env.admin, env.messages) {
            java.util.concurrent.CompletableFuture.completedFuture(UUID.randomUUID())
        }
        command.onCommand(viewer, cmd, "1vs1", arrayOf("stats", "Ghost"))
        env.runOneShots()
        verify(exactly = 0) { viewer.sendMessage(any<net.kyori.adventure.text.Component>()) }
    }

    @Test
    fun `setlobby writes config`() {
        val p = opPlayer("Alice")
        val w = env.world()
        every { p.location } returns Location(w, 7.5, 64.0, -2.5, 90f, 0f)
        run(p, "setlobby")
        verify(exactly = 1) { p.sendMessage(contains("ロビーを設定しました")) }
        val lobby = env.arenaRepo.lobby()!!
        assertEquals(7.5, lobby.x)
        assertEquals(90f, lobby.yaw, 0.001f)
    }

    @Test
    fun `arena info shows state and players during match`() {
        val viewer = env.player("Viewer")
        run(viewer, "arena", "info", "missing")
        verify(exactly = 1) { viewer.sendMessage(contains("そのアリーナは存在しません")) }

        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)

        run(viewer, "arena", "info", "arena1")
        verify(exactly = 1) { viewer.sendMessage(contains("=== §aArena[§b§larena1§a] §e===")) }
        verify(exactly = 1) { viewer.sendMessage(contains("状態: §c§lIngame")) }
        verify(exactly = 1) { viewer.sendMessage(contains("[§6Alice§c] vs [§6Bob§c]")) }
        verify(exactly = 1) { viewer.sendMessage(contains("勝数: §a1-0")) }
    }

    @Test
    fun `arena create remove lifecycle`() {
        val op = opPlayer("Op")
        run(op, "arena", "create", "newarena")
        verify(exactly = 1) { op.sendMessage(contains("アリーナ: newarena を作成しました")) }
        assertEquals(false, env.service.definition("newarena")!!.enabled)

        run(op, "arena", "create", "newarena")
        verify(exactly = 1) { op.sendMessage(contains("そのアリーナはすでに存在しています")) }

        run(op, "arena", "remove", "newarena")
        verify(exactly = 1) { op.sendMessage(contains("アリーナ: newarena を削除しました")) }
        org.junit.jupiter.api.Assertions.assertNull(env.service.definition("newarena"))

        run(op, "arena", "remove", "newarena")
        verify(exactly = 1) { op.sendMessage(contains("そのアリーナは存在しません")) }
    }

    @Test
    fun `arena enable disable`() {
        val op = opPlayer("Op")
        env.newArena("a2", enabled = false)
        run(op, "arena", "enable", "a2")
        verify(exactly = 1) { op.sendMessage(contains("を有効にしました")) }
        run(op, "arena", "enable", "a2")
        verify(exactly = 1) { op.sendMessage(contains("そのアリーナはすでに有効になっています！")) }
        run(op, "arena", "disable", "a2")
        verify(exactly = 1) { op.sendMessage(contains("を無効にしました")) }
        run(op, "arena", "disable", "a2")
        verify(exactly = 1) { op.sendMessage(contains("§cそのアリーナはすでに無効です！")) }
        assertEquals(false, env.service.definition("a2")!!.enabled)
    }

    @Test
    fun `arena setspawn saves fractional location`() {
        val op = opPlayer("Op")
        env.newArena()
        val w = env.world()
        every { op.location } returns Location(w, 1.5, 65.25, -3.0, 33.3f, 12.5f)
        run(op, "arena", "setspawn1", "arena1")
        verify(exactly = 1) { op.sendMessage(contains("のスポーン1を設定しました")) }
        val spawn1 = env.arenaRepo.find("arena1")!!.spawn1!!
        assertEquals(33.3f, spawn1.yaw, 0.001f)
        assertEquals(65.25, spawn1.y)
    }

    @Test
    fun `arena setInv saves kit`() {
        val op = opPlayer("Op")
        env.newArena()
        op.inventory.setItem(0, env.item(Material.DIAMOND_SWORD))
        run(op, "arena", "setInv", "arena1")
        verify(exactly = 1) { op.sendMessage(contains("のインベントリを設定しました")) }
        assertEquals(Material.DIAMOND_SWORD, env.equipment.kitOf(net.ninebolt.onevsone.domain.ArenaId("arena1"))?.items?.get(0)?.type)
    }

    @Test
    fun `arena setsign requires looking at sign`() {
        val op = opPlayer("Op")
        env.newArena()
        every { op.getTargetBlockExact(10) } returns null
        run(op, "arena", "setsign", "arena1")
        verify(exactly = 1) { op.sendMessage(contains("看板を見て実行してください")) }
    }

    @Test
    fun `arena setsign registers sign and reports taken`() {
        val op = opPlayer("Op")
        val second = opPlayer("Op2")
        env.newArena("arena1")
        env.newArena("arena2")

        val block = mockk<org.bukkit.block.Block>(relaxed = true)
        val sign = mockk<org.bukkit.block.Sign>(relaxed = true)
        val w = env.world()
        every { block.state } returns sign
        every { block.world } returns w
        every { block.x } returns 4
        every { block.y } returns 64
        every { block.z } returns 4
        every { block.location } returns Location(w, 4.0, 64.0, 4.0)
        every { w.getBlockAt(4, 64, 4) } returns block
        every { sign.getSide(org.bukkit.block.sign.Side.FRONT) } returns mockk<org.bukkit.block.sign.SignSide>(relaxed = true)
        every { op.getTargetBlockExact(10) } returns block
        every { second.getTargetBlockExact(10) } returns block

        run(op, "arena", "setsign", "arena1")
        assertEquals("arena1", env.arenaRepo.signOwner("world", 4.0, 64.0, 4.0))

        run(second, "arena", "setsign", "arena2")
        verify(exactly = 1) { second.sendMessage(contains("その看板はすでに登録されています")) }
    }

    @Test
    fun `missing arg shows red usage`() {
        val op = opPlayer("Op")
        run(op, "arena", "create")
        verify(exactly = 1) { op.sendMessage(contains("§c/1vs1 arena create [arena]")) }
        run(op, "arena", "setsign")
        verify(exactly = 1) { op.sendMessage(contains("§c/1vs1 arena setsign [arena]")) }
    }

    @Test
    fun `console can run admin commands`() {
        val console = mockk<BlockCommandSender>(relaxed = true)
        every { console.isOp } returns true
        run(console, "arena", "create", "consolearena")
        verify(exactly = 1) { console.sendMessage(contains("アリーナ: consolearena を作成しました")) }
        run(console, "arena", "setspawn1", "consolearena")
        verify(exactly = 1) { console.sendMessage(contains("このコマンドはプレイヤーのみ実行可能です")) }
    }

    @Test
    fun `unknown subcommand falls back to usage`() {
        val p = env.player("Alice")
        run(p, "bogus")
        verify(exactly = 1) { p.sendMessage(contains("/1vs1 stats | /1vs1 stats [player]")) }
        run(p, "arena", "bogus")
        verify(exactly = 1) { p.sendMessage(contains("/1vs1 arena info [arena]")) }
    }
}
