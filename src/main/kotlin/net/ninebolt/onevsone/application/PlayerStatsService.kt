package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.PlayerStats
import kotlin.uuid.Uuid

// Calls are serialized on the main thread, so the throttle map needs no synchronization
class PlayerStatsService(private val stats: PlayerStatsRepository) {

    fun statsFor(playerId: Uuid): PlayerStats? = stats.find(playerId)

    // Named-stats lookups resolve uncached names through an external call, hence the cooldown
    private val statsLookupThrottle = RequestThrottle(STATS_LOOKUP_COOLDOWN_NANOS)

    fun tryAcquireStatsLookup(playerId: Uuid, nowNanos: Long): Boolean =
        statsLookupThrottle.tryAcquire(playerId, nowNanos)

    private companion object {
        const val STATS_LOOKUP_COOLDOWN_NANOS = 3_000_000_000L
    }
}
