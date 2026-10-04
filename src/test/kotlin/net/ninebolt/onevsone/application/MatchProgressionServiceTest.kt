package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.fixtures.arenaId
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
        app.participation.requestRespawn(p.id)
        app.scheduler.runOneShots()
        assertTrue("respawn" in p.events)
        assertFalse(p.dead)
    }

    @Test
    fun `request respawn leaves an alive handle untouched`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.participation.requestRespawn(p.id)
        app.scheduler.runOneShots()
        assertTrue(p.events.isEmpty())
    }

    @Test
    fun `request respawn without a handle does nothing`() {
        val app = TestApp()
        app.participation.requestRespawn(Uuid.random())
        app.scheduler.runOneShots()
        assertTrue(app.logger.records.isEmpty())
    }

    @Test
    fun `abort on an unknown arena is a no-op`() {
        val app = TestApp()
        app.progression.abort(arenaId("nope"))
        assertTrue(app.scheduler.timers.isEmpty())
        assertTrue(app.presentation.signUpdates.isEmpty())
        assertTrue(app.logger.records.isEmpty())
    }

    @Test
    fun `round win with the winner offline skips the winner rearm and teleport`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.players.disconnect(p1)

        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))

        assertEquals(1, app.equipment.kitApplies.count { it.second == p1.id })
        assertEquals(1, p1.teleports.size)
        assertEquals(1, app.presentation.roundEndSounds.size)
        assertEquals(2, app.equipment.kitApplies.count { it.second == p2.id })

        app.scheduler.tick()
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
    }

    @Test
    fun `round win with the loser offline enters round countdown without a sound`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        app.players.disconnect(p2)

        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))

        assertTrue(app.presentation.roundEndSounds.isEmpty())
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, app.state())

        app.scheduler.tick()
        assertEquals(ArenaState.Kind.WAITING, app.state())
    }

    @Test
    fun `final defeat by death restores a loser who is already respawned`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()

        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
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
        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))

        p1.dead = false
        assertEquals(JoinOutput.JoinedWaiting, app.participation.join(p1.id, p1.name, arenaId("arena1")))
        assertEquals(1, app.equipment.restored.count { it.playerId == p1.id })

        app.scheduler.runOneShots()
        assertEquals(1, app.equipment.restored.count { it.playerId == p1.id })
        assertTrue("vitals" !in p1.events)
        assertTrue(app.presentation.fireworks.isEmpty())
        assertEquals(ArenaState.Kind.ONEMORE, app.state())
    }

    @Test
    fun `quit by an offline loser finishes the match and keeps their pending backup`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.players.disconnect(p2)

        app.participation.quit(p2.id)

        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.equipment.restored.any { it.playerId == p1.id })
        assertTrue(app.equipment.restored.none { it.playerId == p2.id })
        assertNotNull(app.recovery.pending(p2.id))
        assertEquals(1, app.statsRepository.stats[p1.id]?.wins)
        assertEquals(1, app.statsRepository.stats[p2.id]?.losses)
    }

    @Test
    fun `match results accumulate in persisted stats across matches`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))

        assertEquals(JoinOutput.JoinedWaiting, app.participation.join(p1.id, p1.name, arenaId("arena1")))
        assertEquals(JoinOutput.JoinedStarting, app.participation.join(p2.id, p2.name, arenaId("arena1")))
        app.scheduler.tick(6)
        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))

        assertEquals(2, app.statsRepository.stats[p1.id]?.wins)
        assertEquals(2, app.statsRepository.stats[p2.id]?.losses)
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
    fun `aborting before backups defers a dead participant without a pending backup`() {
        val app = TestApp()
        val (p1, _) = app.joinedTwo()
        p1.dead = true

        app.progression.abort(arenaId("arena1"))

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
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        val applies = app.equipment.kitApplies.count { it.second == p2.id }
        val teleports = p2.teleports.size

        app.progression.abort(arenaId("arena1"))
        app.scheduler.runOneShots()

        assertEquals(applies, app.equipment.kitApplies.count { it.second == p2.id })
        assertEquals(teleports, p2.teleports.size)
    }

    @Test
    fun `a deferred loser rearm is skipped when the loser is gone`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        val applies = app.equipment.kitApplies.count { it.second == p2.id }
        val teleports = p2.teleports.size

        app.players.disconnect(p2)
        app.scheduler.runOneShots()

        assertEquals(applies, app.equipment.kitApplies.count { it.second == p2.id })
        assertEquals(teleports, p2.teleports.size)
    }

    @Test
    fun `a deferred loser rearm is skipped while the loser is quitting`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
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
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        val applies = app.equipment.kitApplies.count { it.second == p2.id }

        assertNull(app.admin.remove("arena1"))
        app.scheduler.runOneShots()

        assertEquals(applies, app.equipment.kitApplies.count { it.second == p2.id })
        assertNull(app.participation.matchIn("arena1"))
    }

    @Test
    fun `a deferred restore is skipped when the loser rejoins elsewhere`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        assertEquals(listOf(p1.id), app.equipment.restored.map { it.playerId })

        val arena2 = app.newArena("arena2")
        p2.dead = false
        app.participation.join(p2.id, p2.name, arena2)
        val restored = app.equipment.restored.size
        app.scheduler.runOneShots()

        assertEquals(restored, app.equipment.restored.size)
        assertEquals(arena2, app.sessions.arenaIdOf(p2.id))
    }

    @Test
    fun `forfeit restores an online loser without a vitals reset`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()

        app.participation.quit(p2.id)

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
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        val applies = app.equipment.kitApplies.count { it.second == p2.id }
        val teleports = p2.teleports.size

        app.scheduler.runOneShots()

        assertTrue("respawn" in p2.events)
        assertEquals(applies + 1, app.equipment.kitApplies.count { it.second == p2.id })
        assertEquals(teleports + 1, p2.teleports.size)
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
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        assertNull(app.admin.remove("arena1"))

        app.scheduler.runOneShots()

        assertTrue("respawn" in p2.events)
        assertTrue(app.equipment.restored.isNotEmpty())
    }

    @Test
    fun `a deferred restore is skipped when the loser is restored during the respawn`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        p2.onRespawn = {
            p2.onRespawn = null
            app.recovery.pending(p2.id)?.let { app.recovery.restoreNow(p2, it) }
        }

        app.scheduler.runOneShots()

        assertTrue("respawn" in p2.events)
        assertEquals(1, app.equipment.restored.count { it.playerId == p2.id })
        assertTrue("vitals" !in p2.events)
        assertNull(app.recovery.pending(p2.id))
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
    fun `forfeit by an already offline loser skips the restore but still finishes the match`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.players.disconnect(p2)
        val events = p2.events.size

        app.participation.quit(p2.id)

        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertEquals(events, p2.events.size)
        assertTrue(app.equipment.restored.any { it.playerId == p1.id })
    }

    @Test
    fun `forfeit with the loser's backup missing skips the restore`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.equipment.storedBackups.values.removeIf { it.playerId == p2.id }

        app.participation.quit(p2.id)

        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.logger.reports.any { it.message.contains("without a pending backup") })
        assertTrue(app.equipment.restored.none { it.playerId == p2.id })
        assertTrue(app.equipment.restored.any { it.playerId == p1.id })
    }

    @Test
    fun `match finish warns when the winner's backup is missing`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.equipment.storedBackups.values.removeIf { it.playerId == p1.id }

        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        app.scheduler.runOneShots()

        assertTrue(app.logger.reports.any { it.message.contains("without a pending backup") })
    }

    @Test
    fun `match finish warns and still respawns the loser when the loser's backup is missing`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()
        app.equipment.storedBackups.values.removeIf { it.playerId == p2.id }
        p2.dead = true

        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        app.scheduler.runOneShots()

        assertTrue(app.logger.reports.any { it.message.contains("without a pending backup") })
        assertTrue("respawn" in p2.events)
    }

    @Test
    fun `an aborted match still completes a pending deferred restore`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))

        app.progression.abort(arenaId("arena1"))
        app.scheduler.runOneShots()

        assertTrue("respawn" in p2.events)
        assertTrue(app.equipment.restored.isNotEmpty())
    }

    @Test
    fun `disabling the arena during the match start teleport does not present the match`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        p1.onTeleport = {
            p1.onTeleport = null
            app.admin.disable("arena1")
        }

        app.scheduler.tick(6)

        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(p1.teleports.isNotEmpty())
        assertTrue(p2.teleports.isEmpty())
        assertTrue(app.presentation.matchStarts.isEmpty())
        assertTrue(app.presentation.scoreboards.isEmpty())
        assertTrue(app.equipment.restored.any { it.playerId == p1.id })
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
        assertTrue(app.logger.reports.isEmpty())
    }

    @Test
    fun `disabling the arena during the last match start teleport aborts instead of beginning`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        p2.onTeleport = {
            p2.onTeleport = null
            app.admin.disable("arena1")
        }

        app.scheduler.tick(6)

        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(p1.teleports.isNotEmpty())
        assertTrue(p2.teleports.isNotEmpty())
        assertTrue(app.presentation.matchStarts.isEmpty())
        assertTrue(app.presentation.scoreboards.isEmpty())
        assertTrue(app.equipment.restored.any { it.playerId == p1.id })
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
        assertTrue(app.logger.reports.isEmpty())
    }

    @Test
    fun `removing the arena during the last match start teleport stops without an error`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        p2.onTeleport = {
            p2.onTeleport = null
            assertNull(app.admin.remove("arena1"))
        }

        app.scheduler.tick(6)

        assertTrue(p1.teleports.isNotEmpty())
        assertTrue(p2.teleports.isNotEmpty())
        assertTrue(app.presentation.matchStarts.isEmpty())
        assertTrue(app.equipment.restored.any { it.playerId == p1.id })
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
        assertTrue(app.logger.reports.isEmpty())
        assertNull(app.participation.matchIn("arena1"))
    }

    @Test
    fun `a participant quitting during the match start teleport aborts and restores the opponent`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        p1.onTeleport = {
            p1.onTeleport = null
            app.players.quittingScope(p1) { app.participation.quit(p1.id) }
            app.players.disconnect(p1)
        }

        app.scheduler.tick(6)

        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(p2.teleports.isEmpty())
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
        assertNull(app.recovery.pending(p2.id))
        assertNotNull(app.recovery.pending(p1.id))
        assertTrue(app.presentation.matchStarts.isEmpty())
        assertTrue(app.logger.reports.isEmpty())
    }

    @Test
    fun `removing the arena during the match start teleport stops without an error`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        p1.onTeleport = {
            p1.onTeleport = null
            assertNull(app.admin.remove("arena1"))
        }

        app.scheduler.tick(6)

        assertTrue(app.logger.reports.isEmpty())
        assertTrue(p2.teleports.isEmpty())
        assertTrue(app.equipment.restored.any { it.playerId == p1.id })
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
        assertNull(app.participation.matchIn("arena1"))
    }

    @Test
    fun `aborting during a round end teleport does not refresh a stale sign`() {
        val app = TestApp()
        val (_, p2) = app.joinedTwo()
        app.signs.setSign(arenaId("arena1"), BlockPosition.new("world", 3, 64, 3))
        app.scheduler.tick(6)
        assertEquals(ArenaState.Kind.INGAME, app.state())
        p2.onTeleport = {
            p2.onTeleport = null
            app.progression.abort(arenaId("arena1"))
        }

        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))
        app.scheduler.tick()

        assertEquals(ArenaState.Kind.WAITING, app.presentation.signUpdates.last().third)
        assertTrue(app.scheduler.timers.all { it.cancelled })
    }
}
