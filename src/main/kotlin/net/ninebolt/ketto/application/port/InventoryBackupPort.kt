package net.ninebolt.ketto.application.port

import net.ninebolt.ketto.domain.MatchId
import net.ninebolt.ketto.domain.Participant
import kotlin.uuid.Uuid

interface InventoryBackupPort {
    // on failure throws PersistenceException leaving both inventories untouched
    fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef>

    // on failure the backup record is kept
    fun restore(backup: BackupRef)

    fun discard(backup: BackupRef)

    fun findPending(playerId: Uuid): BackupRef?

    fun pendingRefs(): List<BackupRef>
}
