package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.application.port.PersistenceFailure
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
        assertEquals(ArenaState.WAITING, app.state())
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
        assertEquals(ArenaState.ROUNDCOUNTDOWN, app.state())

        app.scheduler.tick()
        assertEquals(ArenaState.WAITING, app.state())
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
        assertEquals(ArenaState.ONEMORE, app.state())
    }

    @Test
    fun `quit by an offline loser finishes the match and keeps their ticket`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.players.disconnect(p2)

        app.service.quit(p2.id)

        assertEquals(ArenaState.WAITING, app.state())
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
        assertEquals(ArenaState.COUNTDOWN, app.state())
        assertEquals(0, app.equipment.backupCalls)

        p1.dead = false
        app.scheduler.tick()
        assertEquals(ArenaState.INGAME, app.state())
    }

    @Test
    fun `the countdown waits while the second player is dead`() {
        val app = TestApp()
        val (_, p2) = app.joinedTwo()
        app.scheduler.tick(5)
        p2.dead = true
        app.scheduler.tick()
        assertEquals(ArenaState.COUNTDOWN, app.state())
        assertEquals(0, app.equipment.backupCalls)

        p2.dead = false
        app.scheduler.tick()
        assertEquals(ArenaState.INGAME, app.state())
    }

    @Test
    fun `the countdown aborts when the first participant disconnects`() {
        val app = TestApp()
        val (p1, _) = app.joinedTwo()
        app.scheduler.tick(2)
        app.players.disconnect(p1)
        app.scheduler.tick()
        assertEquals(ArenaState.WAITING, app.state())
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
    fun `the countdown stops when the arena is removed`() {
        val app = TestApp()
        app.joinedTwo()
        app.scheduler.tick(2)

        assertNull(app.admin.remove("arena1"))
        app.scheduler.tick(10)

        assertNull(app.service.matchOf("arena1"))
        assertEquals(0, app.equipment.backupCalls)
        assertTrue(app.equipment.kitApplies.isEmpty())
    }

    @Test
    fun `forfeit restores an online loser without a vitals reset`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()

        app.service.quit(p2.id)

        assertEquals(ArenaState.WAITING, app.state())
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
        assertEquals(ArenaState.WAITING, app.state())
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
        assertEquals(ArenaState.INGAME, app.state())

        timer.run()

        assertTrue(timer.cancelled)
        assertEquals(ArenaState.INGAME, app.state())
    }

    @Test
    fun `finish deferred callbacks are skipped once the arena is removed`() {
        val app = TestApp()
        app.joinedTwo()
        val match = app.service.matchOf("arena1")!!
        val (first, second) = match.participants
        val h1 = app.players.players.getValue(first.id).also { it.dead = true }
        val h2 = app.players.players.getValue(second.id).also { it.dead = true }

        app.progression.finishMatch(match, first, second, forfeit = false, death = true)
        assertNull(app.admin.remove("arena1"))
        app.scheduler.runOneShots()

        assertTrue("vitals" !in h1.events)
        assertTrue("vitals" !in h2.events)
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `finish deferred callbacks are skipped while the player is still registered`() {
        val app = TestApp()
        app.joinedTwo()
        val match = app.service.matchOf("arena1")!!
        val (first, second) = match.participants
        val h1 = app.players.players.getValue(first.id).also { it.dead = true }
        val h2 = app.players.players.getValue(second.id).also { it.dead = true }

        app.progression.finishMatch(match, first, second, forfeit = false, death = true)
        app.scheduler.runOneShots()

        assertTrue("vitals" !in h1.events)
        assertTrue("vitals" !in h2.events)
        assertTrue("respawn" !in h1.events)
        assertTrue(app.equipment.restored.isEmpty())
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
    fun `a failed inventory backup aborts the match before it starts`() {
        val app = TestApp()
        app.joinedTwo()
        app.equipment.failOnBackup = PersistenceFailure("backup failed")

        app.scheduler.tick(6)

        assertEquals(ArenaState.WAITING, app.state())
        assertTrue(app.equipment.kitApplies.isEmpty())
    }

    @Test
    fun `a kit failure during match start aborts the match`() {
        val app = TestApp()
        app.joinedTwo()
        app.equipment.failOnApplyAt = 1

        app.scheduler.tick(6)

        assertEquals(ArenaState.WAITING, app.state())
        assertTrue(app.logger.reports.isNotEmpty())
    }

    @Test
    fun `a match starts even when a spawn is not configured`() {
        val app = TestApp()
        app.registry.installArena(Arena.new(Arena.Id.new("arena1"), enabled = true), persist = {})
        val p1 = app.players.add("Alice")
        val p2 = app.players.add("Bob")
        app.service.join(p1.id, p1.name, Arena.Id.new("arena1"))
        app.service.join(p2.id, p2.name, Arena.Id.new("arena1"))

        app.scheduler.tick(6)

        assertEquals(ArenaState.INGAME, app.state())
        assertTrue(app.logger.warnings.any { it.contains("spawn") && it.contains("not set") })
    }

    @Test
    fun `a forfeit without a loser ticket does not restore the loser`() {
        val app = TestApp()
        app.joinedTwo()
        val match = app.service.matchOf("arena1")!!
        val (first, second) = match.participants
        val h1 = app.players.players.getValue(first.id)
        val h2 = app.players.players.getValue(second.id)

        app.progression.finishMatch(match, first, second, forfeit = true, death = false)

        assertTrue("vitals" in h1.events)
        assertTrue("vitals" !in h2.events)
        assertTrue(app.equipment.restored.isEmpty())
    }
}
