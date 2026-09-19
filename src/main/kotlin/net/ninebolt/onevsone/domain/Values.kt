package net.ninebolt.onevsone.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** 試合参加者。Bukkit の Player もインベントリも持たず、識別子と表示名だけを持つ。 */
data class Participant(val id: UUID, val name: String)

/** Paper の Location を含まない純粋な座標値。 */
data class WorldPosition(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f
)

/** アリーナの静的設定。試合状態(ArenaMatch)とは分離する。装備中身は infrastructure が保持する。 */
class ArenaDefinition(
    val id: ArenaId,
    var enabled: Boolean = false,
    var spawn1: WorldPosition? = null,
    var spawn2: WorldPosition? = null
) {
    val name: String get() = id.name

    fun spawn(slot: Int): WorldPosition? = if (slot == 0) spawn1 else spawn2
}

/** プレイヤー戦績。勝率の数値計算をここに集約する。 */
data class PlayerStats(val wins: Int, val losses: Int) {
    /** win/lose を小数第 2 位 HALF_UP で。lose == 0 のときは win / 1。 */
    val ratio: String
        get() = BigDecimal.valueOf(wins.toLong())
            .divide(BigDecimal.valueOf(losses.coerceAtLeast(1).toLong()), 2, RoundingMode.HALF_UP)
            .toPlainString()
}
