package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.deathEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.moveEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 死亡・ダメージ・切断・移動イベントのハンドリング。 */
class ArenaListenerCombatTest {

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
    fun `death event keeps inventory clears drops and resolves round`() {
        val (p1, p2) = env.twoPlayerIngame()
        every { p2.isDead } returns true
        val event = env.deathEvent(p2)
        env.listener.onDeath(event)
        verify(exactly = 1) { event.keepInventory = true }
        assertTrue(event.drops.isEmpty())
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.service.matchOf("arena1")!!.winsOf(p1.uuid))
    }

    @Test
    fun `non participant death ignored`() {
        val outsider = env.player("Outsider")
        val event = env.deathEvent(outsider)
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
        val (p1, _) = env.twoPlayerIngame()
        val event = mockk<EntityDamageEvent>(relaxed = true)
        every { event.entity } returns p1
        env.listener.onDamage(event)
        verify(exactly = 0) { event.isCancelled = true }
    }

    @Test
    fun `damage cancelled in round countdown state`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)
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
        val (p1, p2) = env.twoPlayerIngame()
        p1.inventory.setItem(0, null)
        every { p1.isOnline } returns false
        env.players.remove(p1.uniqueId)
        val event = mockk<PlayerQuitEvent>(relaxed = true)
        every { event.player } returns p1
        env.listener.onQuit(event)
        assertEquals(ArenaState.WAITING, env.state())
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
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
        assertEquals(1, env.service.matchOf("arena1")!!.winsOf(p2.uuid))
    }

    @Test
    fun `round countdown freezes xz movement but allows y only`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)

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
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        val w = env.world()
        val event = mockk<PlayerTeleportEvent>(relaxed = true)
        every { event.player } returns p1
        every { event.from } returns Location(w, 0.0, 64.0, 0.0)
        every { event.to } returns Location(w, 5.0, -3.0, 0.0)
        env.listener.onMove(event)
        verify(exactly = 0) { event.to = any() }
    }

    @Test
    fun `void fall in ROUNDCOUNTDOWN scores again after resolving guard released`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        val roundTimerId = env.timers.last().taskId

        env.runOneShots()
        val w = env.world()
        env.listener.onMove(moveEvent(p2, Location(w, 0.0, 1.0, 0.0), Location(w, 0.0, -1.0, 0.0)))
        assertEquals(2, env.service.matchOf("arena1")!!.winsOf(p1.uuid))
        env.timers.first { it.taskId == roundTimerId }.runnable.run()
        assertTrue(env.cancelledTaskIds.contains(roundTimerId))

        env.listener.onMove(moveEvent(p2, Location(w, 0.0, 1.0, 0.0), Location(w, 0.0, -1.0, 0.0)))
        assertEquals(2, env.service.matchOf("arena1")!!.winsOf(p1.uuid))
    }
}
