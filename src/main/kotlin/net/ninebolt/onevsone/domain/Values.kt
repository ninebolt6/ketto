package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/** Bukkit の Player もインベントリも持たず、識別子と表示名だけを持つ。 */
data class Participant private constructor(val id: Uuid, val name: String) {
    companion object {
        /** 新規参加者。識別子は内部で発番する。 */
        fun new(name: String): Participant = new(Uuid.random(), name)

        /** 既存プレイヤーの識別子が分かっている場合向け。 */
        fun new(id: Uuid, name: String): Participant {
            require(name.isNotBlank()) { "participant name must not be blank" }
            return Participant(id, name)
        }
    }
}

/** Paper の Location を含まない純粋な座標値。 */
data class WorldPosition private constructor(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f
) {
    companion object {
        fun new(
            world: String,
            x: Double,
            y: Double,
            z: Double,
            yaw: Float = 0f,
            pitch: Float = 0f
        ): WorldPosition {
            require(world.isNotBlank()) { "world name must not be blank" }
            require(x.isFinite() && y.isFinite() && z.isFinite()) {
                "coordinates must be finite (x=$x y=$y z=$z)"
            }
            require(yaw.isFinite() && pitch.isFinite()) {
                "yaw/pitch must be finite (yaw=$yaw pitch=$pitch)"
            }
            return WorldPosition(world, x, y, z, yaw, pitch)
        }
    }
}

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
