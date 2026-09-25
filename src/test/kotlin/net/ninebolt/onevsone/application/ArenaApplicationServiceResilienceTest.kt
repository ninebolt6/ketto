package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaApplicationServiceResilienceTest {

    @Test
    fun `abort during countdown stops timer and never equips`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        app.scheduler.tick(2)
        app.service.abort(Arena.Id.new("arena1"))
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        app.scheduler.tick(6)
        assertEquals(0, app.equipment.backupCalls)
        assertTrue(app.equipment.kitApplies.isEmpty())
        assertTrue(p1.teleports.isEmpty())
        assertTrue(p2.teleports.isEmpty())
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `backup persistence failure aborts before any equipment change`() {
        val app = TestApp()
        app.equipment.failOnBackup = PersistenceFailure("disk full")
        val (p1, p2) = app.joinedTwo()
        app.scheduler.tick(6)
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertNull(app.service.arenaIdOf(p2.id))
        assertTrue(app.equipment.kitApplies.isEmpty())
        assertTrue(app.equipment.restored.isEmpty())
        assertTrue(app.logger.reports.any { it.message.contains("Could not save inventories") })
        app.scheduler.tick(3)
        assertTrue(p1.teleports.isEmpty())
    }

    @Test
    fun `equipment apply failure after backup aborts and restores`() {
        val app = TestApp()
        app.equipment.failOnApplyAt = 2
        val (p1, _) = app.joinedTwo()
        app.scheduler.tick(6)
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.logger.reports.any { it.message.contains("Could not apply equipment") })
    }

    @Test
    fun `round end failure aborts instead of stalling in round countdown`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.equipment.failOnApplyAt = app.equipment.applyCalls + 1

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertNull(app.service.arenaIdOf(p2.id))
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.logger.reports.any { it.message.contains("Could not finish round") })
    }

    @Test
    fun `same tick duplicate defeat does not double score`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        assertFalse(app.service.defeat(p2.id, DefeatCause.DEATH))
        assertFalse(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(1, app.service.matchOf("arena1")!!.winsOf(p1.id))
        assertTrue(app.stats.stats.isEmpty())
    }

    @Test
    fun `countdown aborts when participant disconnects mid countdown`() {
        val app = TestApp()
        val (_, p2) = app.joinedTwo()
        app.scheduler.tick(2)
        app.players.disconnect(p2)
        app.scheduler.tick()
        assertEquals(ArenaState.WAITING, app.state())
        assertTrue(app.equipment.kitApplies.isEmpty())
    }

    @Test
    fun `stats failure for winner does not block loser record or restores`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.stats.failOnWin = PersistenceFailure("write failed")
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.WAITING, app.state())
        assertEquals(1, app.stats.stats[p2.id]?.losses)
        assertNull(app.stats.stats[p1.id])
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.logger.reports.any { it.message.startsWith("Failed to record win") })
    }

    @Test
    fun `stats failure for loser does not block winner record`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.stats.failOnLoss = PersistenceFailure("write failed")
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(1, app.stats.stats[p1.id]?.wins)
        assertTrue(app.logger.reports.any { it.message.startsWith("Failed to record loss") })
    }

    @Test
    fun `projection failure during abort degrades to report and still commits`() {
        val app = TestApp()
        val (p1, _) = app.startMatch()
        app.matchState.failOnPersist = true
        app.service.abort(Arena.Id.new("arena1"))
        assertNull(app.service.arenaIdOf(p1.id))
        assertTrue(app.logger.reports.any { it.message.contains("match projection") })
        app.matchState.failOnPersist = false
        app.matchState.persistMatch(app.service.matchOf("arena1")!!)
        assertTrue(app.matchState.registrations.isEmpty())
    }

    @Test
    fun `load isolates per arena status persistence failure`() {
        val app = TestApp()
        app.arenas.save(Arena.new(Arena.Id.new("broken")))
        app.arenas.save(Arena.new(Arena.Id.new("healthy")))
        app.matchState.failOnSaveStatusFor += "broken"
        app.lifecycle.load()
        assertEquals(ArenaState.WAITING, app.service.matchOf("broken")!!.state)
        assertEquals(ArenaState.WAITING, app.service.matchOf("healthy")!!.state)
        assertTrue(app.logger.warnings.any { it.contains("broken") })
    }

    @Test
    fun `shutdown restores backups even when a status save fails`() {
        val app = TestApp()
        app.startMatch("arena1")
        val (q1, q2) = app.startMatch("arena2")
        app.matchState.failOnSaveStatusFor += "arena1"
        app.lifecycle.shutdown()
        assertNull(app.service.arenaIdOf(q1.id))
        assertNull(app.service.arenaIdOf(q2.id))
        assertEquals(4, app.equipment.restored.size)
    }

    @Test
    fun `stale countdown callback after abort does nothing`() {
        val app = TestApp()
        app.joinedTwo()
        val timer = app.scheduler.timers.last()
        app.service.abort(Arena.Id.new("arena1"))
        timer.run()
        assertTrue(timer.cancelled)
        assertTrue(app.equipment.kitApplies.isEmpty())
        val p3 = app.players.add("Carol")
        assertEquals(JoinOutput.JoinedWaiting, app.service.join(p3.id, p3.name, Arena.Id.new("arena1")))
    }

    @Test
    fun `arena without spawns starts in place with a warning`() {
        val app = TestApp()
        val arena = Arena.Id.new("arena1")
        app.registry.installArena(Arena.new(arena, enabled = true), persist = {})
        val p1 = app.players.add("Alice")
        val p2 = app.players.add("Bob")
        app.service.join(p1.id, p1.name, arena)
        app.service.join(p2.id, p2.name, arena)

        app.scheduler.tick(6)
        assertEquals(ArenaState.INGAME, app.state())
        assertTrue(p1.teleports.isEmpty())
        assertTrue(p2.teleports.isEmpty())
        assertEquals(2, app.logger.warnings.count { it.contains("is not set; skipping teleport") })
    }

    @Test
    fun `dead player keeps countdown waiting without consuming the start tick`() {
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
}
