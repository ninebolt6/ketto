package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.PlayerStats
import java.util.UUID

/**
 * 戦績の永続化。ファイル不存在は null、破損・I/O は PersistenceFailure。
 */
interface PlayerStatsRepository {
    fun find(playerId: UUID): PlayerStats?
    fun recordWin(playerId: UUID)
    fun recordLoss(playerId: UUID)
}
