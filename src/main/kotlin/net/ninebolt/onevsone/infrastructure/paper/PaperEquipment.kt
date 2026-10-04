package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.infrastructure.persistence.PersistedBackup
import net.ninebolt.onevsone.infrastructure.persistence.SqliteBackupStore
import net.ninebolt.onevsone.infrastructure.persistence.SqliteKitStore
import org.bukkit.entity.Player
import kotlin.uuid.Uuid

class PaperEquipment(
    private val backupStore: SqliteBackupStore,
    private val kitStore: SqliteKitStore,
    private val lookup: PaperPlayerLookup,
) : KitPort,
    InventoryBackupPort {

    private val kits = mutableMapOf<Arena.Id, PaperInventorySnapshot>()

    override fun forgetKit(arena: Arena.Id) {
        kits.remove(arena)
    }

    override fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef> {
        val captured = participants.map { participant ->
            val player = lookup.find(participant.id)
                ?: throw PersistenceException("Player ${participant.name} (${participant.id}) is not available for inventory backup")
            PersistedBackup(
                BackupRef.new(
                    matchId = match,
                    playerId = participant.id,
                    playerName = participant.name,
                ),
                PaperInventorySnapshot.capture(player.inventory),
            )
        }
        backupStore.saveBackups(captured)
        return captured.map { it.ref }
    }

    override fun restore(backup: BackupRef) {
        val snapshot = backupStore.backupFor(backup)?.snapshot
            ?: throw PersistenceException("No stored backup ${backup.backupId} for ${backup.playerName}")
        val player = lookup.find(backup.playerId)
            ?: throw PersistenceException("Player ${backup.playerName} is not available for restore")
        snapshot.apply(player.inventory)
    }

    override fun discard(backup: BackupRef) {
        backupStore.deleteBackup(backup)
    }

    override fun findPending(playerId: Uuid): BackupRef? = backupStore.findPending(playerId)

    override fun pendingRefs(): List<BackupRef> = backupStore.pendingRefs()

    override fun applyKit(arena: Arena.Id, playerId: Uuid) {
        val player = lookup.find(playerId)
            ?: throw PersistenceException("Player $playerId is not available for kit apply")
        kit(arena).apply(player.inventory)
    }

    override fun saveKit(arena: Arena.Id, playerId: Uuid) {
        val player = lookup.find(playerId)
            ?: throw PersistenceException("Player $playerId is not available for kit capture")
        val kit = PaperInventorySnapshot.capture(player.inventory)
        kitStore.saveArenaKit(arena.name, kit)
        kits[arena] = kit
    }

    private fun kit(arena: Arena.Id): PaperInventorySnapshot = kits.getOrPut(arena) { kitStore.loadArenaKit(arena.name) }
}
