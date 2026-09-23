package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.ArenaMatch

/**
 * Write-only persistence projection of the match aggregate: a participant
 * ledger plus the status snapshot. Never read back at runtime; registrations
 * are cleared at startup.
 *
 * Each method is one atomic persistence unit.
 */
interface MatchStateRepository {
    /**
     * Persists the whole projection for match's arena in one unit: the
     * participant ledger is rewritten to the current participants (rows
     * pointing at this arena but absent from the match are removed, missing
     * or moved players are upserted) and the status snapshot is saved.
     * Being state-based rather than a diff, a failed call self-heals on the
     * next call for the same arena.
     */
    fun persistMatch(match: ArenaMatch)

    /** Persists only the status snapshot (state, participant names, win counts). */
    fun saveStatus(match: ArenaMatch)

    /** Clears arena membership while preserving inventory backups. */
    fun clearRegistrations()
}
