package net.ninebolt.onevsone.domain

data class PlayerStats private constructor(val wins: Int, val losses: Int) {
    val ratio: Double
        get() = wins.toDouble() / losses.coerceAtLeast(1)

    companion object {
        fun new(wins: Int, losses: Int): PlayerStats {
            require(wins >= 0 && losses >= 0) { "negative stats: wins=$wins losses=$losses" }
            return PlayerStats(wins, losses)
        }
    }
}
