package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaApplicationServiceTest {

    @Test
    fun `first join waits and second starts countdown`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        assertEquals(JoinOutput.JoinedWaiting, app.service.join(p1.id, p1.name, Arena.Id.new("arena1")))
        assertEquals(ArenaState.ONEMORE, app.state())
        assertEquals(Arena.Id.new("arena1"), app.service.arenaIdOf(p1.id))

        val p2 = app.players.add("Bob")
        assertEquals(JoinOutput.JoinedStarting, app.service.join(p2.id, p2.name, Arena.Id.new("arena1")))
        assertEquals(ArenaState.COUNTDOWN, app.state())
        assertEquals(1, app.scheduler.timers.size)
        assertEquals(10L, app.scheduler.timers.last().delay)
        assertEquals(20L, app.scheduler.timers.last().period)
    }

    @Test
    fun `double join across arenas is rejected by index`() {
        val app = TestApp()
        app.newArena("a1")
        app.newArena("a2")
        val p = app.players.add("Alice")
        assertEquals(JoinOutput.JoinedWaiting, app.service.join(p.id, p.name, Arena.Id.new("a1")))
        assertEquals(JoinOutput.AlreadyJoined, app.service.join(p.id, p.name, Arena.Id.new("a2")))
        assertEquals(Arena.Id.new("a1"), app.service.arenaIdOf(p.id))
        assertEquals(ArenaState.WAITING, app.service.matchOf("a2")!!.state)
    }

    @Test
    fun `join rejects disabled or busy arena`() {
        val app = TestApp()
        app.newArena("enabled")
        app.newArena("disabled", enabled = false)
        val p1 = app.players.add("Alice")
        assertEquals(JoinOutput.NotEnabled, app.service.join(p1.id, p1.name, Arena.Id.new("disabled")))
        assertNull(app.service.arenaIdOf(p1.id))

        app.service.join(p1.id, p1.name, Arena.Id.new("enabled"))
        val p2 = app.players.add("Bob")
        app.service.join(p2.id, p2.name, Arena.Id.new("enabled"))
        val p3 = app.players.add("Carol")
        assertEquals(JoinOutput.InMatch, app.service.join(p3.id, p3.name, Arena.Id.new("enabled")))
        assertNull(app.service.arenaIdOf(p3.id))
    }

    @Test
    fun `join without touching inventory before match start`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        val p2 = app.players.add("Bob")
        app.service.join(p1.id, p1.name, Arena.Id.new("arena1"))
        app.service.join(p2.id, p2.name, Arena.Id.new("arena1"))
        app.scheduler.tick(5)
        assertEquals(ArenaState.COUNTDOWN, app.state())
        assertEquals(0, app.equipment.backupCalls)
        assertTrue(app.equipment.kitApplies.isEmpty())
    }

    @Test
    fun `initial countdown ticks then batch backup then equip and INGAME`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        (5 downTo 1).forEach { n ->
            app.scheduler.tick()
            assertEquals(n, app.presentation.countdownTicks.last().seconds)
        }
        assertEquals(ArenaState.COUNTDOWN, app.state())
        assertEquals(0, app.equipment.backupCalls)

        app.scheduler.tick()
        assertEquals(ArenaState.INGAME, app.state())
        assertEquals(1, app.equipment.backupCalls)
        assertEquals(2, app.equipment.kitApplies.size)
        assertEquals(1, app.presentation.matchStarts.size)
        assertTrue(p1.events.contains("teleport"))
        assertTrue(p2.events.contains("teleport"))
        assertEquals(2, app.matchState.registrations.size)
        assertNotNull(app.service.pendingRestore(p1.id))
        assertNotNull(app.service.pendingRestore(p2.id))
    }

    @Test
    fun `round flow never re-backs-up`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        assertEquals(1, app.equipment.backupCalls)

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, app.state())
        app.scheduler.tick(8)
        assertEquals(ArenaState.INGAME, app.state())
        assertEquals(1, app.equipment.backupCalls)

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        app.scheduler.tick(8)
        assertEquals(1, app.equipment.backupCalls)
    }

    @Test
    fun `round countdown timing and release resolution`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, app.state())
        assertEquals(1, app.presentation.roundWins.size)
        assertEquals(Triple(listOf(p1.id, p2.id), 1, "Alice"), app.presentation.roundWins.last())

        assertFalse(app.service.defeat(p2.id, DefeatCause.FALL))
        app.scheduler.runOneShots()
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(2, app.service.matchOf("arena1")!!.winsOf(p1.id))
    }

    @Test
    fun `final defeat finishes match restores and records stats`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertNull(app.service.arenaIdOf(p2.id))
        assertEquals(listOf(Arena.Id.new("arena1") to "Alice"), app.presentation.champions)
        assertEquals(1, app.stats.stats[p1.id]?.wins)
        assertEquals(1, app.stats.stats[p2.id]?.losses)
        assertEquals(2, app.equipment.restored.size)
        assertEquals(2, app.equipment.acknowledged.size)
        assertTrue(app.equipment.storedBackups.isEmpty())
        assertEquals(1, app.presentation.fireworks.size)
    }

    @Test
    fun `quit during countdown unregisters only and keeps opponent waiting`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.service.quit(p1.id)
        }
        assertEquals(ArenaState.ONEMORE, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertEquals(Arena.Id.new("arena1"), app.service.arenaIdOf(p2.id))
        assertFalse(app.matchState.registrations.containsKey(p1.id))
        assertTrue(app.matchState.registrations.containsKey(p2.id))
        assertTrue(app.stats.stats.isEmpty())
        assertTrue(app.equipment.restored.isEmpty())
        assertTrue(app.presentation.champions.isEmpty())
        app.scheduler.tick(6)
        assertEquals(ArenaState.ONEMORE, app.state())
    }

    @Test
    fun `quit during onemore unregisters without stats`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        app.service.join(p1.id, p1.name, Arena.Id.new("arena1"))
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.service.quit(p1.id)
        }
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertFalse(app.matchState.registrations.containsKey(p1.id))
        assertTrue(app.stats.stats.isEmpty())
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `leave allowed only while onemore`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        assertEquals(LeaveError.NotJoined, app.service.leave(p1.id))
        app.service.join(p1.id, p1.name, Arena.Id.new("arena1"))
        val p2 = app.players.add("Bob")
        app.service.join(p2.id, p2.name, Arena.Id.new("arena1"))
        assertEquals(LeaveError.NotWaiting, app.service.leave(p1.id))
        assertEquals(Arena.Id.new("arena1"), app.service.arenaIdOf(p1.id))
    }

    @Test
    fun `leave during onemore unregisters and resets`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        app.service.join(p1.id, p1.name, Arena.Id.new("arena1"))
        assertNull(app.service.leave(p1.id))
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertFalse(app.matchState.registrations.containsKey(p1.id))
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `shutdown aborts matches and restores online pendings`() {
        val app = TestApp()
        val (p1, _) = app.startMatch()
        app.lifecycle.shutdown()
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.matchState.registrations.isEmpty())
    }

    @Test
    fun `round countdown restores INGAME and releases resolution at completion`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        app.service.defeat(p2.id, DefeatCause.FALL)
        app.scheduler.tick()
        app.scheduler.tick()
        (5 downTo 1).forEach { n ->
            app.scheduler.tick()
            assertEquals(n, app.presentation.roundCountdownTicks.last().seconds)
        }
        assertEquals(ArenaState.ROUNDCOUNTDOWN, app.state())
        app.scheduler.tick()
        assertEquals(ArenaState.INGAME, app.state())
        assertEquals(1, app.presentation.roundStarts.size)
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
    }

    @Test
    fun `forfeit during roundcountdown ends match`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.service.defeat(p2.id, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, app.state())
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.service.quit(p1.id)
        }
        assertEquals(ArenaState.WAITING, app.state())
        assertEquals(1, app.stats.stats[p2.id]?.wins)
        assertEquals(1, app.stats.stats[p1.id]?.losses)
    }
}
