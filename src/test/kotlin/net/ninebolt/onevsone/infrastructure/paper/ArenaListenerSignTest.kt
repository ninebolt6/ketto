package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.Component
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.breakEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        env.signRepo.setSign("arena1", WorldPosition.new("world", 3.0, 64.0, 3.0))
        val p1 = env.player("Alice")

        val unregistered = interact(p1, env.signBlock(9, 64, 9))
        env.signListener.onInteract(unregistered)
        assertNull(env.service.arenaIdOf(p1.uuid))

        val registered = interact(p1, env.signBlock(3, 64, 3))
        env.signListener.onInteract(registered)
        assertEquals(arena, env.service.arenaIdOf(p1.uuid))
        // バニラの看板編集画面を開かせない
        assertTrue(registered.isCancelled)
        assertFalse(unregistered.isCancelled)

        val bob = env.player("Bob")
        val offhand = interact(bob, env.signBlock(3, 64, 3), EquipmentSlot.OFF_HAND)
        env.signListener.onInteract(offhand)
        assertNull(env.service.arenaIdOf(bob.uuid))
    }

    @Test
    fun `cannot join sign click shows message`() {
        val arena = env.newArena()
        env.signRepo.setSign("arena1", WorldPosition.new("world", 3.0, 64.0, 3.0))
        val block = env.signBlock(3, 64, 3)
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)

        val p3 = env.player("Carol")
        env.signListener.onInteract(interact(p3, block))
        assertTrue(p3.drainMessages().any { it.contains("このアリーナは現在ゲーム中です") })
    }

    @Test
    fun `non sign block and non right click ignored`() {
        env.signRepo.setSign("arena1", WorldPosition.new("world", 3.0, 64.0, 3.0))
        env.newArena()
        val p1 = env.player("Alice")

        env.signListener.onInteract(interact(p1, env.plainBlock(3, 64, 3)))

        val leftClick = interact(p1, env.signBlock(3, 64, 3), action = Action.LEFT_CLICK_BLOCK)
        env.signListener.onInteract(leftClick)
        assertNull(env.service.arenaIdOf(p1.uuid))
    }

    @Test
    fun `registered sign cannot be broken until unregistered`() {
        env.newArena()
        env.signRepo.setSign("arena1", WorldPosition.new("world", 3.0, 64.0, 3.0))
        val p1 = env.player("Alice")

        val registered = breakEvent(p1, env.signBlock(3, 64, 3))
        env.signListener.onBreak(registered)
        assertTrue(registered.isCancelled)

        val unregistered = breakEvent(p1, env.signBlock(9, 64, 9))
        env.signListener.onBreak(unregistered)
        assertFalse(unregistered.isCancelled)

        env.admin.clearSign("arena1")
        val freed = breakEvent(p1, env.signBlock(3, 64, 3))
        env.signListener.onBreak(freed)
        assertFalse(freed.isCancelled)
    }

    @Test
    fun `non sign break is ignored`() {
        env.newArena()
        env.signRepo.setSign("arena1", WorldPosition.new("world", 3.0, 64.0, 3.0))
        val p1 = env.player("Alice")

        val event = breakEvent(p1, env.plainBlock(3, 64, 3))
        env.signListener.onBreak(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `join event triggers pending restore`() {
        val (p1, p2) = env.twoPlayerIngame()
        // 試合中に disconnect すると backup は残る
        env.removePlayer(p2)
        env.service.abort(Arena.Id.new("arena1"))

        // 再参加時に PlayerJoinEvent 経由で復元(空バックアップ→空インベントリ)
        p2.inventory.setItem(0, null)
        p2.reconnect()
        env.listener.onJoin(PlayerJoinEvent(p2, Component.empty()))
        assertNull(p2.inventory.contents[0])
    }
}
