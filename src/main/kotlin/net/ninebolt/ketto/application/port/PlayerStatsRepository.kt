package net.ninebolt.ketto.application.port

import net.ninebolt.ketto.domain.PlayerStats
import kotlin.uuid.Uuid

// a missing file yields null; corruption and I/O errors throw PersistenceException
interface PlayerStatsRepository {
    fun find(playerId: Uuid): PlayerStats?
    fun save(stats: PlayerStats)
}
