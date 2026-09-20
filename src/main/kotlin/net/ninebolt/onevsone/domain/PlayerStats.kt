package net.ninebolt.onevsone.domain

/** 勝率の数値計算をここに集約する。表示書式は呼び出し側。 */
data class PlayerStats private constructor(val wins: Int, val losses: Int) {
    /** win/lose。lose == 0 のときは win / 1。 */
    val ratio: Double
        get() = wins.toDouble() / losses.coerceAtLeast(1)

    companion object {
        fun new(wins: Int, losses: Int): PlayerStats {
            require(wins >= 0 && losses >= 0) { "negative stats: wins=$wins losses=$losses" }
            return PlayerStats(wins, losses)
        }
    }
}
