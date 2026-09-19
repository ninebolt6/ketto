package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.WorldPosition
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ArenaListenerTest {

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

    private fun MockKMatcherScope.contains(part: String): String = match { it.contains(part) }

    private fun deathEvent(player: Player): PlayerDeathEvent {
        val event = mockk<PlayerDeathEvent>(relaxed = true)
        val drops = mutableListOf(env.item(Material.STONE))
        every { event.entity } returns player
        every { event.drops } returns drops
        return event
    }

    private fun moveEvent(player: Player, from: Location, to: Location): PlayerMoveEvent {
        val event = mockk<PlayerMoveEvent>(relaxed = true)
        every { event.player } returns player
        every { event.from } returns from
        every { event.to } returns to
        return event
    }

    private fun twoPlayerIngame(): Pair<Player, Player> {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        return p1 to p2
    }

    @Test
    fun `death event keeps inventory clears drops and resolves round`() {
        val (p1, p2) = twoPlayerIngame()
        every { p2.isDead } returns true
        val event = deathEvent(p2)
        env.listener.onDeath(event)
        verify(exactly = 1) { event.keepInventory = true }
        assertTrue(event.drops.isEmpty())
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.service.matchOf("arena1")!!.winsOf(p1.uniqueId))
    }

    @Test
    fun `non participant death ignored`() {
        val outsider = env.player("Outsider")
        val event = deathEvent(outsider)
        env.listener.onDeath(event)
        verify(exactly = 0) { event.keepInventory = true }
    }

    @Test
    fun `non player damage ignored`() {
        val event = mockk<EntityDamageEvent>(relaxed = true)
        every { event.entity } returns mockk<Entity>(relaxed = true)
        env.listener.onDamage(event)
        verify(exactly = 0) { event.isCancelled = true }
    }

    @Test
    fun `damage not cancelled in INGAME`() {
        val (p1, _) = twoPlayerIngame()
        val event = mockk<EntityDamageEvent>(relaxed = true)
        every { event.entity } returns p1
        env.listener.onDamage(event)
        verify(exactly = 0) { event.isCancelled = true }
    }

    @Test
    fun `damage cancelled in round countdown state`() {
        val (p1, p2) = twoPlayerIngame()
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        val event = mockk<EntityDamageEvent>(relaxed = true)
        every { event.entity } returns p1
        env.listener.onDamage(event)
        verify(exactly = 1) { event.isCancelled = true }
    }

    @Test
    fun `quit of outsider does not touch inventory`() {
        val outsider = env.player("Outsider")
        val event = mockk<PlayerQuitEvent>(relaxed = true)
        every { event.player } returns outsider
        env.listener.onQuit(event)
        val inv = outsider.inventory
        verify(exactly = 0) { inv.clear() }
    }

    @Test
    fun `quit of participant resolves through quitting scope`() {
        val (p1, p2) = twoPlayerIngame()
        p1.inventory.setItem(0, null)
        every { p1.isOnline } returns false
        env.players.remove(p1.uniqueId)
        val event = mockk<PlayerQuitEvent>(relaxed = true)
        every { event.player } returns p1
        env.listener.onQuit(event)
        assertEquals(ArenaState.WAITING, env.state())
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.wins)
    }

    @Test
    fun `void fall below zero resolves only when ingame with two players`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        assertEquals(ArenaState.ONEMORE, env.state())

        val w = env.world()
        val event = moveEvent(p1, Location(w, 0.0, -1.0, 0.0), Location(w, 0.0, -5.0, 0.0))
        env.listener.onMove(event)
        assertEquals(ArenaState.ONEMORE, env.state())

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.tick(6)

        val fall = moveEvent(p1, Location(w, 0.0, 1.0, 0.0), Location(w, 0.0, -1.0, 0.0))
        env.listener.onMove(fall)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.service.matchOf("arena1")!!.winsOf(p2.uniqueId))
    }

    @Test
    fun `round countdown freezes xz movement but allows y only`() {
        val (p1, p2) = twoPlayerIngame()
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)

        val w = env.world()
        val from = Location(w, 0.0, 64.0, 0.0)
        val horizontal = moveEvent(p1, from, Location(w, 1.0, 64.0, 0.0))
        env.listener.onMove(horizontal)
        verify(exactly = 1) { horizontal.to = from }

        val vertical = moveEvent(p1, Location(w, 0.0, 64.0, 0.0), Location(w, 0.0, 65.0, 0.0))
        env.listener.onMove(vertical)
        verify(exactly = 0) { vertical.to = any() }
    }

    @Test
    fun `teleport events are excluded from move handling`() {
        val (p1, p2) = twoPlayerIngame()
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        val w = env.world()
        val event = mockk<PlayerTeleportEvent>(relaxed = true)
        every { event.player } returns p1
        every { event.from } returns Location(w, 0.0, 64.0, 0.0)
        every { event.to } returns Location(w, 5.0, -3.0, 0.0)
        env.listener.onMove(event)
        verify(exactly = 0) { event.to = any() }
    }

    @Test
    fun `break cancelled only in ingame and roundcountdown`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val event = mockk<BlockBreakEvent>(relaxed = true)
        every { event.player } returns p1
        env.listener.onBreak(event)
        verify(exactly = 0) { event.isCancelled = true }

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.tick(6)
        env.listener.onBreak(event)
        verify(exactly = 1) { event.isCancelled = true }
    }

    @Test
    fun `commands blocked except in ONEMORE`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val event = mockk<PlayerCommandPreprocessEvent>(relaxed = true)
        every { event.player } returns p1

        env.listener.onCommand(event)
        verify(exactly = 0) { event.isCancelled = true }

        env.join(p1, arena)
        env.listener.onCommand(event)
        verify(exactly = 0) { event.isCancelled = true }

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.listener.onCommand(event)
        verify(exactly = 1) { event.isCancelled = true }
        verify(exactly = 1) { p1.sendMessage(contains("コマンドは使用できません！")) }
    }

    private fun signBlock(x: Int, y: Int, z: Int): Block {
        val block = mockk<Block>(relaxed = true)
        val sign = mockk<Sign>(relaxed = true)
        val side = mockk<org.bukkit.block.sign.SignSide>(relaxed = true)
        val w = env.world()
        every { sign.getSide(org.bukkit.block.sign.Side.FRONT) } returns side
        every { block.state } returns sign
        every { block.world } returns w
        every { block.x } returns x
        every { block.y } returns y
        every { block.z } returns z
        every { w.getBlockAt(x, y, z) } returns block
        return block
    }

    private fun interact(player: Player, block: Block, hand: EquipmentSlot = EquipmentSlot.HAND): PlayerInteractEvent {
        val event = mockk<PlayerInteractEvent>(relaxed = true)
        every { event.player } returns player
        every { event.action } returns Action.RIGHT_CLICK_BLOCK
        every { event.hand } returns hand
        every { event.clickedBlock } returns block
        return event
    }

    @Test
    fun `registered sign join works and unregistered ignored`() {
        val arena = env.newArena()
        env.arenaRepo.setSign("arena1", WorldPosition("world", 3.0, 64.0, 3.0))
        val p1 = env.player("Alice")

        val unregistered = interact(p1, signBlock(9, 64, 9))
        env.listener.onInteract(unregistered)
        assertNull(env.service.arenaIdOf(p1.uniqueId))

        val registered = interact(p1, signBlock(3, 64, 3))
        env.listener.onInteract(registered)
        assertEquals(arena, env.service.arenaIdOf(p1.uniqueId))

        val offhand = interact(env.player("Bob"), signBlock(3, 64, 3), EquipmentSlot.OFF_HAND)
        env.listener.onInteract(offhand)
        assertNull(env.service.arenaIdOf(env.players.values.first { it.name == "Bob" }.uniqueId))
    }

    @Test
    fun `cannot join sign click shows message`() {
        val arena = env.newArena()
        env.arenaRepo.setSign("arena1", WorldPosition("world", 3.0, 64.0, 3.0))
        val block = signBlock(3, 64, 3)
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)

        val p3 = env.player("Carol")
        env.listener.onInteract(interact(p3, block))
        verify(exactly = 1) { p3.sendMessage(contains("このアリーナは現在ゲーム中です")) }
    }

    @Test
    fun `non sign block and non right click ignored`() {
        env.arenaRepo.setSign("arena1", WorldPosition("world", 3.0, 64.0, 3.0))
        env.newArena()
        val p1 = env.player("Alice")

        val block = mockk<Block>(relaxed = true)
        every { block.state } returns mockk<org.bukkit.block.BlockState>(relaxed = true)
        every { block.world } returns env.world()
        env.listener.onInteract(interact(p1, block))

        val leftClick = interact(p1, signBlock(3, 64, 3))
        every { leftClick.action } returns Action.LEFT_CLICK_BLOCK
        env.listener.onInteract(leftClick)
        assertNull(env.service.arenaIdOf(p1.uniqueId))
    }

    @Test
    fun `restriction matrix follows live state transitions`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)

        fun assertState(damageCancelled: Boolean, breakCancelled: Boolean, commandBlocked: Boolean) {
            val damage = mockk<EntityDamageEvent>(relaxed = true)
            every { damage.entity } returns p1
            env.listener.onDamage(damage)
            if (damageCancelled) verify(exactly = 1) { damage.isCancelled = true } else verify(exactly = 0) { damage.isCancelled = true }

            val breaking = mockk<BlockBreakEvent>(relaxed = true)
            every { breaking.player } returns p1
            env.listener.onBreak(breaking)
            if (breakCancelled) verify(exactly = 1) { breaking.isCancelled = true } else verify(exactly = 0) { breaking.isCancelled = true }

            val command = mockk<PlayerCommandPreprocessEvent>(relaxed = true)
            every { command.player } returns p1
            env.listener.onCommand(command)
            if (commandBlocked) verify(exactly = 1) { command.isCancelled = true } else verify(exactly = 0) { command.isCancelled = true }
        }

        assertState(damageCancelled = false, breakCancelled = false, commandBlocked = false) // ONEMORE

        val p2 = env.player("Bob")
        env.join(p2, arena)
        assertState(damageCancelled = false, breakCancelled = false, commandBlocked = true) // COUNTDOWN

        env.tick(6)
        assertState(damageCancelled = false, breakCancelled = true, commandBlocked = true) // INGAME

        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertState(damageCancelled = true, breakCancelled = true, commandBlocked = true) // ROUNDCOUNTDOWN
    }

    @Test
    fun `void fall in ROUNDCOUNTDOWN scores again after resolving guard released`() {
        val (p1, p2) = twoPlayerIngame()
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        val roundTimerId = env.timers.last().taskId

        env.runOneShots()
        val w = env.world()
        env.listener.onMove(moveEvent(p2, Location(w, 0.0, 1.0, 0.0), Location(w, 0.0, -1.0, 0.0)))
        assertEquals(2, env.service.matchOf("arena1")!!.winsOf(p1.uniqueId))
        env.timers.first { it.taskId == roundTimerId }.runnable.run()
        assertTrue(env.cancelledTaskIds.contains(roundTimerId))

        env.listener.onMove(moveEvent(p2, Location(w, 0.0, 1.0, 0.0), Location(w, 0.0, -1.0, 0.0)))
        assertEquals(2, env.service.matchOf("arena1")!!.winsOf(p1.uniqueId))
    }

    @Test
    fun `join event triggers pending restore`() {
        val (p1, p2) = twoPlayerIngame()
        // 試合中に disconnect すると backup は残る
        env.players.remove(p2.uniqueId)
        every { p2.isOnline } returns false
        env.service.abort(ArenaId("arena1"))

        // 再参加時に PlayerJoinEvent 経由で復元(空バックアップ→ロビーアイテム)
        every { p2.isOnline } returns true
        p2.inventory.setItem(0, null)
        env.players[p2.uniqueId] = p2
        val join = mockk<org.bukkit.event.player.PlayerJoinEvent>(relaxed = true)
        every { join.player } returns p2
        env.listener.onJoin(join)
        assertEquals(Material.COMPASS, p2.inventory.contents[0]?.type)
    }
}
