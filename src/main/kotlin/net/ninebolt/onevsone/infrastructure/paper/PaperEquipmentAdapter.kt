package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.infrastructure.persistence.PersistedBackup
import net.ninebolt.onevsone.infrastructure.persistence.SqliteBackupStore
import net.ninebolt.onevsone.infrastructure.persistence.SqliteKitStore
import org.bukkit.entity.Player
import kotlin.uuid.Uuid

/**
 * Adapter for inventory payloads. Backup/kit ItemStacks are confined to this
 * layer as PaperInventorySnapshot.
 */
class PaperEquipmentAdapter(
    private val backups: SqliteBackupStore,
    private val kitStore: SqliteKitStore,
    private val lookup: PaperPlayerLookup
) : KitPort, InventoryBackupPort {

    /** In-memory cache of arena kits. */
    private val kits = mutableMapOf<Arena.Id, PaperInventorySnapshot>()

    /** Backup payloads captured/loaded while running (backupId -> snapshot). */
    private val pendingSnapshots = mutableMapOf<Uuid, PaperInventorySnapshot>()

    /** For tests and startup preloading. */
    internal fun putKit(arena: Arena.Id, kit: PaperInventorySnapshot) {
        kits[arena] = kit
    }

    /** The cached arena kit (for test verification. null when unset). */
    internal fun kitOf(arena: Arena.Id): PaperInventorySnapshot? = kits[arena]

    override fun forgetKit(arena: Arena.Id) {
        kits.remove(arena)
    }

    /**
     * Duplicates and bulk-persists both players' inventories. Duplication does
     * not modify the inventories. If saving fails, throws PersistenceFailure
     * without changing anyone's inventory.
     */
    override fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef> {
        val captured = participants.map { participant ->
            val player = lookup.resolve(participant.id)
                ?: throw PersistenceFailure("Player ${participant.name} (${participant.id}) is not available for inventory backup")
            PersistedBackup(
                BackupRef.new(
                    matchId = match,
                    playerId = participant.id,
                    playerName = participant.name
                ),
                PaperInventorySnapshot.capture(player.inventory)
            )
        }
        backups.saveBackups(captured)
        captured.forEach { (ref, snapshot) -> pendingSnapshots[ref.backupId] = snapshot }
        return captured.map { it.ref }
    }

    /** Restores a backup. An empty snapshot simply returns the player to an empty inventory. */
    override fun restore(backup: BackupRef) {
        val snapshot = pendingSnapshots[backup.backupId]
            ?: backups.backupFor(backup)?.snapshot
            ?: throw PersistenceFailure("No stored backup ${backup.backupId} for ${backup.playerName}")
        val player = resolve(backup)
            ?: throw PersistenceFailure("Player ${backup.playerName} is not available for restore")
        snapshot.apply(player.inventory)
    }

    private fun resolve(backup: BackupRef): Player? =
        backup.playerId?.let { lookup.resolve(it) } ?: lookup.resolveByName(backup.playerName)

    /** Deletes only records with a matching backupId. */
    override fun acknowledge(backup: BackupRef) {
        backups.deleteBackup(backup)
        pendingSnapshots.remove(backup.backupId)
    }

    override fun pendingBackups(): List<BackupRef> =
        backups.persistedBackups().onEach { pendingSnapshots[it.ref.backupId] = it.snapshot }.map { it.ref }

    override fun applyKit(arena: Arena.Id, playerId: Uuid) {
        val player = lookup.resolve(playerId)
            ?: throw PersistenceFailure("Player $playerId is not available for kit apply")
        kit(arena).apply(player.inventory)
    }

    override fun saveKit(arena: Arena.Id, playerId: Uuid) {
        val player = lookup.resolve(playerId)
            ?: throw PersistenceFailure("Player $playerId is not available for kit capture")
        val kit = PaperInventorySnapshot.capture(player.inventory)
        kitStore.saveArenaKit(arena.name, kit)
        kits[arena] = kit
    }

    private fun kit(arena: Arena.Id): PaperInventorySnapshot =
        kits.getOrPut(arena) { kitStore.loadArenaKit(arena.name) }
}
