package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import kotlin.uuid.Uuid

class FakeEquipment(var players: FakePlayers? = null) :
    KitPort,
    InventoryBackupPort {
    val storedBackups = linkedMapOf<Uuid, BackupRef>()
    val restored = mutableListOf<BackupRef>()
    val acknowledged = mutableListOf<BackupRef>()
    val kitApplies = mutableListOf<Pair<Arena.Id, Uuid>>()
    val savedKits = mutableListOf<Pair<Arena.Id, Uuid>>()
    val forgottenKits = mutableListOf<Arena.Id>()
    var backupCalls = 0
    var applyCalls = 0
    var failOnBackup: PersistenceFailure? = null
    var failOnApplyAt: Int = -1
    var failOnAcknowledge = false
    var failOnRestore = false

    override fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef> {
        backupCalls++
        failOnBackup?.let { throw it }
        return participants.map { p ->
            BackupRef.new(match, p.id, p.name)
        }.onEach(::putBackup)
    }

    override fun restore(backup: BackupRef) {
        if (failOnRestore) throw PersistenceFailure("restore failed")
        restored += backup
        players?.players?.get(backup.playerId)?.events?.add("restore")
    }

    override fun acknowledge(backup: BackupRef) {
        if (failOnAcknowledge) throw PersistenceFailure("acknowledge failed")
        acknowledged += backup
        storedBackups.remove(backup.backupId)
    }

    override fun pendingFor(playerId: Uuid): BackupRef? = storedBackups.values.firstOrNull { it.playerId == playerId }

    override fun pendingRefs(): List<BackupRef> = storedBackups.values.toList()

    fun seedBackup(ref: BackupRef) {
        putBackup(ref)
    }

    private fun putBackup(ref: BackupRef) {
        storedBackups.values.removeIf { it.playerId == ref.playerId }
        storedBackups[ref.backupId] = ref
    }

    override fun applyKit(arena: Arena.Id, playerId: Uuid) {
        applyCalls++
        if (applyCalls == failOnApplyAt) throw PersistenceFailure("kit apply failed")
        kitApplies += arena to playerId
    }

    override fun saveKit(arena: Arena.Id, playerId: Uuid) {
        savedKits += arena to playerId
    }

    override fun forgetKit(arena: Arena.Id) {
        forgottenKits += arena
    }
}
