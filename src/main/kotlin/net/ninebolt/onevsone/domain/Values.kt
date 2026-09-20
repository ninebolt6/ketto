package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/** Bukkit の Player もインベントリも持たず、識別子と表示名だけを持つ。 */
data class Participant(val id: Uuid, val name: String)

/** Paper の Location を含まない純粋な座標値。 */
data class WorldPosition(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f
)

/** 勝率の数値計算をここに集約する。表示書式は呼び出し側。 */
data class PlayerStats(val wins: Int, val losses: Int) {
    /** win/lose。lose == 0 のときは win / 1。 */
    val ratio: Double
        get() = wins.toDouble() / losses.coerceAtLeast(1)
}
