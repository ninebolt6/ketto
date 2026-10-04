package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

// Deferred callbacks are validated by comparing the still-pending BackupRef
class InventoryRecoveryService(
    private val backupPort: InventoryBackupPort,
    private val playerPort: PlayerPort,
    private val lobbyRepository: LobbyRepository,
    private val presentationPort: PresentationPort,
    private val logger: Logger,
) {
    fun backupBeforeMatch(participants: List<Participant>) {
        backupPort.backupBeforeMatch(MatchId.new(), participants)
    }

    fun pending(playerId: Uuid): BackupRef? = backupPort.pendingFor(playerId)

    fun restoreNow(handle: PlayerHandle, ref: BackupRef): Boolean {
        if (backupPort.pendingFor(handle.id) != ref) return false
        if (!restorePayload(handle, ref)) return false
        try {
            backupPort.discard(ref)
        } catch (e: PersistenceException) {
            // A failed delete leaves the record on disk; re-restoring on next startup is the safe side
            logger.log(Level.SEVERE, "Could not discard restored backup for ${handle.name} (${handle.id}); record retained", e)
        }
        return true
    }

    private fun restorePayload(handle: PlayerHandle, ref: BackupRef): Boolean {
        try {
            backupPort.restore(ref)
        } catch (e: PersistenceException) {
            logger.log(Level.SEVERE, "Could not restore inventory for ${handle.name} (${handle.id}); backup retained", e)
            return false
        }
        presentationPort.clearScoreboard(handle.id)
        return true
    }

    fun restoreToLobby(handle: PlayerHandle, ref: BackupRef?) {
        if (ref != null && !restoreNow(handle, ref)) return
        teleportLobby(handle)
    }

    private fun teleportLobby(handle: PlayerHandle) {
        val position = lobbyRepository.lobby()
        if (position == null) {
            logger.warning("Lobby is not set; skipping teleport for ${handle.name}")
            return
        }
        handle.teleport(position)
    }

    // Shutdown runs no future ticks, so restores are synchronous; dead players cannot be teleported and keep their record for next login
    fun restoreAllOnline() {
        val refs = try {
            backupPort.pendingRefs()
        } catch (e: PersistenceException) {
            logger.log(Level.WARNING, "Could not list pending backups; leaving records for next startup", e)
            return
        }
        refs.forEach { ref ->
            val handle = playerPort.handle(ref.playerId) ?: return@forEach
            if (handle.dead) {
                restorePayload(handle, ref)
            } else {
                restoreNow(handle, ref)
            }
        }
    }
}
