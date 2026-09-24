package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

// Deferred callbacks are validated by ticket identity
class PlayerRecoveryService(
    private val backups: InventoryBackupPort,
    private val players: PlayerPort,
    private val lobby: LobbyRepository,
    private val presentation: PresentationPort,
    private val logger: Logger
) {
    class RestoreTicket(val ref: BackupRef)

    private val tickets = mutableMapOf<Uuid, RestoreTicket>()

    fun loadPersisted() {
        backups.pendingBackups().forEach(::registerTicket)
    }

    fun backupBeforeMatch(participants: List<Participant>) {
        backups.backupBeforeMatch(MatchId.new(), participants).forEach(::registerTicket)
    }

    private fun registerTicket(ref: BackupRef) {
        val owner = ref.playerId
        if (owner == null) {
            logger.warning(
                "Backup ${ref.backupId} for ${ref.playerName} has no owner uuid and cannot be restored; " +
                    "remove the stale row from the backups table"
            )
            return
        }
        tickets[owner] = RestoreTicket(ref)
    }

    fun pending(playerId: Uuid): RestoreTicket? = tickets[playerId]

    private fun ownedBy(handle: PlayerHandle, ticket: RestoreTicket): Boolean =
        tickets[handle.id] === ticket

    fun restoreNow(handle: PlayerHandle, ticket: RestoreTicket): Boolean {
        if (!ownedBy(handle, ticket)) return false
        try {
            backups.restore(ticket.ref)
        } catch (e: PersistenceFailure) {
            logger.log(Level.SEVERE, "Could not restore inventory for ${handle.name} (${handle.id}); backup retained", e)
            return false
        }
        presentation.clearScoreboard(handle.id)
        forget(ticket)
        try {
            backups.acknowledge(ticket.ref)
        } catch (e: PersistenceFailure) {
            // A failed delete leaves the record on disk; re-restoring on next startup is the safe side
            logger.log(Level.SEVERE, "Could not discard restored backup for ${handle.name} (${handle.id}); record retained", e)
        }
        return true
    }

    fun restoreToLobby(handle: PlayerHandle, ticket: RestoreTicket?) {
        if (ticket != null && !restoreNow(handle, ticket)) return
        teleportLobby(handle)
    }

    private fun forget(ticket: RestoreTicket) {
        tickets.values.remove(ticket)
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
        tickets.toList().forEach { (id, ticket) ->
            val handle = players.handle(id) ?: return@forEach
            if (handle.dead) {
                try {
                    backups.restore(ticket.ref)
                } catch (e: PersistenceFailure) {
                    logger.log(Level.SEVERE, "Could not restore inventory for ${handle.name} (${handle.id}); backup retained", e)
                    return@forEach
                }
                presentation.clearScoreboard(id)
            } else {
                restoreNow(handle, ticket)
            }
        }
    }
}
