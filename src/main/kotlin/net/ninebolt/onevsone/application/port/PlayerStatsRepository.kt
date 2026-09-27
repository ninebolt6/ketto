package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.PlayerStats
import kotlin.uuid.Uuid

// a missing file yields null; corruption and I/O errors throw PersistenceException
interface PlayerStatsRepository {
    fun find(playerId: Uuid): PlayerStats?
    fun recordWin(playerId: Uuid)
    fun recordLoss(playerId: Uuid)
}
