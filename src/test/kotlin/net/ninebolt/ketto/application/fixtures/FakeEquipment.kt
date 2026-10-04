package net.ninebolt.ketto.application.fixtures

import net.ninebolt.ketto.application.port.BackupRef
import net.ninebolt.ketto.application.port.InventoryBackupPort
import net.ninebolt.ketto.application.port.KitPort
import net.ninebolt.ketto.application.port.PersistenceException
import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.MatchId
import net.ninebolt.ketto.domain.Participant
import kotlin.uuid.Uuid

class FakeEquipment(var players: FakePlayers? = null) :
    KitPort,
    InventoryBackupPort {
    val storedBackups = linkedMapOf<Uuid, BackupRef>()
    val restored = mutableListOf<BackupRef>()
    val discarded = mutableListOf<BackupRef>()
    val kitApplies = mutableListOf<Pair<Arena.Id, Uuid>>()
    val savedKits = mutableListOf<Pair<Arena.Id, Uuid>>()
    val forgottenKits = mutableListOf<Arena.Id>()
    val stripped = mutableListOf<Uuid>()
    var backupCalls = 0
    var applyCalls = 0
    var failOnBackup: PersistenceException? = null
    var failOnApplyAt: Int = -1
    var failOnDiscard = false
    var failOnRestore = false
    var failOnPendingRefs: PersistenceException? = null
    var failOnFindPending: PersistenceException? = null

    override fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef> {
        backupCalls++
        failOnBackup?.let { throw it }
        return participants.map { p ->
            BackupRef.new(match, p.id, p.name)
        }.onEach(::putBackup)
    }

    override fun restore(backup: BackupRef) {
        if (failOnRestore) throw PersistenceException("restore failed")
        restored += backup
        players?.players?.get(backup.playerId)?.events?.add("restore")
    }

    override fun discard(backup: BackupRef) {
        if (failOnDiscard) throw PersistenceException("discard failed")
        discarded += backup
        storedBackups.remove(backup.backupId)
    }

    override fun findPending(playerId: Uuid): BackupRef? {
        failOnFindPending?.let { throw it }
        return storedBackups.values.firstOrNull { it.playerId == playerId }
    }

    override fun pendingRefs(): List<BackupRef> {
        failOnPendingRefs?.let { throw it }
        return storedBackups.values.toList()
    }

    fun seedBackup(ref: BackupRef) {
        putBackup(ref)
    }

    private fun putBackup(ref: BackupRef) {
        storedBackups.values.removeIf { it.playerId == ref.playerId }
        storedBackups[ref.backupId] = ref
    }

    override fun applyKit(arena: Arena.Id, playerId: Uuid) {
        applyCalls++
        if (applyCalls == failOnApplyAt) throw PersistenceException("kit apply failed")
        kitApplies += arena to playerId
    }

    override fun saveKit(arena: Arena.Id, playerId: Uuid) {
        savedKits += arena to playerId
    }

    override fun forgetKit(arena: Arena.Id) {
        forgottenKits += arena
    }

    override fun stripKit(playerId: Uuid) {
        stripped += playerId
    }
}
