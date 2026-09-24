package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.MatchId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import kotlin.uuid.Uuid

class PlayerRecoveryServiceTest {

    private fun backupRef(id: Uuid, name: String, match: MatchId = MatchId.new()) =
        BackupRef.new(match, id, name)

    @Test
    fun `persisted backups load into tickets on startup`() {
        val app = TestApp()
        val ref = backupRef(Uuid.random(), "Alice")
        app.equipment.seedBackup(ref)
        app.recovery.loadPersisted()
        assertNotNull(app.recovery.pending(ref.playerId!!))
    }

    @Test
    fun `quit right after match end still restores via quitting scope`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()
        p2.dead = true
        app.service.defeat(p2.id, DefeatCause.DEATH)
        assertEquals(ArenaState.WAITING, app.state())

        val ticket = app.recovery.pending(p2.id)
        assertNotNull(ticket)
        app.players.disconnect(p2)
        app.players.quittingScope(p2) {
            app.service.quit(p2.id)
        }
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.equipment.storedBackups.isEmpty())
    }

    @Test
    fun `deferred restore after final death respawns before restoring`() {
        val app = TestApp(requiredWins = 1)
        val (_, p2) = app.startMatch()
        p2.dead = true
        app.service.defeat(p2.id, DefeatCause.DEATH)
        app.scheduler.runOneShots()
        assertEquals(listOf("respawn", "vitals", "restore"), p2.events.takeLast(3))
        assertTrue(app.equipment.storedBackups.isEmpty())
    }

    @Test
    fun `no backup means no restore and no fallback`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        app.service.join(p1.id, p1.name, Arena.Id.new("arena1"))
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.service.quit(p1.id)
        }
        assertTrue(app.equipment.restored.isEmpty())
        assertNull(app.service.pendingRestore(p1.id))
    }

    @Test
    fun `offline participant retains pending restore for next login`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        app.players.disconnect(p2)
        app.service.abort(Arena.Id.new("arena1"))
        assertEquals(1, app.equipment.restored.size)
        assertEquals(1, app.equipment.storedBackups.size)

        p2.online = true
        app.service.restorePending(p2.id)
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.equipment.storedBackups.isEmpty())
        assertNull(app.service.pendingRestore(p2.id))
    }

    @Test
    fun `name collision does not restore but renamed uuid does`() {
        val app = TestApp()
        val original = app.players.add("Alice")
        val ref = backupRef(original.id, "Alice")
        app.equipment.seedBackup(ref)
        app.recovery.loadPersisted()

        val squatter = app.players.add("Alice")
        app.service.restorePending(squatter.id)
        assertTrue(app.equipment.restored.isEmpty())

        val renamed = app.players.add("Alice2", original.id)
        app.service.restorePending(renamed.id)
        assertEquals(1, app.equipment.restored.size)
        assertTrue(app.equipment.storedBackups.isEmpty())
    }

    @Test
    fun `backup without uuid is not restored`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        val ref = BackupRef.new(MatchId.new(), null, "Alice")
        app.equipment.seedBackup(ref)
        app.recovery.loadPersisted()
        assertTrue(app.logger.warnings.any { it.contains("no owner uuid") })

        app.service.restorePending(p.id)
        assertTrue(app.equipment.restored.isEmpty())
        assertTrue(app.equipment.storedBackups.containsKey(ref.backupId))
    }

    @Test
    fun `acknowledge failure keeps disk record but completes restore`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        val ref = backupRef(p.id, "Alice")
        app.equipment.seedBackup(ref)
        app.recovery.loadPersisted()
        app.equipment.failOnAcknowledge = true

        app.service.restorePending(p.id)
        assertEquals(1, app.equipment.restored.size)
        assertTrue(app.equipment.storedBackups.containsKey(ref.backupId))
        assertTrue(app.logger.reports.any { it.message.contains("Could not discard") })
    }

    @Test
    fun `restore failure retains ticket and record`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        val ref = backupRef(p.id, "Alice")
        app.equipment.seedBackup(ref)
        app.recovery.loadPersisted()
        app.equipment.failOnRestore = true

        app.service.restorePending(p.id)
        assertTrue(app.equipment.restored.isEmpty())
        assertNotNull(app.service.pendingRestore(p.id))
        app.equipment.failOnRestore = false
        app.service.restorePending(p.id)
        assertEquals(1, app.equipment.restored.size)
    }

    @Test
    fun `stale deferred callback after abort cannot reapply`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        app.service.defeat(p2.id, DefeatCause.DEATH)
        app.service.abort(Arena.Id.new("arena1"))
        app.scheduler.runOneShots()
        val p2Restores = app.equipment.restored.count { it.playerId == p2.id }
        assertEquals(1, p2Restores)
        assertTrue(app.equipment.kitApplies.count { it.second == p2.id } <= 2)
        assertEquals(ArenaState.WAITING, app.state())
    }

    @Test
    fun `restore ticket survives abort and completes on rejoin`() {
        val app = TestApp()
        val (_, p2) = app.startMatch()
        p2.dead = true
        app.service.defeat(p2.id, DefeatCause.DEATH)
        app.players.disconnect(p2)
        app.scheduler.runOneShots()
        assertNotNull(app.service.pendingRestore(p2.id))

        app.scheduler.tick()
        assertEquals(ArenaState.WAITING, app.state())
        assertNotNull(app.service.pendingRestore(p2.id))
        assertNull(app.service.arenaIdOf(p2.id))

        p2.online = true
        p2.dead = false
        app.service.restorePending(p2.id)
        assertTrue(app.equipment.restored.any { it.playerId == p2.id })
        assertNull(app.service.pendingRestore(p2.id))
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
        assertNotNull(app.service.pendingRestore(p2.id))
    }

    @Test
    fun `dead pending holder cannot join until restored`() {
        val app = TestApp()
        app.newArena()
        val p = app.players.add("Alice")
        val ref = backupRef(p.id, "Alice")
        app.equipment.seedBackup(ref)
        app.recovery.loadPersisted()

        p.dead = true
        assertEquals(JoinOutput.InMatch, app.service.join(p.id, p.name, Arena.Id.new("arena1")))
        assertNull(app.service.arenaIdOf(p.id))
        p.dead = false
        assertEquals(JoinOutput.JoinedWaiting, app.service.join(p.id, p.name, Arena.Id.new("arena1")))
        assertTrue(app.equipment.restored.any { it.playerId == p.id })
        assertTrue(app.equipment.storedBackups.isEmpty())
    }
}
