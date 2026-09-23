package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.Participant

/**
 * Synchronizes committed match state, the participant ledger, and the join
 * sign through the persistence and presentation ports. Failures propagate for
 * context-dependent handling.
 */
class MatchStateSync(
    private val matchState: MatchStateRepository,
    private val presentation: MatchPresentationPort
) {
    fun register(participant: Participant, arena: Arena.Id) {
        matchState.registerParticipant(participant, arena)
    }

    fun unregister(participant: Participant) {
        matchState.unregisterParticipant(participant.name)
    }

    fun saveStatus(match: ArenaMatch) {
        matchState.saveStatus(match)
    }

    fun refreshSign(arena: Arena.Id, state: ArenaState) {
        presentation.updateSign(arena, state)
    }

    fun clearRegistrations() {
        matchState.clearRegistrations()
    }

    /** Persists the new state and refreshes the join sign. */
    fun publish(match: ArenaMatch) {
        saveStatus(match)
        refreshSign(match.arenaId, match.state)
    }
}
