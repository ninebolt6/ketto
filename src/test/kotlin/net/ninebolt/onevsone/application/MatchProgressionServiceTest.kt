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
import kotlin.uuid.Uuid

class MatchProgressionServiceTest {

    @Test
    fun `request respawn revives a dead handle on the next tick`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        p.dead = true
        app.service.requestRespawn(p.id)
        app.scheduler.runOneShots()
        assertTrue("respawn" in p.events)
        assertFalse(p.dead)
    }

    @Test
    fun `request respawn leaves an alive handle untouched`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.service.requestRespawn(p.id)
        app.scheduler.runOneShots()
        assertTrue(p.events.isEmpty())
    }

    @Test
    fun `request respawn without a handle does nothing`() {
        val app = TestApp()
        app.service.requestRespawn(Uuid.random())
        app.scheduler.runOneShots()
        assertTrue(app.logger.records.isEmpty())
    }

    @Test
    fun `abort on an unknown arena is a no-op`() {
        val app = TestApp()
        app.service.abort(Arena.Id.new("nope"))
        assertTrue(app.scheduler.timers.isEmpty())
        assertTrue(app.presentation.signUpdates.isEmpty())
        assertTrue(app.logger.records.isEmpty())
    }

    @Test
    fun `round win with the winner offline skips the winner rearm and teleport`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.players.disconnect(p1)

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))

        assertEquals(1, app.equipment.kitApplies.count { it.second == p1.id })
        assertEquals(1, p1.teleports.size)
        assertEquals(1, app.presentation.roundEndSounds.size)
        assertEquals(2, app.equipment.kitApplies.count { it.second == p2.id })

        app.scheduler.tick()
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
    }

    @Test
    fun `round win with the loser offline releases the resolution without a sound`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        app.players.disconnect(p2)

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))

        assertTrue(app.presentation.roundEndSounds.isEmpty())
        assertFalse(app.service.matchOf("arena1")!!.resolving)
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, app.state())

        app.scheduler.tick()
        assertEquals(ArenaState.Kind.WAITING, app.state())
    }

    @Test
    fun `final defeat by death restores a loser who is already respawned`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()

        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        app.scheduler.runOneShots()

        assertTrue("respawn" !in p2.events)
        assertEquals(listOf("vitals", "restore"), p2.events.takeLast(2))
        assertTrue(app.equipment.storedBackups.isEmpty())
    }

    @Test
    fun `a winner who rejoins before the deferred tick is not restored twice`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        p1.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))

        p1.dead = false
        assertEquals(JoinOutput.JoinedWaiting, app.service.join(p1.id, p1.name, Arena.Id.new("arena1")))
        assertEquals(1, app.equipment.restored.count { it.playerId == p1.id })

        app.scheduler.runOneShots()
        assertEquals(1, app.equipment.restored.count { it.playerId == p1.id })
        assertTrue("vitals" !in p1.events)
        assertTrue(app.presentation.fireworks.isEmpty())
        assertEquals(ArenaState.Kind.ONEMORE, app.state())
    }

    @Test
    fun `quit by an offline loser finishes the match and keeps their ticket`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.players.disconnect(p2)

        app.service.quit(p2.id)

        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.equipment.restored.any { it.playerId == p1.id })
        assertTrue(app.equipment.restored.none { it.playerId == p2.id })
        assertNotNull(app.service.pendingRestore(p2.id))
        assertEquals(1, app.stats.stats[p1.id]?.wins)
        assertEquals(1, app.stats.stats[p2.id]?.losses)
    }

    @Test
    fun `the countdown waits while the first player is dead`() {
        val app = TestApp()
        val (p1, _) = app.joinedTwo()
        app.scheduler.tick(5)
        p1.dead = true
        app.scheduler.tick()
        assertEquals(ArenaState.Kind.COUNTDOWN, app.state())
        assertEquals(0, app.equipment.backupCalls)

        p1.dead = false
        app.scheduler.tick()
        assertEquals(ArenaState.Kind.INGAME, app.state())
    }

    @Test
    fun `the countdown waits while the second player is dead`() {
        val app = TestApp()
        val (_, p2) = app.joinedTwo()
        app.scheduler.tick(5)
        p2.dead = true
        app.scheduler.tick()
        assertEquals(ArenaState.Kind.COUNTDOWN, app.state())
        assertEquals(0, app.equipment.backupCalls)

        p2.dead = false
        app.scheduler.tick()
        assertEquals(ArenaState.Kind.INGAME, app.state())
    }

    @Test
    fun `the countdown aborts when the first participant disconnects`() {
        val app = TestApp()
        val (p1, _) = app.joinedTwo()
        app.scheduler.tick(2)
        app.players.disconnect(p1)
        app.scheduler.tick()
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.equipment.kitApplies.isEmpty())
    }

    @Test
    fun `aborting before backups defers a dead participant without a ticket`() {
        val app = TestApp()
        val (p1, _) = app.joinedTwo()
        p1.dead = true

        app.service.abort(Arena.Id.new("arena1"))

        assertTrue(app.equipment.restored.isEmpty())
        app.scheduler.runOneShots()
        assertTrue("respawn" in p1.events)
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `a deferred loser rearm is skipped once the match is aborted`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        val applies = app.equipment.kitApplies.count { it.second == p2.id }
        val teleports = p2.teleports.size

        app.service.abort(Arena.Id.new("arena1"))
        app.scheduler.runOneShots()

        assertEquals(applies, app.equipment.kitApplies.count { it.second == p2.id })
        assertEquals(teleports, p2.teleports.size)
    }

    @Test
    fun `a deferred loser rearm is skipped when the loser is gone`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        val applies = app.equipment.kitApplies.count { it.second == p2.id }
        val teleports = p2.teleports.size

        app.players.disconnect(p2)
        app.scheduler.runOneShots()

        assertEquals(applies, app.equipment.kitApplies.count { it.second == p2.id })
        assertEquals(teleports, p2.teleports.size)
        assertTrue(app.service.matchOf("arena1")!!.resolving)
    }

    @Test
    fun `a deferred loser rearm is skipped while the loser is quitting`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        val applies = app.equipment.kitApplies.count { it.second == p2.id }
        val teleports = p2.teleports.size

        app.players.disconnect(p2)
        app.players.quittingScope(p2) {
            app.scheduler.runOneShots()
        }

        assertEquals(applies, app.equipment.kitApplies.count { it.second == p2.id })
        assertEquals(teleports, p2.teleports.size)
    }

    @Test
    fun `a deferred loser rearm is skipped when the arena is removed`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        val applies = app.equipment.kitApplies.count { it.second == p2.id }

        assertNull(app.admin.remove("arena1"))
        app.scheduler.runOneShots()

        assertEquals(applies, app.equipment.kitApplies.count { it.second == p2.id })
        assertNull(app.service.matchOf("arena1"))
    }

    @Test
    fun `a deferred restore is skipped when the loser rejoins elsewhere`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        assertEquals(listOf(p1.id), app.equipment.restored.map { it.playerId })

        val arena2 = app.newArena("arena2")
        p2.dead = false
        app.service.join(p2.id, p2.name, arena2)
        val restored = app.equipment.restored.size
        app.scheduler.runOneShots()

        assertEquals(restored, app.equipment.restored.size)
        assertEquals(arena2, app.service.arenaIdOf(p2.id))
    }

    @Test
    fun `forfeit restores an online loser without a vitals reset`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()

        app.service.quit(p2.id)

        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
        assertTrue("vitals" !in p2.events)
        assertTrue(app.presentation.fireworks.isEmpty())
    }

    @Test
    fun `the countdown aborts when the second participant disconnects`() {
        val app = TestApp()
        val (_, p2) = app.joinedTwo()
        app.scheduler.tick(2)
        app.players.disconnect(p2)
        app.scheduler.tick()
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.equipment.kitApplies.isEmpty())
    }

    @Test
    fun `a deferred loser rearm runs while the round still waits`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        val applies = app.equipment.kitApplies.count { it.second == p2.id }
        val teleports = p2.teleports.size

        app.scheduler.runOneShots()

        assertTrue("respawn" in p2.events)
        assertEquals(applies + 1, app.equipment.kitApplies.count { it.second == p2.id })
        assertEquals(teleports + 1, p2.teleports.size)
        assertFalse(app.service.matchOf("arena1")!!.resolving)
    }

    @Test
    fun `a stale countdown tick after the match began only cancels itself`() {
        val app = TestApp()
        app.joinedTwo()
        val timer = app.scheduler.timers.last()
        app.scheduler.tick(6)
        assertEquals(ArenaState.Kind.INGAME, app.state())

        timer.run()

        assertTrue(timer.cancelled)
        assertEquals(ArenaState.Kind.INGAME, app.state())
    }

    @Test
    fun `a deferred restore still completes after the arena is removed`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        assertNull(app.admin.remove("arena1"))

        app.scheduler.runOneShots()

        assertTrue("respawn" in p2.events)
        assertTrue(app.equipment.restored.isNotEmpty())
    }

    @Test
    fun `a countdown cancels itself once the arena is removed`() {
        val app = TestApp()
        app.joinedTwo()
        val timer = app.scheduler.timers.last()
        assertNull(app.admin.remove("arena1"))

        timer.run()

        assertTrue(timer.cancelled)
    }

    @Test
    fun `an aborted match still completes a pending deferred restore`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))

        app.service.abort(Arena.Id.new("arena1"))
        app.scheduler.runOneShots()

        assertTrue("respawn" in p2.events)
        assertTrue(app.equipment.restored.isNotEmpty())
    }
}
