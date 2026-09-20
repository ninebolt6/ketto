package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.PlayerStats
import kotlin.uuid.Uuid

/**
 * 戦績の永続化。ファイル不存在は null、破損・I/O は PersistenceFailure。
 */
interface PlayerStatsRepository {
    fun find(playerId: Uuid): PlayerStats?
    fun recordWin(playerId: Uuid)
    fun recordLoss(playerId: Uuid)
}
