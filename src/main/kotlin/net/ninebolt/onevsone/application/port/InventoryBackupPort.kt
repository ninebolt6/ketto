package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import kotlin.uuid.Uuid

interface InventoryBackupPort {
    // on failure throws PersistenceFailure leaving both inventories untouched
    fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef>

    // on failure the backup record is kept
    fun restore(backup: BackupRef)

    fun acknowledge(backup: BackupRef)

    fun pendingFor(playerId: Uuid): BackupRef?

    fun pendingRefs(): List<BackupRef>
}
