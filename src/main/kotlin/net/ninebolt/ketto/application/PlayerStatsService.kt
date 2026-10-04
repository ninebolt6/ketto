package net.ninebolt.ketto.application

import net.ninebolt.ketto.application.port.PersistenceException
import net.ninebolt.ketto.application.port.PlayerPort
import net.ninebolt.ketto.application.port.PlayerStatsRepository
import net.ninebolt.ketto.domain.PlayerStats
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

// Calls are serialized on the main thread, so the throttle map needs no synchronization
class PlayerStatsService(
    private val statsRepository: PlayerStatsRepository,
    private val playerPort: PlayerPort,
    private val logger: Logger,
) {

    fun ownStats(playerId: Uuid): StatsOutput = read(playerId)

    fun lookupStats(requesterId: Uuid, targetName: String, nowNanos: Long, onResult: (StatsOutput) -> Unit) {
        if (!statsLookupThrottle.tryAcquire(requesterId, nowNanos)) {
            onResult(StatsOutput.Cooldown)
            return
        }
        playerPort.resolveOfflineId(targetName) { uuid ->
            onResult(uuid?.let(::read) ?: StatsOutput.Missing)
        }
    }

    fun recordWin(playerId: Uuid) {
        val current = statsRepository.find(playerId) ?: PlayerStats.new(playerId)
        statsRepository.save(current.recordWin())
    }

    fun recordLoss(playerId: Uuid) {
        val current = statsRepository.find(playerId) ?: PlayerStats.new(playerId)
        statsRepository.save(current.recordLoss())
    }

    private fun read(playerId: Uuid): StatsOutput {
        val found = try {
            statsRepository.find(playerId)
        } catch (e: PersistenceException) {
            logger.log(Level.WARNING, "Could not read stats for $playerId", e)
            null
        }
        return found?.let(StatsOutput::Found) ?: StatsOutput.Missing
    }

    // Named-stats lookups resolve uncached names through an external call, hence the cooldown
    private val statsLookupThrottle = RequestThrottle(STATS_LOOKUP_COOLDOWN_NANOS)

    private companion object {
        const val STATS_LOOKUP_COOLDOWN_NANOS = 3_000_000_000L
    }
}

sealed interface StatsOutput {
    data class Found(val stats: PlayerStats) : StatsOutput
    data object Missing : StatsOutput
    data object Cooldown : StatsOutput
}
