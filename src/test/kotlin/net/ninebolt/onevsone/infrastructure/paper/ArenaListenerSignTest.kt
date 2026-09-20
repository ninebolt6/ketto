package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.breakEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.contains
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Join 看板クリックと参加イベント経由の復元。 */
class ArenaListenerSignTest {

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
    fun `registered sign join works and unregistered ignored`() {
        val arena = env.newArena()
        env.signRepo.setSign("arena1", WorldPosition("world", 3.0, 64.0, 3.0))
        val p1 = env.player("Alice")

        val unregistered = interact(p1, env.signBlock(9, 64, 9))
        env.signListener.onInteract(unregistered)
        assertNull(env.service.arenaIdOf(p1.uuid))

        val registered = interact(p1, env.signBlock(3, 64, 3))
        env.signListener.onInteract(registered)
        assertEquals(arena, env.service.arenaIdOf(p1.uuid))

        val offhand = interact(env.player("Bob"), env.signBlock(3, 64, 3), EquipmentSlot.OFF_HAND)
        env.signListener.onInteract(offhand)
        assertNull(env.service.arenaIdOf(env.players.values.first { it.name == "Bob" }.uuid))
    }

    @Test
    fun `cannot join sign click shows message`() {
        val arena = env.newArena()
        env.signRepo.setSign("arena1", WorldPosition("world", 3.0, 64.0, 3.0))
        val block = env.signBlock(3, 64, 3)
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)

        val p3 = env.player("Carol")
        env.signListener.onInteract(interact(p3, block))
        verify(exactly = 1) { p3.sendMessage(contains("このアリーナは現在ゲーム中です")) }
    }

    @Test
    fun `non sign block and non right click ignored`() {
        env.signRepo.setSign("arena1", WorldPosition("world", 3.0, 64.0, 3.0))
        env.newArena()
        val p1 = env.player("Alice")

        val block = mockk<Block>(relaxed = true)
        every { block.state } returns mockk<BlockState>(relaxed = true)
        every { block.world } returns env.world()
        env.signListener.onInteract(interact(p1, block))

        val leftClick = interact(p1, env.signBlock(3, 64, 3))
        every { leftClick.action } returns Action.LEFT_CLICK_BLOCK
        env.signListener.onInteract(leftClick)
        assertNull(env.service.arenaIdOf(p1.uuid))
    }

    @Test
    fun `registered sign cannot be broken until unregistered`() {
        env.newArena()
        env.signRepo.setSign("arena1", WorldPosition("world", 3.0, 64.0, 3.0))
        val p1 = env.player("Alice")

        val registered = breakEvent(p1, env.signBlock(3, 64, 3))
        env.signListener.onBreak(registered)
        verify(exactly = 1) { registered.isCancelled = true }

        val unregistered = breakEvent(p1, env.signBlock(9, 64, 9))
        env.signListener.onBreak(unregistered)
        verify(exactly = 0) { unregistered.isCancelled = true }

        env.admin.clearSign("arena1")
        val freed = breakEvent(p1, env.signBlock(3, 64, 3))
        env.signListener.onBreak(freed)
        verify(exactly = 0) { freed.isCancelled = true }
    }

    @Test
    fun `non sign break is ignored`() {
        env.newArena()
        env.signRepo.setSign("arena1", WorldPosition("world", 3.0, 64.0, 3.0))
        val p1 = env.player("Alice")

        val block = mockk<Block>(relaxed = true)
        every { block.state } returns mockk<BlockState>(relaxed = true)
        every { block.world } returns env.world()
        val event = breakEvent(p1, block)
        env.signListener.onBreak(event)
        verify(exactly = 0) { event.isCancelled = true }
    }

    @Test
    fun `join event triggers pending restore`() {
        val (p1, p2) = env.twoPlayerIngame()
        // 試合中に disconnect すると backup は残る
        env.players.remove(p2.uniqueId)
        every { p2.isOnline } returns false
        env.service.abort(ArenaId("arena1"))

        // 再参加時に PlayerJoinEvent 経由で復元(空バックアップ→空インベントリ)
        every { p2.isOnline } returns true
        p2.inventory.setItem(0, null)
        env.players[p2.uniqueId] = p2
        val join = mockk<PlayerJoinEvent>(relaxed = true)
        every { join.player } returns p2
        env.listener.onJoin(join)
        assertNull(p2.inventory.contents[0])
    }
}
