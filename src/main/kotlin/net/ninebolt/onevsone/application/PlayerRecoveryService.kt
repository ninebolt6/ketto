package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

// Deferred callbacks are validated by comparing the still-pending BackupRef
class PlayerRecoveryService(
    private val backups: InventoryBackupPort,
    private val players: PlayerPort,
    private val lobby: LobbyRepository,
    private val presentation: PresentationPort,
    private val logger: Logger,
) {
    fun backupBeforeMatch(participants: List<Participant>) {
        backups.backupBeforeMatch(MatchId.new(), participants)
    }

    fun pending(playerId: Uuid): BackupRef? = backups.pendingFor(playerId)

    fun restoreNow(handle: PlayerHandle, ref: BackupRef): Boolean {
        if (backups.pendingFor(handle.id) != ref) return false
        if (!restorePayload(handle, ref)) return false
        try {
            backups.acknowledge(ref)
        } catch (e: PersistenceFailure) {
            // A failed delete leaves the record on disk; re-restoring on next startup is the safe side
            logger.log(Level.SEVERE, "Could not discard restored backup for ${handle.name} (${handle.id}); record retained", e)
        }
        return true
    }

    private fun restorePayload(handle: PlayerHandle, ref: BackupRef): Boolean {
        try {
            backups.restore(ref)
        } catch (e: PersistenceFailure) {
            logger.log(Level.SEVERE, "Could not restore inventory for ${handle.name} (${handle.id}); backup retained", e)
            return false
        }
        presentation.clearScoreboard(handle.id)
        return true
    }

    fun restoreToLobby(handle: PlayerHandle, ref: BackupRef?) {
        if (ref != null && !restoreNow(handle, ref)) return
        teleportLobby(handle)
    }

    private fun teleportLobby(handle: PlayerHandle) {
        val lobby = lobby.lobby()
        if (lobby == null) {
            logger.warning("Lobby is not set; skipping teleport for ${handle.name}")
            return
        }
        handle.teleport(lobby)
    }

    // Shutdown runs no future ticks, so restores are synchronous; dead players cannot be teleported and keep their record for next login
    fun restoreAllOnline() {
        val refs = try {
            backups.pendingRefs()
        } catch (e: PersistenceFailure) {
            logger.log(Level.WARNING, "Could not list pending backups; leaving records for next startup", e)
            return
        }
        refs.forEach { ref ->
            val handle = players.handle(ref.playerId) ?: return@forEach
            if (handle.dead) {
                restorePayload(handle, ref)
            } else {
                restoreNow(handle, ref)
            }
        }
    }
}
