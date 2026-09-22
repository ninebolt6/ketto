package net.ninebolt.onevsone.domain

/** Win-rate arithmetic is centralized here. Display formatting is the caller's job. */
data class PlayerStats private constructor(val wins: Int, val losses: Int) {
    /** win/lose. Computed as win / 1 when lose == 0. */
    val ratio: Double
        get() = wins.toDouble() / losses.coerceAtLeast(1)

    companion object {
        fun new(wins: Int, losses: Int): PlayerStats {
            require(wins >= 0 && losses >= 0) { "negative stats: wins=$wins losses=$losses" }
            return PlayerStats(wins, losses)
        }
    }
}
