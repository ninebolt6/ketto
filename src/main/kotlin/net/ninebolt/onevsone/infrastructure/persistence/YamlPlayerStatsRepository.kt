package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.PlayerStats
import kotlin.uuid.Uuid

/** stats/<uuid>.yml の永続化。 */
class YamlPlayerStatsRepository(private val store: YamlStore) : PlayerStatsRepository {

    /** ファイル不存在は null。破損は PersistenceFailure。 */
    override fun find(playerId: Uuid): PlayerStats? {
        val file = store.statsFile(playerId)
        if (!file.exists()) return null
        val yaml = store.load(file)
        return PlayerStats(wins = yaml.getInt("win"), losses = yaml.getInt("lose"))
    }

    override fun recordWin(playerId: Uuid) = write(playerId, winDelta = 1, loseDelta = 0)

    override fun recordLoss(playerId: Uuid) = write(playerId, winDelta = 0, loseDelta = 1)

    private fun write(playerId: Uuid, winDelta: Int, loseDelta: Int) {
        val file = store.statsFile(playerId)
        val yaml = store.load(file)
        yaml.set("win", yaml.getInt("win") + winDelta)
        yaml.set("lose", yaml.getInt("lose") + loseDelta)
        store.save(yaml, file)
    }
}
