package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchParticipationServiceResilienceTest {

    @Test
    fun `abort during countdown stops timer and never equips`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        app.scheduler.tick(2)
        app.progression.abort(arenaId("arena1"))
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertNull(app.sessions.findArenaIdOf(p1.id))
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
        app.equipment.failOnBackup = PersistenceException("disk full")
        val (p1, p2) = app.joinedTwo()
        app.scheduler.tick(6)
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertNull(app.sessions.findArenaIdOf(p1.id))
        assertNull(app.sessions.findArenaIdOf(p2.id))
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
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertNull(app.sessions.findArenaIdOf(p1.id))
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.logger.reports.any { it.message.contains("Could not apply equipment") })
    }

    @Test
    fun `round end failure aborts instead of stalling in round countdown`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.equipment.failOnApplyAt = app.equipment.applyCalls + 1

        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertNull(app.sessions.findArenaIdOf(p1.id))
        assertNull(app.sessions.findArenaIdOf(p2.id))
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.logger.reports.any { it.message.contains("Could not finish round") })
    }

    @Test
    fun `same tick duplicate death does not double score`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.participation.defeat(p2.id, DefeatCause.DEATH))
        assertFalse(app.participation.defeat(p2.id, DefeatCause.DEATH))
        assertEquals(1, app.participation.findMatchIn("arena1")!!.winsOf(p1.id))
        assertTrue(app.statsRepository.stats.isEmpty())
    }

    @Test
    fun `stats failure for winner does not block loser record or restores`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.statsRepository.failOnSaveFor = p1.id
        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertEquals(1, app.statsRepository.stats[p2.id]?.losses)
        assertNull(app.statsRepository.stats[p1.id])
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.logger.reports.any { it.message.startsWith("Failed to record win") })
    }

    @Test
    fun `stats failure for loser does not block winner record`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.statsRepository.failOnSaveFor = p2.id
        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))
        assertEquals(1, app.statsRepository.stats[p1.id]?.wins)
        assertTrue(app.logger.reports.any { it.message.startsWith("Failed to record loss") })
    }

    @Test
    fun `stale countdown callback after abort does nothing`() {
        val app = TestApp()
        app.joinedTwo()
        val timer = app.scheduler.timers.last()
        app.progression.abort(arenaId("arena1"))
        timer.run()
        assertTrue(timer.cancelled)
        assertTrue(app.equipment.kitApplies.isEmpty())
        val p3 = app.players.add("Carol")
        assertEquals(JoinOutput.JoinedWaiting, app.participation.join(p3.id, p3.name, arenaId("arena1")))
    }

    @Test
    fun `pending lookup failure still records both results and strips kits`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.equipment.failOnFindPending = PersistenceException("read failed")

        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))

        assertEquals(1, app.statsRepository.stats[p1.id]?.wins)
        assertEquals(1, app.statsRepository.stats[p2.id]?.losses)
        assertEquals(setOf(p1.id, p2.id), app.equipment.stripped.toSet())
        assertTrue(app.logger.reports.any { it.message.contains("without a pending backup") })
    }

    @Test
    fun `restore failure strips kits keeps rows and records both results`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.equipment.failOnRestore = true

        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))

        assertEquals(1, app.statsRepository.stats[p1.id]?.wins)
        assertEquals(1, app.statsRepository.stats[p2.id]?.losses)
        assertEquals(setOf(p1.id, p2.id), app.equipment.stripped.toSet())
        assertEquals(setOf(p1.id, p2.id), app.presentation.clearedScoreboards.toSet())
        assertNotNull(app.recovery.findPending(p1.id))
        assertNotNull(app.recovery.findPending(p2.id))
    }

    @Test
    fun `lobby read failure still records both results`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.arenaRepository.lobbyPosition = WorldPosition.new("world", 9.0, 64.0, 9.0)
        app.arenaRepository.failOnLobbyRead = true

        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))

        assertEquals(1, app.statsRepository.stats[p1.id]?.wins)
        assertEquals(1, app.statsRepository.stats[p2.id]?.losses)
        assertTrue(app.equipment.restored.isNotEmpty())
        assertTrue(app.equipment.stripped.isEmpty())
        assertTrue(app.logger.warnings.any { it.contains("Could not read the lobby") })
    }

    @Test
    fun `sign refresh failure still records both results`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.arenaRepository.failOnSignRead = true

        assertTrue(app.participation.defeat(p2.id, DefeatCause.FALL))

        assertEquals(1, app.statsRepository.stats[p1.id]?.wins)
        assertEquals(1, app.statsRepository.stats[p2.id]?.losses)
        assertTrue(app.logger.reports.any { it.message.contains("refresh the sign") })
    }
}
