package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant

/**
 * Persistence and restore of inventory backups taken at match start.
 * ItemStack payloads stay inside infrastructure; inner layers only get
 * BackupRef back.
 */
interface InventoryBackupPort {
    /**
     * Duplicates and bulk-persists both players' inventories just before the
     * match starts. On failure, throws PersistenceFailure without changing
     * anyone's inventory.
     */
    fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef>

    /** Restores a backup. On failure, throws with the backup kept. */
    fun restore(backup: BackupRef)

    /** Deletes the persisted record after restore completes. Only records with a matching backupId are removed. */
    fun acknowledge(backup: BackupRef)

    /** Identifiers of unrestored backups left by a previous process etc. */
    fun pendingBackups(): List<BackupRef>
}
