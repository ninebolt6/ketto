package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.ArenaPlayerMock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.lastBroadcast
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.view
import org.bukkit.Material
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 参加・カウントダウン・ラウンド進行・終了の正常系シナリオ。 */
class PaperMatchProgressionTest {

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
    fun `first join sets ONEMORE second join starts COUNTDOWN`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        assertEquals(ArenaState.ONEMORE, env.view().state)
        assertEquals(arena, env.service.arenaIdOf(p1.uuid))
        val joined = p1.drainMessages()
        assertTrue(joined.any { it.contains("に参加しました") })
        assertTrue(joined.any { it.contains("あと一人参加するのを待っています") })

        val p2 = env.player("Bob")
        env.join(p2, arena)
        assertEquals(ArenaState.COUNTDOWN, env.view().state)
        env.tick()
        assertTrue(p1.drainMessages().any { it.contains("テレポートまで:") })
    }

    @Test
    fun `join rejected when already participant or arena disabled or ingame`() {
        val arena = env.newArena()
        val disabled = env.newArena("disabled", enabled = false)
        val p1 = env.player("Alice")
        env.join(p1, disabled)
        assertTrue(p1.drainMessages().any { it.contains("アリーナが有効になっていません！") })
        assertNull(env.service.arenaIdOf(p1.uuid))

        env.join(p1, arena)
        env.join(p1, arena)
        assertTrue(p1.drainMessages().any { it.contains("すでに他のアリーナに参加しています") })

        val p2 = env.player("Bob")
        env.join(p2, arena)
        val p3 = env.player("Carol")
        env.join(p3, arena)
        assertTrue(p3.drainMessages().any { it.contains("このアリーナは現在ゲーム中です") })
        assertNull(env.service.arenaIdOf(p3.uuid))
    }

    @Test
    fun `initial countdown messages then INGAME with equip teleport scoreboard`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)

        (5 downTo 1).forEach { n ->
            env.tick()
            assertTrue(p1.drainMessages().any { it.contains("テレポートまで: ${n}秒") })
        }
        assertEquals(ArenaState.COUNTDOWN, env.view().state)

        env.tick()
        assertEquals(ArenaState.INGAME, env.view().state)
        assertTrue(p1.drainMessages().any { it.contains("ゲームスタート！") })
        assertTrue(p1.hasTeleported())
        assertTrue(p2.hasTeleported())
        assertEquals(1.0, p1.location.x, 0.001)
        assertEquals(2.0, p2.location.x, 0.001)
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)
        assertEquals(Material.IRON_SWORD, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `nonfinal loss awards round then round countdown restarts INGAME`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertEquals(ArenaState.INGAME, env.view().state)

        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.view().state)
        assertEquals(1, env.view().winsOf(p1.uuid))
        val roundEnd = p1.drainMessages()
        assertTrue(roundEnd.any { it.contains("ラウンド[") })
        assertTrue(roundEnd.any { it.contains("勝者: Alice") })

        env.tick()
        env.tick()
        (5 downTo 1).forEach { n ->
            env.tick()
            assertTrue(p1.drainMessages().any { it.contains("開始まで: ${n}秒") })
        }
        env.tick()
        assertEquals(ArenaState.INGAME, env.view().state)
        // ラウンド開始「スタート！」はゲーム開始「ゲームスタート！」の部分文字列なので prefix 境界で区別する
        assertTrue(p1.drainMessages().any { it.contains("] スタート！") })
    }

    @Test
    fun `requiredWins 3 ends match on third loss and final kill not counted`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(1, env.view().winsOf(p1.uuid))
        env.tick(8)
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(2, env.view().winsOf(p1.uuid))
        env.tick(8)
        env.service.defeat(p2.uuid, DefeatCause.FALL)

        assertEquals(ArenaState.WAITING, env.view().state)
        assertTrue(env.view().wins.isEmpty())
        assertTrue(env.view().participants.isEmpty())
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertNull(env.service.arenaIdOf(p2.uuid))
        assertTrue(env.lastBroadcast().contains("が優勝しました！"))
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
    }

    @Test
    fun `death schedules respawn before re-equip`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        // 再装備の有無をスロット記録で識別できるよう、敗北者の先頭スロットを空にする
        p2.inventory.setItem(0, null)

        p2.health = 0.0
        env.service.defeat(p2.uuid, DefeatCause.DEATH)
        env.runOneShots()
        assertEquals(1, p2.respawnCount)
        // リスポーン時点ではまだキット未適用(= null)。再装備は respawn の後に走る
        assertNull(p2.slotAtRespawn)
        assertEquals(Material.IRON_SWORD, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `finish resets vitals and clears sidebars`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        val ingameBoard = p1.scoreboard
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(20.0, p1.health)
        assertEquals(20, p1.foodLevel)
        assertEquals(0, p1.fireTicks)
        assertEquals(20.0, p2.health)
        // 終了時に空ボードが新たに割り当てられる
        assertNotSame(ingameBoard, p1.scoreboard)
        assertNotSame(ingameBoard, p2.scoreboard)
    }

    @Test
    fun `participant vanishing during countdown aborts before touching inventory`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(5)

        // 開始直前にプレイヤーが消える → バックアップも装備交換も行わず中断
        env.removePlayer(p2)
        env.tick()
        assertEquals(ArenaState.WAITING, env.view().state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertFalse(p1.hasTeleported())
        assertNull(env.service.arenaIdOf(p1.uuid))
    }
}
