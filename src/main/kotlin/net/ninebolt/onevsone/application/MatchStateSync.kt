package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/**
 * Reflects committed matches externally: status persistence, sign updates, and
 * players.yml ledger unregistration. Failures propagate to the caller as
 * PersistenceFailure (whether to swallow them is context-dependent).
 */
class MatchStateSync(
    private val matchState: MatchStateRepository,
    private val presentation: MatchPresentationPort
) {
    /** Saves the match state to the status file and refreshes the join sign. */
    fun publish(match: ArenaMatch) {
        matchState.saveStatus(match)
        presentation.updateSign(match.arenaId, match.state)
    }

    /** Unregisters the participation record in players.yml. */
    fun unregister(participant: Participant) {
        matchState.unregisterParticipant(participant.name)
    }
}
