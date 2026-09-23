package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.PlayerStats
import kotlin.uuid.Uuid

/**
 * Read side of player stats, including the rate-limited named lookup.
 * Calls are serialized by the main thread; each repository call is one
 * atomic persistence unit.
 */
class PlayerStatsService(private val stats: PlayerStatsRepository) {

    /** Throws PersistenceFailure on corruption (handled by the caller). */
    fun statsFor(playerId: Uuid): PlayerStats? = stats.find(playerId)

    /**
     * Rate-limits the named-stats lookup, which resolves uncached names through
     * an external call. Once per cooldown window per requester.
     */
    private val statsLookupThrottle = RequestThrottle(STATS_LOOKUP_COOLDOWN_NANOS)

    /** true when the requester may run a named-stats lookup now. */
    fun tryAcquireStatsLookup(playerId: Uuid, nowNanos: Long): Boolean =
        statsLookupThrottle.tryAcquire(playerId, nowNanos)

    private companion object {
        const val STATS_LOOKUP_COOLDOWN_NANOS = 3_000_000_000L
    }
}
