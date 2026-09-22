package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.PlayerStats
import kotlin.uuid.Uuid

/** Persistence for stats/<uuid>.yml. */
class YamlPlayerStatsRepository(private val store: YamlStore) : PlayerStatsRepository {

    /** A missing file is null; corruption is PersistenceFailure. */
    override fun find(playerId: Uuid): PlayerStats? {
        val file = store.statsFile(playerId)
        if (!file.exists()) return null
        val yaml = store.load(file)
        return try {
            PlayerStats.new(wins = yaml.getInt("win"), losses = yaml.getInt("lose"))
        } catch (e: IllegalArgumentException) {
            throw PersistenceFailure("Corrupt stats file ${file.name}", e)
        }
    }

    override fun recordWin(playerId: Uuid) = write(playerId, winDelta = 1, loseDelta = 0)

    override fun recordLoss(playerId: Uuid) = write(playerId, winDelta = 0, loseDelta = 1)

    private fun write(playerId: Uuid, winDelta: Int, loseDelta: Int) {
        store.update(store.statsFile(playerId)) { yaml ->
            yaml.set("win", yaml.getInt("win") + winDelta)
            yaml.set("lose", yaml.getInt("lose") + loseDelta)
        }
    }
}
