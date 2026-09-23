package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/**
 * Externally referenced match state and participant ledger. Persists immutable
 * aggregate snapshots.
 */
interface MatchStateRepository {
    /** Persists state, participant names, and win counts. */
    fun saveStatus(match: ArenaMatch)
    /** Records arena membership without changing inventory backups. */
    fun registerParticipant(participant: Participant, arena: Arena.Id)
    /** Removes arena membership without deleting inventory backups. */
    fun unregisterParticipant(playerName: String)
    /** Clears arena membership while preserving inventory backups. */
    fun clearRegistrations()
}
