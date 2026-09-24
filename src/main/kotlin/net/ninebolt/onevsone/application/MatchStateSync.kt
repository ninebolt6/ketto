package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState

class MatchStateSync(
    private val matchState: MatchStateRepository,
    private val signs: ArenaSignService
) {
    fun persistMatch(match: ArenaMatch) {
        matchState.persistMatch(match)
    }

    fun saveStatus(match: ArenaMatch) {
        matchState.saveStatus(match)
    }

    fun refreshSign(arena: Arena.Id, state: ArenaState) {
        signs.refreshSign(arena, state)
    }

    fun refreshSign(match: ArenaMatch) {
        refreshSign(match.arenaId, match.state)
    }

    fun clearRegistrations() {
        matchState.clearRegistrations()
    }
}
