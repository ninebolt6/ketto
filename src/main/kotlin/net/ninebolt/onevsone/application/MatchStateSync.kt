package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/**
 * Synchronizes committed match state and participant registrations through the
 * persistence and presentation ports. Failures propagate for context-dependent
 * handling.
 */
class MatchStateSync(
    private val matchState: MatchStateRepository,
    private val presentation: MatchPresentationPort
) {
    /** Persists the new state and refreshes the join sign. */
    fun publish(match: ArenaMatch) {
        matchState.saveStatus(match)
        presentation.updateSign(match.arenaId, match.state)
    }

    fun unregister(participant: Participant) {
        matchState.unregisterParticipant(participant.name)
    }
}
