package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.ArenaMatch

// write-only projection: never read at runtime; registrations are cleared at startup
interface MatchStateRepository {
    // state-based full rewrite: a failed call self-heals on the next call for the same arena
    fun persistMatch(match: ArenaMatch)

    fun saveStatus(match: ArenaMatch)

    // clears membership only; inventory backups are preserved
    fun clearRegistrations()
}
