package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class InventoryRecoveryServiceTest {

    private fun backupRef(id: Uuid, name: String, match: MatchId = MatchId.new()) = BackupRef.new(match, id, name)

    @Test
    fun `shutdown retains pending records when the backup listing fails`() {
        val app = TestApp()
        val ref = backupRef(Uuid.random(), "Alice")
        app.equipment.seedBackup(ref)
        app.equipment.failOnPendingRefs = PersistenceException("read failed")
        app.lifecycle.shutdown()
        assertTrue(app.logger.warnings.any { it.contains("Could not list pending backups") })
        assertEquals(ref, app.equipment.pendingFor(ref.playerId))
    }

    @Test
    fun `quit right after match end still restores via quitting scope`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()
        p2.dead = true
        app.participation.defeat(p2.id, DefeatCause.DEATH)
        assertEquals(ArenaState.Kind.WAITING, app.state())

        val pending = app.recovery.pending(p2.id)
        assertNotNull(pending)
        app.players.disconnect(p2)
        app.players.quittingScope(p2) {
            app.participation.quit(p2.id)
        }
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.equipment.storedBackups.isEmpty())
    }

    @Test
    fun `deferred restore after final death respawns before restoring`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()
        p2.dead = true
        app.participation.defeat(p2.id, DefeatCause.DEATH)
        app.scheduler.runOneShots()
        assertEquals(listOf("respawn", "vitals", "restore"), p2.events.takeLast(3))
        assertTrue(app.equipment.storedBackups.isEmpty())
    }

    @Test
    fun `no backup means no restore and no fallback`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        app.participation.join(p1.id, p1.name, arenaId("arena1"))
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.participation.quit(p1.id)
        }
        assertTrue(app.equipment.restored.isEmpty())
        assertNull(app.recovery.pending(p1.id))
    }

    @Test
    fun `offline participant retains pending restore for next login`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        app.players.disconnect(p2)
        app.progression.abort(arenaId("arena1"))
        assertEquals(1, app.equipment.restored.size)
        assertEquals(1, app.equipment.storedBackups.size)

        p2.online = true
        app.participation.restorePending(p2.id)
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.equipment.storedBackups.isEmpty())
        assertNull(app.recovery.pending(p2.id))
    }

    @Test
    fun `name collision does not restore but renamed uuid does`() {
        val app = TestApp()
        val original = app.players.add("Alice")
        val ref = backupRef(original.id, "Alice")
        app.equipment.seedBackup(ref)

        val squatter = app.players.add("Alice")
        app.participation.restorePending(squatter.id)
        assertTrue(app.equipment.restored.isEmpty())

        val renamed = app.players.add("Alice2", original.id)
        app.participation.restorePending(renamed.id)
        assertEquals(1, app.equipment.restored.size)
        assertTrue(app.equipment.storedBackups.isEmpty())
    }

    @Test
    fun `discard failure keeps disk record but completes restore`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        val ref = backupRef(p.id, "Alice")
        app.equipment.seedBackup(ref)
        app.equipment.failOnDiscard = true

        app.participation.restorePending(p.id)
        assertEquals(1, app.equipment.restored.size)
        assertTrue(app.equipment.storedBackups.containsKey(ref.backupId))
        assertTrue(app.logger.reports.any { it.message.contains("Could not discard") })
    }

    @Test
    fun `restore failure retains the pending backup and record`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        val ref = backupRef(p.id, "Alice")
        app.equipment.seedBackup(ref)
        app.equipment.failOnRestore = true

        app.participation.restorePending(p.id)
        assertTrue(app.equipment.restored.isEmpty())
        assertNotNull(app.recovery.pending(p.id))
        app.equipment.failOnRestore = false
        app.participation.restorePending(p.id)
        assertEquals(1, app.equipment.restored.size)
    }

    @Test
    fun `restore failure returns recovery rejection and keeps the pending backup`() {
        val app = TestApp()
        val arenaId = app.newArena()
        val p = app.players.add("Alice")
        val ref = backupRef(p.id, p.name)
        app.equipment.seedBackup(ref)
        val pending = assertNotNull(app.recovery.pending(p.id))
        app.equipment.failOnRestore = true

        assertEquals(JoinOutput.RestorePending, app.participation.join(p.id, p.name, arenaId))

        assertNull(app.sessions.arenaIdOf(p.id))
        assertTrue(app.sessions.match(arenaId)!!.participants.isEmpty())
        assertEquals(pending, app.recovery.pending(p.id))
        assertTrue(app.equipment.storedBackups.containsKey(ref.backupId))
    }

    @Test
    fun `stale deferred callback after abort cannot reapply`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        app.participation.defeat(p2.id, DefeatCause.DEATH)
        val applies = app.equipment.kitApplies.count { it.second == p2.id }
        app.progression.abort(arenaId("arena1"))
        app.scheduler.runOneShots()
        val p2Restores = app.equipment.restored.count { it.playerId == p2.id }
        assertEquals(1, p2Restores)
        assertEquals(applies, app.equipment.kitApplies.count { it.second == p2.id })
        assertEquals(ArenaState.Kind.WAITING, app.state())
    }

    @Test
    fun `pending backup survives abort and completes on rejoin`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        app.participation.defeat(p2.id, DefeatCause.DEATH)
        app.players.disconnect(p2)
        app.scheduler.runOneShots()
        assertNotNull(app.recovery.pending(p2.id))

        app.scheduler.tick()
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertNotNull(app.recovery.pending(p2.id))
        assertNull(app.sessions.arenaIdOf(p2.id))

        p2.online = true
        p2.dead = false
        app.participation.restorePending(p2.id)
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
        assertNull(app.recovery.pending(p2.id))
    }

    @Test
    fun `shutdown keeps records for dead players and restores online ones`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        p2.dead = true
        app.lifecycle.shutdown()
        assertTrue(app.equipment.restored.any { it.playerId == p1.id })
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
        assertEquals(1, app.equipment.storedBackups.size)
        assertNotNull(app.recovery.pending(p2.id))
    }

    @Test
    fun `failed restore during finish keeps position and pending backup`() {
        val app = TestApp(requiredWins = 1)
        val lobby = WorldPosition.new("world", 9.0, 64.0, 9.0)
        app.arenaRepository.lobbyPosition = lobby
        val (p1, p2) = app.startMatch()
        app.equipment.failOnRestore = true

        app.participation.defeat(p2.id, DefeatCause.FALL)
        app.scheduler.runOneShots()
        assertTrue(p1.teleports.none { it == lobby })
        assertTrue(p2.teleports.none { it == lobby })
        assertNotNull(app.recovery.pending(p1.id))
        assertNotNull(app.recovery.pending(p2.id))
    }

    @Test
    fun `missing lobby skips teleport with a warning`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()

        app.participation.defeat(p2.id, DefeatCause.FALL)
        app.scheduler.runOneShots()
        assertTrue(app.logger.warnings.count { it.contains("Lobby is not set") } >= 1)
        assertNull(app.recovery.pending(p1.id))
    }

    @Test
    fun `shutdown retains dead player record when restore fails`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        app.equipment.failOnRestore = true
        app.lifecycle.shutdown()
        assertTrue(app.logger.reports.any { it.message.contains("Could not restore inventory") })
        assertNotNull(app.recovery.pending(p2.id))
    }

    @Test
    fun `quit by an unjoined player with a pending backup restores at quit`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.equipment.seedBackup(backupRef(p.id, p.name))
        app.players.disconnect(p)
        app.players.quittingScope(p) {
            app.participation.quit(p.id)
        }
        assertTrue(app.equipment.restored.any { it.playerId == p.id })
        assertNull(app.recovery.pending(p.id))
    }

    @Test
    fun `quit with a pending backup but no handle keeps the restore`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.equipment.seedBackup(backupRef(p.id, p.name))
        app.players.disconnect(p)

        app.participation.quit(p.id)

        assertTrue(app.equipment.restored.isEmpty())
        assertNotNull(app.recovery.pending(p.id))
    }

    @Test
    fun `a stale pending backup cannot be restored`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        val participant = Participant.new(p.id, p.name)
        app.recovery.backupBeforeMatch(listOf(participant))
        val stale = app.recovery.pending(p.id)!!
        app.recovery.backupBeforeMatch(listOf(participant))

        assertFalse(app.recovery.restoreNow(p, stale))

        assertTrue(app.equipment.restored.isEmpty())
        assertNotNull(app.recovery.pending(p.id))
    }

    @Test
    fun `finished match teleports players to the configured lobby`() {
        val app = TestApp(requiredWins = 1)
        val lobby = WorldPosition.new("world", 9.0, 64.0, 9.0)
        app.arenaRepository.lobbyPosition = lobby
        val (p1, p2) = app.startMatch()

        app.participation.defeat(p2.id, DefeatCause.FALL)
        app.scheduler.runOneShots()

        assertTrue(lobby in p1.teleports)
        assertTrue(lobby in p2.teleports)
    }

    @Test
    fun `shutdown keeps the record for an offline pending player`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        app.players.disconnect(p2)
        app.lifecycle.shutdown()
        assertTrue(app.equipment.restored.none { it.playerId == p2.id })
        assertEquals(1, app.equipment.storedBackups.size)
        assertNotNull(app.recovery.pending(p2.id))
    }

    @Test
    fun `restore to lobby leaves the player in place when the restore fails`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.equipment.seedBackup(backupRef(p.id, p.name))
        app.arenaRepository.lobbyPosition = WorldPosition.new("world", 0.0, 64.0, 0.0)
        app.equipment.failOnRestore = true

        app.recovery.restoreToLobby(p, app.recovery.pending(p.id)!!)

        assertTrue(p.teleports.isEmpty())
        assertNotNull(app.recovery.pending(p.id))
    }

    @Test
    fun `restore to lobby without a pending backup teleports to the lobby`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.arenaRepository.lobbyPosition = WorldPosition.new("world", 9.0, 64.0, 9.0)

        app.recovery.restoreToLobby(p, null)

        assertEquals(1, p.teleports.size)
    }

    @Test
    fun `dead pending holder cannot join until restored`() {
        val app = TestApp()
        app.newArena()
        val p = app.players.add("Alice")
        val ref = backupRef(p.id, "Alice")
        app.equipment.seedBackup(ref)

        p.dead = true
        assertEquals(JoinOutput.Rejected, app.participation.join(p.id, p.name, arenaId("arena1")))
        assertNull(app.sessions.arenaIdOf(p.id))
        p.dead = false
        assertEquals(JoinOutput.JoinedWaiting, app.participation.join(p.id, p.name, arenaId("arena1")))
        assertTrue(app.equipment.restored.any { it.playerId == p.id })
        assertTrue(app.equipment.storedBackups.isEmpty())
    }
}
