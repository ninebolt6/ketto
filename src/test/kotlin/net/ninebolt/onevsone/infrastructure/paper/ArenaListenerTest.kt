package net.ninebolt.onevsone.infrastructure.paper

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
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
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

    private fun deathEvent(player: Player): PlayerDeathEvent {
        val event = mock(PlayerDeathEvent::class.java)
        val drops = mutableListOf(env.item(Material.STONE))
        `when`(event.entity).thenReturn(player)
        `when`(event.drops).thenReturn(drops)
        return event
    }

    private fun moveEvent(player: Player, from: Location, to: Location): PlayerMoveEvent {
        val event = mock(PlayerMoveEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.from).thenReturn(from)
        `when`(event.to).thenReturn(to)
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
        `when`(p2.isDead).thenReturn(true)
        val event = deathEvent(p2)
        env.listener.onDeath(event)
        verify(event).setKeepInventory(true)
        assertTrue(event.drops.isEmpty())
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.service.matchView("arena1")!!.winsOf(p1.uniqueId))
    }

    @Test
    fun `non participant death ignored`() {
        val outsider = env.player("Outsider")
        val event = deathEvent(outsider)
        env.listener.onDeath(event)
        verify(event, never()).setKeepInventory(true)
    }

    @Test
    fun `non player damage ignored`() {
        val event = mock(EntityDamageEvent::class.java)
        `when`(event.entity).thenReturn(mock(Entity::class.java))
        env.listener.onDamage(event)
        verify(event, never()).isCancelled = true
    }

    @Test
    fun `damage not cancelled in INGAME`() {
        val (p1, _) = twoPlayerIngame()
        val event = mock(EntityDamageEvent::class.java)
        `when`(event.entity).thenReturn(p1)
        env.listener.onDamage(event)
        verify(event, never()).isCancelled = true
    }

    @Test
    fun `damage cancelled in round countdown state`() {
        val (p1, p2) = twoPlayerIngame()
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        val event = mock(EntityDamageEvent::class.java)
        `when`(event.entity).thenReturn(p1)
        env.listener.onDamage(event)
        verify(event).isCancelled = true
    }

    @Test
    fun `quit of outsider does not touch inventory`() {
        val outsider = env.player("Outsider")
        val event = mock(PlayerQuitEvent::class.java)
        `when`(event.player).thenReturn(outsider)
        env.listener.onQuit(event)
        verify(outsider.inventory, never()).clear()
    }

    @Test
    fun `quit of participant resolves through quitting scope`() {
        val (p1, p2) = twoPlayerIngame()
        p1.inventory.setItem(0, null)
        `when`(p1.isOnline).thenReturn(false)
        env.players.remove(p1.uniqueId)
        val event = mock(PlayerQuitEvent::class.java)
        `when`(event.player).thenReturn(p1)
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
        assertEquals(1, env.service.matchView("arena1")!!.winsOf(p2.uniqueId))
    }

    @Test
    fun `round countdown freezes xz movement but allows y only`() {
        val (p1, p2) = twoPlayerIngame()
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)

        val w = env.world()
        val horizontal = moveEvent(p1, Location(w, 0.0, 64.0, 0.0), Location(w, 1.0, 64.0, 0.0))
        env.listener.onMove(horizontal)
        verify(horizontal).setTo(horizontal.from)

        val vertical = moveEvent(p1, Location(w, 0.0, 64.0, 0.0), Location(w, 0.0, 65.0, 0.0))
        env.listener.onMove(vertical)
        verify(vertical, never()).setTo(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `teleport events are excluded from move handling`() {
        val (p1, p2) = twoPlayerIngame()
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        val w = env.world()
        val event = mock(PlayerTeleportEvent::class.java)
        `when`(event.player).thenReturn(p1)
        `when`(event.from).thenReturn(Location(w, 0.0, 64.0, 0.0))
        `when`(event.to).thenReturn(Location(w, 5.0, -3.0, 0.0))
        env.listener.onMove(event)
        verify(event, never()).setTo(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `break cancelled only in ingame and roundcountdown`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val event = mock(BlockBreakEvent::class.java)
        `when`(event.player).thenReturn(p1)
        env.listener.onBreak(event)
        verify(event, never()).isCancelled = true

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.tick(6)
        env.listener.onBreak(event)
        verify(event).isCancelled = true
    }

    @Test
    fun `commands blocked except in ONEMORE`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val event = mock(PlayerCommandPreprocessEvent::class.java)
        `when`(event.player).thenReturn(p1)

        env.listener.onCommand(event)
        verify(event, never()).isCancelled = true

        env.join(p1, arena)
        env.listener.onCommand(event)
        verify(event, never()).isCancelled = true

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.listener.onCommand(event)
        verify(event).isCancelled = true
        verify(p1).sendMessage(contains("コマンドは使用できません！"))
    }

    private fun signBlock(x: Int, y: Int, z: Int): Block {
        val block = mock(Block::class.java)
        val sign = mock(Sign::class.java)
        val side = mock(org.bukkit.block.sign.SignSide::class.java)
        val w = env.world()
        `when`(sign.getSide(org.bukkit.block.sign.Side.FRONT)).thenReturn(side)
        `when`(block.state).thenReturn(sign)
        `when`(block.world).thenReturn(w)
        `when`(block.x).thenReturn(x)
        `when`(block.y).thenReturn(y)
        `when`(block.z).thenReturn(z)
        `when`(w.getBlockAt(x, y, z)).thenReturn(block)
        return block
    }

    private fun interact(player: Player, block: Block, hand: EquipmentSlot = EquipmentSlot.HAND): PlayerInteractEvent {
        val event = mock(PlayerInteractEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.action).thenReturn(Action.RIGHT_CLICK_BLOCK)
        `when`(event.hand).thenReturn(hand)
        `when`(event.clickedBlock).thenReturn(block)
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
        verify(p3).sendMessage(contains("このアリーナは現在ゲーム中です"))
    }

    @Test
    fun `non sign block and non right click ignored`() {
        env.arenaRepo.setSign("arena1", WorldPosition("world", 3.0, 64.0, 3.0))
        env.newArena()
        val p1 = env.player("Alice")

        val block = mock(Block::class.java)
        `when`(block.state).thenReturn(mock(org.bukkit.block.BlockState::class.java))
        `when`(block.world).thenReturn(env.world())
        env.listener.onInteract(interact(p1, block))

        val leftClick = interact(p1, signBlock(3, 64, 3))
        `when`(leftClick.action).thenReturn(Action.LEFT_CLICK_BLOCK)
        env.listener.onInteract(leftClick)
        assertNull(env.service.arenaIdOf(p1.uniqueId))
    }

    @Test
    fun `restriction matrix follows live state transitions`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)

        fun assertState(damageCancelled: Boolean, breakCancelled: Boolean, commandBlocked: Boolean) {
            val damage = mock(EntityDamageEvent::class.java)
            `when`(damage.entity).thenReturn(p1)
            env.listener.onDamage(damage)
            if (damageCancelled) verify(damage).isCancelled = true else verify(damage, never()).isCancelled = true

            val breaking = mock(BlockBreakEvent::class.java)
            `when`(breaking.player).thenReturn(p1)
            env.listener.onBreak(breaking)
            if (breakCancelled) verify(breaking).isCancelled = true else verify(breaking, never()).isCancelled = true

            val command = mock(PlayerCommandPreprocessEvent::class.java)
            `when`(command.player).thenReturn(p1)
            env.listener.onCommand(command)
            if (commandBlocked) verify(command).isCancelled = true else verify(command, never()).isCancelled = true
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
        assertEquals(2, env.service.matchView("arena1")!!.winsOf(p1.uniqueId))
        env.timers.first { it.taskId == roundTimerId }.runnable.run()
        assertTrue(env.cancelledTaskIds.contains(roundTimerId))

        env.listener.onMove(moveEvent(p2, Location(w, 0.0, 1.0, 0.0), Location(w, 0.0, -1.0, 0.0)))
        assertEquals(2, env.service.matchView("arena1")!!.winsOf(p1.uniqueId))
    }

    @Test
    fun `join event triggers pending restore`() {
        val (p1, p2) = twoPlayerIngame()
        // 試合中に disconnect すると backup は残る
        env.players.remove(p2.uniqueId)
        `when`(p2.isOnline).thenReturn(false)
        env.service.abort(ArenaId("arena1"))

        // 再参加時に PlayerJoinEvent 経由で復元(空バックアップ→ロビーアイテム)
        `when`(p2.isOnline).thenReturn(true)
        p2.inventory.setItem(0, null)
        env.players[p2.uniqueId] = p2
        val join = mock(org.bukkit.event.player.PlayerJoinEvent::class.java)
        `when`(join.player).thenReturn(p2)
        env.listener.onJoin(join)
        assertEquals(Material.COMPASS, p2.inventory.contents[0]?.type)
    }
}
