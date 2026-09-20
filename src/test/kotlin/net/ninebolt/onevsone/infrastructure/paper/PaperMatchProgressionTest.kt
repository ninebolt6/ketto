package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.verify
import io.mockk.verifyOrder
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.contains
import net.ninebolt.onevsone.infrastructure.paper.fixtures.lastBroadcast
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.view
import org.bukkit.Location
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
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
        verify(exactly = 1) { p1.sendMessage(contains("に参加しました")) }
        verify(exactly = 1) { p1.sendMessage(contains("あと一人参加するのを待っています")) }

        val p2 = env.player("Bob")
        env.join(p2, arena)
        assertEquals(ArenaState.COUNTDOWN, env.view().state)
        assertEquals(1, env.timers.size)
        assertEquals(10L, env.timers.last().delay)
        assertEquals(20L, env.timers.last().period)
    }

    @Test
    fun `join rejected when already participant or arena disabled or ingame`() {
        val arena = env.newArena()
        val disabled = env.newArena("disabled", enabled = false)
        val p1 = env.player("Alice")
        env.join(p1, disabled)
        verify(exactly = 1) { p1.sendMessage(contains("アリーナが有効になっていません！")) }
        assertNull(env.service.arenaIdOf(p1.uuid))

        env.join(p1, arena)
        env.join(p1, arena)
        verify(exactly = 1) { p1.sendMessage(contains("すでに他のアリーナに参加しています")) }

        val p2 = env.player("Bob")
        env.join(p2, arena)
        val p3 = env.player("Carol")
        env.join(p3, arena)
        verify(exactly = 1) { p3.sendMessage(contains("このアリーナは現在ゲーム中です")) }
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
            verify(exactly = 1) { p1.sendMessage(contains("テレポートまで: ${n}秒")) }
        }
        assertEquals(ArenaState.COUNTDOWN, env.view().state)

        env.tick()
        assertEquals(ArenaState.INGAME, env.view().state)
        verify(exactly = 1) { p1.sendMessage(contains("ゲームスタート！")) }
        verify(exactly = 1) { p1.teleport(any<Location>()) }
        verify(exactly = 1) { p2.teleport(any<Location>()) }
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
        verify(exactly = 1) { p1.sendMessage(contains("ラウンド[")) }
        verify(exactly = 1) { p1.sendMessage(contains("勝者: Alice")) }

        env.tick()
        env.tick()
        (5 downTo 1).forEach { n ->
            env.tick()
            verify(exactly = 1) { p1.sendMessage(contains("開始まで: ${n}秒")) }
        }
        env.tick()
        assertEquals(ArenaState.INGAME, env.view().state)
        // ラウンド開始「スタート！」はゲーム開始「ゲームスタート！」の部分文字列なので prefix 境界で区別する
        verify(exactly = 1) { p1.sendMessage(contains("] スタート！")) }
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
    fun `requiredWins 1 ends on first loss`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(ArenaState.WAITING, env.view().state)
        assertTrue(env.lastBroadcast().contains("Alice"))
    }

    @Test
    fun `mixed winners reach max 5 rounds with requiredWins 3`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        val sequence = listOf(p2, p2, p1, p1, p2)
        sequence.withIndex().forEach { (i, loser) ->
            env.service.defeat(loser.uuid, DefeatCause.FALL)
            if (i < 4) {
                assertEquals(ArenaState.ROUNDCOUNTDOWN, env.view().state)
                env.tick(8)
                assertEquals(ArenaState.INGAME, env.view().state)
            }
        }
        assertEquals(ArenaState.WAITING, env.view().state)
        assertTrue(env.lastBroadcast().contains("Alice"))
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
    }

    @Test
    fun `environmental death without killer awards opponent`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        every { p2.isDead } returns true
        every { p2.killer } returns null
        assertTrue(env.service.defeat(p2.uuid, DefeatCause.DEATH))
        assertEquals(1, env.view().winsOf(p1.uuid))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.view().state)
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
        p1.inventory.setItem(0, null)

        every { p2.isDead } returns true
        env.service.defeat(p2.uuid, DefeatCause.DEATH)
        assertEquals(1, env.oneShots.size)
        env.runOneShots()
        val spigot2 = p2.spigot()
        val inv2 = p2.inventory
        verifyOrder {
            spigot2.respawn()
            inv2.contents = any()
        }
        assertEquals(Material.IRON_SWORD, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `duplicate lose callback does not double score`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        every { p2.isDead } returns true

        assertTrue(env.service.defeat(p2.uuid, DefeatCause.DEATH))
        assertFalse(env.service.defeat(p2.uuid, DefeatCause.DEATH))
        assertFalse(env.service.defeat(p2.uuid, DefeatCause.FALL))
        assertEquals(1, env.view().winsOf(p1.uuid))
        assertNull(env.statsRepo.find(p1.uuid))
        assertNull(env.statsRepo.find(p2.uuid))
    }

    @Test
    fun `void loss in ROUNDCOUNTDOWN scores again after guard release and cancels old timer`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        assertTrue(env.service.defeat(p2.uuid, DefeatCause.FALL))
        val roundTimerId = env.timers.last().taskId
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.view().state)

        assertFalse(env.service.defeat(p2.uuid, DefeatCause.FALL))
        assertEquals(1, env.view().winsOf(p1.uuid))

        env.runOneShots()
        assertTrue(env.service.defeat(p2.uuid, DefeatCause.FALL))
        assertEquals(2, env.view().winsOf(p1.uuid))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.view().state)

        env.timers.first { it.taskId == roundTimerId }.runnable.run()
        assertTrue(env.cancelledTaskIds.contains(roundTimerId))
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

        env.service.defeat(p2.uuid, DefeatCause.FALL)
        verify(exactly = 2) { p1.health = 20.0 }
        verify(exactly = 2) { p1.foodLevel = 20 }
        verify(exactly = 2) { p1.fireTicks = 0 }
        verify(exactly = 2) { p2.health = 20.0 }
        verify(exactly = 2) { p2.scoreboard = any() }
        verify(exactly = 2) { p1.scoreboard = any() }
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
        val inv1 = p1.inventory
        verify(exactly = 0) { inv1.clear() }
        assertNull(env.service.arenaIdOf(p1.uuid))
    }
}
