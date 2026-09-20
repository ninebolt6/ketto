package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.contains
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.block.sign.SignSide
import org.bukkit.command.BlockCommandSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** /1vs1 arena * ・setlobby の管理コマンドの検証。 */
class OneVsOneAdminCommandTest {

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
    fun `setlobby writes config`() {
        val p = env.opPlayer("Alice")
        val w = env.world()
        every { p.location } returns Location(w, 7.5, 64.0, -2.5, 90f, 0f)
        env.run(p, "setlobby")
        verify(exactly = 1) { p.sendMessage(contains("ロビーを設定しました")) }
        val lobby = env.lobbyRepo.lobby()!!
        assertEquals(7.5, lobby.x)
        assertEquals(90f, lobby.yaw, 0.001f)
    }

    @Test
    fun `arena info shows state and players during match`() {
        val viewer = env.player("Viewer")
        env.run(viewer, "arena", "info", "missing")
        verify(exactly = 1) { viewer.sendMessage(contains("そのアリーナは存在しません")) }

        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)

        env.run(viewer, "arena", "info", "arena1")
        verify(exactly = 1) { viewer.sendMessage(contains("=== §aArena[§b§larena1§a] §e===")) }
        verify(exactly = 1) { viewer.sendMessage(contains("状態: §c§lIngame")) }
        verify(exactly = 1) { viewer.sendMessage(contains("[§6Alice§c] vs [§6Bob§c]")) }
        verify(exactly = 1) { viewer.sendMessage(contains("勝数: §a1-0")) }
    }

    @Test
    fun `arena create remove lifecycle`() {
        val op = env.opPlayer("Op")
        env.run(op, "arena", "create", "newarena")
        verify(exactly = 1) { op.sendMessage(contains("アリーナ: newarena を作成しました")) }
        assertEquals(false, env.service.definition("newarena")!!.enabled)

        env.run(op, "arena", "create", "newarena")
        verify(exactly = 1) { op.sendMessage(contains("そのアリーナはすでに存在しています")) }

        env.run(op, "arena", "remove", "newarena")
        verify(exactly = 1) { op.sendMessage(contains("アリーナ: newarena を削除しました")) }
        assertNull(env.service.definition("newarena"))

        env.run(op, "arena", "remove", "newarena")
        verify(exactly = 1) { op.sendMessage(contains("そのアリーナは存在しません")) }
    }

    @Test
    fun `arena enable disable`() {
        val op = env.opPlayer("Op")
        env.newArena("a2", enabled = false)
        env.run(op, "arena", "enable", "a2")
        verify(exactly = 1) { op.sendMessage(contains("を有効にしました")) }
        env.run(op, "arena", "enable", "a2")
        verify(exactly = 1) { op.sendMessage(contains("そのアリーナはすでに有効になっています！")) }
        env.run(op, "arena", "disable", "a2")
        verify(exactly = 1) { op.sendMessage(contains("を無効にしました")) }
        env.run(op, "arena", "disable", "a2")
        verify(exactly = 1) { op.sendMessage(contains("§cそのアリーナはすでに無効です！")) }
        assertEquals(false, env.service.definition("a2")!!.enabled)
    }

    @Test
    fun `arena setspawn saves fractional location`() {
        val op = env.opPlayer("Op")
        env.newArena()
        val w = env.world()
        every { op.location } returns Location(w, 1.5, 65.25, -3.0, 33.3f, 12.5f)
        env.run(op, "arena", "setspawn1", "arena1")
        verify(exactly = 1) { op.sendMessage(contains("のスポーン1を設定しました")) }
        val spawn1 = env.arenaRepo.find("arena1")!!.spawn1!!
        assertEquals(33.3f, spawn1.yaw, 0.001f)
        assertEquals(65.25, spawn1.y)
    }

    @Test
    fun `arena setInv saves kit`() {
        val op = env.opPlayer("Op")
        env.newArena()
        op.inventory.setItem(0, env.item(Material.DIAMOND_SWORD))
        env.run(op, "arena", "setInv", "arena1")
        verify(exactly = 1) { op.sendMessage(contains("のインベントリを設定しました")) }
        assertEquals(Material.DIAMOND_SWORD, env.equipment.kitOf(ArenaId("arena1"))?.items?.get(0)?.type)
    }

    @Test
    fun `arena setsign requires looking at sign`() {
        val op = env.opPlayer("Op")
        env.newArena()
        every { op.getTargetBlockExact(10) } returns null
        env.run(op, "arena", "setsign", "arena1")
        verify(exactly = 1) { op.sendMessage(contains("看板を見て実行してください")) }
    }

    @Test
    fun `arena setsign registers sign and reports taken`() {
        val op = env.opPlayer("Op")
        val second = env.opPlayer("Op2")
        env.newArena("arena1")
        env.newArena("arena2")

        val block = mockk<Block>(relaxed = true)
        val sign = mockk<Sign>(relaxed = true)
        val w = env.world()
        every { block.state } returns sign
        every { block.world } returns w
        every { block.x } returns 4
        every { block.y } returns 64
        every { block.z } returns 4
        every { block.location } returns Location(w, 4.0, 64.0, 4.0)
        every { w.getBlockAt(4, 64, 4) } returns block
        every { sign.getSide(Side.FRONT) } returns mockk<SignSide>(relaxed = true)
        every { op.getTargetBlockExact(10) } returns block
        every { second.getTargetBlockExact(10) } returns block

        env.run(op, "arena", "setsign", "arena1")
        assertEquals("arena1", env.signRepo.signOwner("world", 4, 64, 4))

        env.run(second, "arena", "setsign", "arena2")
        verify(exactly = 1) { second.sendMessage(contains("その看板はすでに登録されています")) }
    }

    @Test
    fun `arena removesign unregisters sign`() {
        val op = env.opPlayer("Op")
        env.newArena()
        env.signRepo.setSign("arena1", WorldPosition("world", 4.0, 64.0, 4.0))

        env.run(op, "arena", "removesign", "arena1")
        verify(exactly = 1) { op.sendMessage(contains("の看板登録を解除しました")) }
        assertNull(env.signRepo.signOwner("world", 4, 64, 4))
        assertNull(env.signRepo.signLocation("arena1"))

        env.run(op, "arena", "removesign", "arena1")
        verify(exactly = 1) { op.sendMessage(contains("そのアリーナには看板が登録されていません")) }

        env.run(op, "arena", "removesign", "missing")
        verify(exactly = 1) { op.sendMessage(contains("そのアリーナは存在しません")) }
    }

    @Test
    fun `missing arg shows red usage`() {
        val op = env.opPlayer("Op")
        env.run(op, "arena", "create")
        verify(exactly = 1) { op.sendMessage(contains("§c/1vs1 arena create [arena]")) }
        env.run(op, "arena", "setsign")
        verify(exactly = 1) { op.sendMessage(contains("§c/1vs1 arena setsign [arena]")) }
    }

    @Test
    fun `extra args rejected by create but tolerated by info`() {
        val op = env.opPlayer("Op")
        env.newArena()
        env.run(op, "arena", "create", "other", "extra")
        verify(exactly = 1) { op.sendMessage(contains("§c/1vs1 arena create [arena]")) }
        env.run(op, "arena", "info", "arena1", "extra")
        verify(exactly = 1) { op.sendMessage(contains("Arena[§b§larena1§a]")) }
    }

    @Test
    fun `console can run admin commands`() {
        val console = mockk<BlockCommandSender>(relaxed = true)
        every { console.isOp } returns true
        env.run(console, "arena", "create", "consolearena")
        verify(exactly = 1) { console.sendMessage(contains("アリーナ: consolearena を作成しました")) }
        env.run(console, "arena", "setspawn1", "consolearena")
        verify(exactly = 1) { console.sendMessage(contains("このコマンドはプレイヤーのみ実行可能です")) }
    }
}
