package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.fixtures.arenaId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ArenaApplicationServiceTest {

    @Test
    fun `first join waits and second starts countdown`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        assertEquals(JoinOutput.JoinedWaiting, app.service.join(p1.id, p1.name, arenaId("arena1")))
        assertEquals(ArenaState.Kind.ONEMORE, app.state())
        assertEquals(arenaId("arena1"), app.registry.arenaOf(p1.id))

        val p2 = app.players.add("Bob")
        assertEquals(JoinOutput.JoinedStarting, app.service.join(p2.id, p2.name, arenaId("arena1")))
        assertEquals(ArenaState.Kind.COUNTDOWN, app.state())
        assertEquals(1, app.scheduler.timers.size)
        assertEquals(10L, app.scheduler.timers.last().delay)
        assertEquals(20L, app.scheduler.timers.last().period)
    }

    @Test
    fun `join unknown arena id reports not found`() {
        val app = TestApp()
        app.newArena()
        val p = app.players.add("Alice")
        assertEquals(JoinOutput.NotFound, app.service.join(p.id, p.name, arenaId("ghost")))
        assertNull(app.registry.arenaOf(p.id))
    }

    @Test
    fun `double join across arenas is rejected by index`() {
        val app = TestApp()
        app.newArena("a1")
        app.newArena("a2")
        val p = app.players.add("Alice")
        assertEquals(JoinOutput.JoinedWaiting, app.service.join(p.id, p.name, arenaId("a1")))
        assertEquals(JoinOutput.AlreadyJoined, app.service.join(p.id, p.name, arenaId("a2")))
        assertEquals(arenaId("a1"), app.registry.arenaOf(p.id))
        assertEquals(ArenaState.Kind.WAITING, app.service.matchOf("a2")!!.state.kind)
    }

    @Test
    fun `join rejects disabled or busy arena`() {
        val app = TestApp()
        app.newArena("enabled")
        app.newArena("disabled", enabled = false)
        val p1 = app.players.add("Alice")
        assertEquals(JoinOutput.NotEnabled, app.service.join(p1.id, p1.name, arenaId("disabled")))
        assertNull(app.registry.arenaOf(p1.id))

        app.service.join(p1.id, p1.name, arenaId("enabled"))
        val p2 = app.players.add("Bob")
        app.service.join(p2.id, p2.name, arenaId("enabled"))
        val p3 = app.players.add("Carol")
        assertEquals(JoinOutput.InMatch, app.service.join(p3.id, p3.name, arenaId("enabled")))
        assertNull(app.registry.arenaOf(p3.id))
    }

    @Test
    fun `join without touching inventory before match start`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        val p2 = app.players.add("Bob")
        app.service.join(p1.id, p1.name, arenaId("arena1"))
        app.service.join(p2.id, p2.name, arenaId("arena1"))
        app.scheduler.tick(5)
        assertEquals(ArenaState.Kind.COUNTDOWN, app.state())
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
        assertEquals(ArenaState.Kind.COUNTDOWN, app.state())
        assertEquals(0, app.equipment.backupCalls)

        app.scheduler.tick()
        assertEquals(ArenaState.Kind.INGAME, app.state())
        assertEquals(1, app.equipment.backupCalls)
        assertEquals(2, app.equipment.kitApplies.size)
        assertEquals(1, app.presentation.matchStarts.size)
        assertTrue(p1.events.contains("teleport"))
        assertTrue(p2.events.contains("teleport"))
        assertEquals(2, app.service.matchOf("arena1")!!.participants.size)
        assertNotNull(app.recovery.pending(p1.id))
        assertNotNull(app.recovery.pending(p2.id))
    }

    @Test
    fun `round flow never re-backs-up`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        assertEquals(1, app.equipment.backupCalls)

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, app.state())
        app.scheduler.tick(8)
        assertEquals(ArenaState.Kind.INGAME, app.state())
        assertEquals(1, app.equipment.backupCalls)

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        app.scheduler.tick(8)
        assertEquals(1, app.equipment.backupCalls)
    }

    @Test
    fun `round countdown accepts repeated falls`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, app.state())
        assertEquals(1, app.presentation.roundWins.size)
        assertEquals(Triple(listOf(p1.id, p2.id), 1, "Alice"), app.presentation.roundWins.last())

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(2, app.service.matchOf("arena1")!!.winsOf(p1.id))
    }

    @Test
    fun `final defeat finishes match restores and records stats`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertNull(app.registry.arenaOf(p1.id))
        assertNull(app.registry.arenaOf(p2.id))
        assertEquals(listOf(arenaId("arena1") to "Alice"), app.presentation.champions)
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
        assertEquals(ArenaState.Kind.ONEMORE, app.state())
        assertNull(app.registry.arenaOf(p1.id))
        assertEquals(arenaId("arena1"), app.registry.arenaOf(p2.id))
        assertTrue(app.stats.stats.isEmpty())
        assertTrue(app.equipment.restored.isEmpty())
        assertTrue(app.presentation.champions.isEmpty())
        app.scheduler.tick(6)
        assertEquals(ArenaState.Kind.ONEMORE, app.state())
    }

    @Test
    fun `quit during onemore unregisters without stats`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        app.service.join(p1.id, p1.name, arenaId("arena1"))
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.service.quit(p1.id)
        }
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertNull(app.registry.arenaOf(p1.id))
        assertTrue(app.stats.stats.isEmpty())
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `leave allowed only while onemore`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        assertEquals(LeaveError.NotJoined, app.service.leave(p1.id))
        app.service.join(p1.id, p1.name, arenaId("arena1"))
        val p2 = app.players.add("Bob")
        app.service.join(p2.id, p2.name, arenaId("arena1"))
        assertEquals(LeaveError.NotWaiting, app.service.leave(p1.id))
        assertEquals(arenaId("arena1"), app.registry.arenaOf(p1.id))
    }

    @Test
    fun `leave during onemore unregisters and resets`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        app.service.join(p1.id, p1.name, arenaId("arena1"))
        assertNull(app.service.leave(p1.id))
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertNull(app.registry.arenaOf(p1.id))
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `shutdown aborts matches and restores online pendings`() {
        val app = TestApp()
        val (p1, _) = app.startMatch()
        app.lifecycle.shutdown()
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.registry.resolveArena("arena1")!!.enabled)
        assertNull(app.registry.arenaOf(p1.id))
        assertEquals(2, app.equipment.restored.size)
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
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, app.state())
        app.scheduler.tick()
        assertEquals(ArenaState.Kind.INGAME, app.state())
        assertEquals(1, app.presentation.roundStarts.size)
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
    }

    @Test
    fun `join while a restore is pending but no handle returns in-match`() {
        val app = TestApp()
        app.newArena()
        val p = app.players.add("Alice")
        app.equipment.seedBackup(BackupRef.new(MatchId.new(), p.id, p.name))
        app.recovery.loadPersisted()
        app.players.disconnect(p)

        assertEquals(JoinOutput.InMatch, app.service.join(p.id, p.name, arenaId("arena1")))
        assertNull(app.registry.arenaOf(p.id))
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `restorePending ignores a joined player`() {
        val app = TestApp()
        app.newArena()
        val p = app.players.add("Alice")
        app.service.join(p.id, p.name, arenaId("arena1"))
        app.recovery.backupBeforeMatch(listOf(Participant.new(p.id, p.name)))

        app.service.restorePending(p.id)

        assertTrue(app.equipment.restored.isEmpty())
        assertNotNull(app.recovery.pending(p.id))
    }

    @Test
    fun `restorePending without a ticket does nothing`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.service.restorePending(p.id)
        assertTrue(app.equipment.restored.isEmpty())
        assertTrue(p.events.isEmpty())
    }

    @Test
    fun `restorePending keeps the ticket when no handle exists`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.equipment.seedBackup(BackupRef.new(MatchId.new(), p.id, p.name))
        app.recovery.loadPersisted()
        app.players.disconnect(p)

        app.service.restorePending(p.id)

        assertTrue(app.equipment.restored.isEmpty())
        assertNotNull(app.recovery.pending(p.id))
    }

    @Test
    fun `quit without a match or pending ticket is a no-op`() {
        val app = TestApp()
        app.service.quit(Uuid.random())
        assertTrue(app.equipment.restored.isEmpty())
        assertTrue(app.logger.records.isEmpty())
    }

    @Test
    fun `defeat by a player without a match returns false`() {
        val app = TestApp()
        assertFalse(app.service.defeat(Uuid.random(), DefeatCause.DEATH))
        app.newArena()
        val p = app.players.add("Alice")
        app.service.join(p.id, p.name, arenaId("arena1"))
        assertFalse(app.service.defeat(p.id, DefeatCause.DEATH))
    }

    @Test
    fun `forfeit during roundcountdown ends match`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.service.defeat(p2.id, DefeatCause.FALL)
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, app.state())
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.service.quit(p1.id)
        }
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertEquals(1, app.stats.stats[p2.id]?.wins)
        assertEquals(1, app.stats.stats[p1.id]?.losses)
    }
}
