package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.application.port.PlayerStatsRepository
import net.ninebolt.ketto.domain.PlayerStats
import kotlin.uuid.Uuid

class SqlitePlayerStatsRepository(private val store: SqliteStore) : PlayerStatsRepository {

    override fun find(playerId: Uuid): PlayerStats? = store.queryOne("SELECT wins, losses FROM player_stats WHERE player_uuid = ?", playerId.toString()) { row ->
        PlayerStats.restored(playerId, wins = row.getInt("wins"), losses = row.getInt("losses"))
    }

    override fun save(stats: PlayerStats) {
        store.exec(
            """
            INSERT INTO player_stats(player_uuid, wins, losses) VALUES (?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
              wins = excluded.wins,
              losses = excluded.losses
            """.trimIndent(),
            stats.playerId.toString(),
            stats.wins,
            stats.losses,
        )
    }
}
