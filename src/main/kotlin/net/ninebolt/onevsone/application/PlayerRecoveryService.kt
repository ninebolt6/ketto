package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import kotlin.uuid.Uuid

/**
 * Manages unrestored backups and restore tokens (RestoreTicket).
 * Handles death-right-after-finish -> disconnect, shutdown, and the next
 * login, independently of whether the player is participating.
 *
 * Deferred callbacks are validated by ticket identity, kept separate from the
 * match generation (ArenaMatch epoch).
 */
class PlayerRecoveryService(
    private val backups: InventoryBackupPort,
    private val players: PlayerPort,
    private val lobby: LobbyRepository,
    private val presentation: MatchPresentationPort,
    private val failures: FailureReporter
) {
    /** Token for one restore target. Deferred callbacks match it by reference identity. */
    class RestoreTicket(val ref: BackupRef)

    private val tickets = mutableMapOf<Uuid, RestoreTicket>()

    fun loadPersisted() {
        backups.pendingBackups().forEach(::registerTicket)
    }

    /**
     * Bulk-saves the participants' inventories and registers the restore
     * tickets. PersistenceFailure propagates with no tickets registered.
     */
    fun backupBeforeMatch(participants: List<Participant>) {
        backups.backupBeforeMatch(MatchId.new(), participants).forEach(::registerTicket)
    }

    private fun registerTicket(ref: BackupRef) {
        val owner = ref.playerId
        if (owner == null) {
            failures.warn(
                "Backup for ${ref.playerName} has no owner uuid and cannot be restored; " +
                    "add 'uuid' to inv.${ref.playerName} in players.yml or delete the record"
            )
            return
        }
        tickets[owner] = RestoreTicket(ref)
    }

    fun pending(playerId: Uuid): RestoreTicket? = tickets[playerId]

    private fun ownedBy(handle: PlayerHandle, ticket: RestoreTicket): Boolean =
        tickets[handle.id] === ticket

    /**
     * Completes a backup restore (inventory, scoreboard, record close-out).
     * true when the restore completed; false when the ticket is not owned or
     * the backup could not be restored.
     */
    fun restoreNow(handle: PlayerHandle, ticket: RestoreTicket): Boolean {
        if (!ownedBy(handle, ticket)) return false
        try {
            backups.restore(ticket.ref)
        } catch (e: PersistenceFailure) {
            failures.report("Could not restore inventory for ${handle.name} (${handle.id}); backup retained", e)
            return false
        }
        presentation.clearScoreboard(handle.id)
        forget(ticket)
        try {
            backups.acknowledge(ticket.ref)
        } catch (e: PersistenceFailure) {
            // On delete failure the on-disk record remains (restored again next startup = safe side)
            failures.report("Could not discard restored backup for ${handle.name} (${handle.id}); record retained", e)
        }
        return true
    }

    /**
     * Sends the player to the lobby, completing the backup restore first when a
     * ticket exists. A failed or unowned restore leaves the player in place.
     */
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
            failures.warn("Lobby is not set; skipping teleport for ${handle.name}")
            return
        }
        handle.teleport(lobby)
    }

    /**
     * Shutdown processing. Synchronously restores online players without
     * relying on future scheduler runs. Dead players get restore + scoreboard
     * clear only (the record is kept and restored again on next login).
     * Incomplete data such as offline players is left in place.
     */
    fun restoreAllOnline() {
        tickets.toList().forEach { (id, ticket) ->
            val handle = players.handle(id) ?: return@forEach
            if (handle.dead) {
                try {
                    backups.restore(ticket.ref)
                } catch (e: PersistenceFailure) {
                    failures.report("Could not restore inventory for ${handle.name} (${handle.id}); backup retained", e)
                    return@forEach
                }
                presentation.clearScoreboard(id)
            } else {
                restoreNow(handle, ticket)
            }
        }
    }
}
