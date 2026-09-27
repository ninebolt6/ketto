package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

data class PlayerStats private constructor(
    val playerId: Uuid,
    val wins: Int,
    val losses: Int,
) {
    val ratio: Double
        get() = wins.toDouble() / losses.coerceAtLeast(1)

    fun recordWin(): PlayerStats = copy(wins = wins + 1)

    fun recordLoss(): PlayerStats = copy(losses = losses + 1)

    companion object {
        fun new(playerId: Uuid): PlayerStats = PlayerStats(playerId, 0, 0)

        fun restored(playerId: Uuid, wins: Int, losses: Int): PlayerStats {
            require(wins >= 0 && losses >= 0) { "negative stats: wins=$wins losses=$losses" }
            return PlayerStats(playerId, wins, losses)
        }
    }
}
