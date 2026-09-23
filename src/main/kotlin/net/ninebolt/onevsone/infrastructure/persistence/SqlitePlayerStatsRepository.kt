package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.PlayerStats
import kotlin.uuid.Uuid

class SqlitePlayerStatsRepository(private val store: SqliteStore) : PlayerStatsRepository {

    override fun find(playerId: Uuid): PlayerStats? =
        store.queryOne("SELECT wins, losses FROM player_stats WHERE player_uuid = ?", playerId.toString()) { row ->
            PlayerStats.new(wins = row.getInt("wins"), losses = row.getInt("losses"))
        }

    override fun recordWin(playerId: Uuid) = write(playerId, winDelta = 1, loseDelta = 0)

    override fun recordLoss(playerId: Uuid) = write(playerId, winDelta = 0, loseDelta = 1)

    private fun write(playerId: Uuid, winDelta: Int, loseDelta: Int) {
        store.exec(
            """
            INSERT INTO player_stats(player_uuid, wins, losses) VALUES (?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
              wins = wins + excluded.wins,
              losses = losses + excluded.losses
            """.trimIndent(),
            playerId.toString(), winDelta, loseDelta
        )
    }
}
