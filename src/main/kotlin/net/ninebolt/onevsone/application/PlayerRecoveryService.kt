package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
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
    private val failures: FailureReporter,
    /**
     * Whether a legacy backup without a recorded uuid may be restored by name
     * match. In offline mode a different person can log in under the same name,
     * so this is allowed only in online mode.
     */
    private val allowLegacyNameRestore: Boolean
) {
    /** Token for one restore target. Deferred callbacks match it by reference identity. */
    class RestoreTicket(val ref: BackupRef)

    private val ticketsByUuid = mutableMapOf<Uuid, RestoreTicket>()
    private val ticketsByName = mutableMapOf<String, RestoreTicket>()

    /** Called at startup. */
    fun loadPersisted() {
        backups.pendingBackups().forEach(::registerTicket)
    }

    /** Called after backupBeforeMatch succeeds. */
    fun register(refs: List<BackupRef>) {
        refs.forEach(::registerTicket)
    }

    private fun registerTicket(ref: BackupRef) {
        if (ref.playerId == null && !allowLegacyNameRestore) {
            failures.warn(
                "Backup for ${ref.playerName} has no owner uuid and is not restored on an offline-mode server; " +
                    "add 'uuid' to inv.${ref.playerName} in players.yml or delete the record"
            )
        }
        val ticket = RestoreTicket(ref)
        ref.playerId?.let { ticketsByUuid[it] = ticket }
        ticketsByName[ref.playerName] = ticket
    }

    /** UUID takes precedence; a name is used only when the backup's uuid matches (or is unrecorded and allowed). */
    fun ticketFor(playerId: Uuid, playerName: String): RestoreTicket? =
        ticketsByUuid[playerId]
            ?: ticketsByName[playerName]?.takeIf { it.matchesId(playerId) }

    fun pending(playerId: Uuid): RestoreTicket? = ticketsByUuid[playerId]

    /** Whether the name index may vouch for ownership. Legacy backups without uuid are allowed only when permitted. */
    private fun RestoreTicket.matchesId(playerId: Uuid): Boolean =
        if (ref.playerId == null) allowLegacyNameRestore else ref.playerId == playerId

    private fun ownedBy(handle: PlayerHandle, ticket: RestoreTicket): Boolean =
        ticketsByUuid[handle.id] === ticket ||
            (ticketsByName[handle.name] === ticket && ticket.matchesId(handle.id))

    /**
     * Completes a backup restore.
     * When ticket is null only the lobby transfer runs; the inventory is untouched.
     */
    fun restoreNow(handle: PlayerHandle, ticket: RestoreTicket?, respawn: Boolean, lobby: Boolean) {
        if (ticket == null) {
            if (lobby) teleportLobby(handle)
            return
        }
        if (!ownedBy(handle, ticket)) return
        if (respawn && handle.dead) handle.respawn()
        try {
            backups.restore(ticket.ref)
        } catch (e: PersistenceFailure) {
            failures.report("Could not restore inventory for ${handle.name} (${handle.id}); backup retained", e)
            return
        }
        presentation.clearScoreboard(handle.id)
        if (lobby) teleportLobby(handle)
        forget(ticket)
        try {
            backups.acknowledge(ticket.ref)
        } catch (e: PersistenceFailure) {
            // On delete failure the on-disk record remains (restored again next startup = safe side)
            failures.report("Could not discard restored backup for ${handle.name} (${handle.id}); record retained", e)
        }
    }

    private fun forget(ticket: RestoreTicket) {
        ticketsByUuid.values.remove(ticket)
        ticketsByName.values.remove(ticket)
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
        ticketsByUuid.toList().forEach { (id, ticket) ->
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
                restoreNow(handle, ticket, respawn = false, lobby = false)
            }
        }
    }
}
