package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Location
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
        p.setLocation(Location(env.world(), 7.5, 64.0, -2.5, 90f, 0f))
        env.run(p, "setlobby")
        assertTrue(p.drainMessages().any { it.contains("ロビーを設定しました") })
        val lobby = env.lobbyRepo.lobby()!!
        assertEquals(7.5, lobby.x)
        assertEquals(90f, lobby.yaw, 0.001f)
    }

    @Test
    fun `arena info shows state and players during match`() {
        val viewer = env.player("Viewer")
        env.run(viewer, "arena", "info", "missing")
        assertTrue(viewer.drainMessages().any { it.contains("そのアリーナは存在しません") })

        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.service.defeat(p2.uuid, DefeatCause.FALL)

        env.run(viewer, "arena", "info", "arena1")
        val msgs = viewer.drainMessages()
        assertTrue(msgs.any { it.contains("=== Arena[arena1] ===") })
        assertTrue(msgs.any { it.contains("状態: Ingame") })
        assertTrue(msgs.any { it.contains("[Alice] vs [Bob]") })
        assertTrue(msgs.any { it.contains("勝数: 1-0") })
    }

    @Test
    fun `arena create remove lifecycle`() {
        val op = env.opPlayer("Op")
        env.run(op, "arena", "create", "newarena")
        assertTrue(op.drainMessages().any { it.contains("アリーナ: newarena を作成しました") })
        assertEquals(false, env.service.arena("newarena")!!.enabled)

        env.run(op, "arena", "create", "newarena")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナはすでに存在しています") })

        env.run(op, "arena", "remove", "newarena")
        assertTrue(op.drainMessages().any { it.contains("アリーナ: newarena を削除しました") })
        assertNull(env.service.arena("newarena"))

        env.run(op, "arena", "remove", "newarena")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナは存在しません") })
    }

    @Test
    fun `arena enable disable`() {
        val op = env.opPlayer("Op")
        env.newArena("a2", enabled = false)
        env.run(op, "arena", "enable", "a2")
        assertTrue(op.drainMessages().any { it.contains("を有効にしました") })
        env.run(op, "arena", "enable", "a2")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナはすでに有効になっています！") })
        env.run(op, "arena", "disable", "a2")
        assertTrue(op.drainMessages().any { it.contains("を無効にしました") })
        env.run(op, "arena", "disable", "a2")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナはすでに無効です！") })
        assertEquals(false, env.service.arena("a2")!!.enabled)
    }

    @Test
    fun `arena setspawn saves fractional location`() {
        val op = env.opPlayer("Op")
        env.newArena()
        op.setLocation(Location(env.world(), 1.5, 65.25, -3.0, 33.3f, 12.5f))
        env.run(op, "arena", "setspawn1", "arena1")
        assertTrue(op.drainMessages().any { it.contains("のスポーン1を設定しました") })
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
        assertTrue(op.drainMessages().any { it.contains("のインベントリを設定しました") })
        assertEquals(Material.DIAMOND_SWORD, env.equipment.kitOf(Arena.Id.new("arena1"))?.items?.get(0)?.type)
    }

    @Test
    fun `arena setsign requires looking at sign`() {
        val op = env.opPlayer("Op")
        env.newArena()
        // 空ワールドの上空で何も指していない状態にする
        op.setLocation(Location(env.world(), 0.5, 100.0, 0.5, 0f, -90f))
        env.run(op, "arena", "setsign", "arena1")
        assertTrue(op.drainMessages().any { it.contains("看板を見て実行してください") })
    }

    @Test
    fun `arena setsign registers sign and reports taken`() {
        val op = env.opPlayer("Op")
        val second = env.opPlayer("Op2")
        env.newArena("arena1")
        env.newArena("arena2")

        env.signBlock(4, 64, 4)
        // 真上から看板を見下ろす位置・向きにする
        val gaze = Location(env.world(), 4.5, 65.5, 4.5, 0f, 90f)
        op.setLocation(gaze)
        second.setLocation(gaze.clone())

        env.run(op, "arena", "setsign", "arena1")
        assertEquals("arena1", env.signRepo.signOwner("world", 4, 64, 4))

        env.run(second, "arena", "setsign", "arena2")
        assertTrue(second.drainMessages().any { it.contains("その看板はすでに登録されています") })
    }

    @Test
    fun `arena removesign unregisters sign`() {
        val op = env.opPlayer("Op")
        env.newArena()
        env.signRepo.setSign("arena1", WorldPosition.new("world", 4.0, 64.0, 4.0))

        env.run(op, "arena", "removesign", "arena1")
        assertTrue(op.drainMessages().any { it.contains("の看板登録を解除しました") })
        assertNull(env.signRepo.signOwner("world", 4, 64, 4))
        assertNull(env.signRepo.signLocation("arena1"))

        env.run(op, "arena", "removesign", "arena1")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナには看板が登録されていません") })

        env.run(op, "arena", "removesign", "missing")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナは存在しません") })
    }

    @Test
    fun `missing arg shows red usage`() {
        val op = env.opPlayer("Op")
        env.run(op, "arena", "create")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena create [arena]") })
        env.run(op, "arena", "setsign")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena setsign [arena]") })
    }

    @Test
    fun `extra args rejected by create but tolerated by info`() {
        val op = env.opPlayer("Op")
        env.newArena()
        env.run(op, "arena", "create", "other", "extra")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena create [arena]") })
        env.run(op, "arena", "info", "arena1", "extra")
        assertTrue(op.drainMessages().any { it.contains("Arena[arena1]") })
    }

    @Test
    fun `console can run admin commands`() {
        val console = env.server.consoleSender
        env.run(console, "arena", "create", "consolearena")
        assertTrue(console.drainMessages().any { it.contains("アリーナ: consolearena を作成しました") })
        env.run(console, "arena", "setspawn1", "consolearena")
        assertTrue(console.drainMessages().any { it.contains("このコマンドはプレイヤーのみ実行可能です") })
    }
}
