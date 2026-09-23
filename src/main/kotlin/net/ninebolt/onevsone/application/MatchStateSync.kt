package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState

/**
 * Synchronizes the match persistence projection (participant ledger plus
 * status snapshot) through the persistence port, and delegates join-sign
 * repaints to ArenaSignService. Failures propagate for context-dependent
 * handling.
 */
class MatchStateSync(
    private val matchState: MatchStateRepository,
    private val signs: ArenaSignService
) {
    /** Rewrites the projection for match's arena (ledger + status) in one call. */
    fun persistMatch(match: ArenaMatch) {
        matchState.persistMatch(match)
    }

    fun saveStatus(match: ArenaMatch) {
        matchState.saveStatus(match)
    }

    fun refreshSign(arena: Arena.Id, state: ArenaState) {
        signs.refreshSign(arena, state)
    }

    /** Repaints the join sign with the match's current state. */
    fun refreshSign(match: ArenaMatch) {
        refreshSign(match.arenaId, match.state)
    }

    fun clearRegistrations() {
        matchState.clearRegistrations()
    }
}
