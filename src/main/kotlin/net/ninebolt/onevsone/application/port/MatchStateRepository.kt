package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/**
 * Externally referenced match state and participant ledger. Persists immutable
 * aggregate snapshots.
 */
interface MatchStateRepository {
    /** Writes state, participant names, and win counts to status/<arena>.yml. */
    fun saveStatus(match: ArenaMatch)
    /** Registers participation in players.yml (membership only; no inventory). */
    fun registerParticipant(participant: Participant, arena: Arena.Id)
    /** Removes the participation record from players.yml. Backups (inv.*) are not deleted. */
    fun unregisterParticipant(playerName: String)
    /** Resets leftover registrations on startup (inv.* backups are kept). */
    fun clearRegistrations()
}
