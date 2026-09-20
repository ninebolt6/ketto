package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import kotlin.uuid.Uuid

/** インベントリ操作の記録・障害注入用フェイク。実データは持たず BackupRef のみ。 */
class FakeEquipment(var players: FakePlayers? = null) : KitPort, InventoryBackupPort {
    val storedBackups = linkedMapOf<Uuid, BackupRef>()
    val restored = mutableListOf<BackupRef>()
    val acknowledged = mutableListOf<BackupRef>()
    val kitApplies = mutableListOf<Pair<Arena.Id, Uuid>>()
    val savedKits = mutableListOf<Pair<Arena.Id, Uuid>>()
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
            BackupRef(Uuid.random(), match, p.id, p.name)
        }.onEach { storedBackups[it.backupId] = it }
    }

    override fun restore(backup: BackupRef) {
        if (failOnRestore) throw PersistenceFailure("restore failed")
        restored += backup
        backup.playerId?.let { players?.players?.get(it)?.events?.add("restore") }
    }

    override fun acknowledge(backup: BackupRef) {
        if (failOnAcknowledge) throw PersistenceFailure("acknowledge failed")
        acknowledged += backup
        storedBackups.remove(backup.backupId)
    }

    override fun pendingBackups(): List<BackupRef> = storedBackups.values.toList()

    fun seedBackup(ref: BackupRef) {
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
}
