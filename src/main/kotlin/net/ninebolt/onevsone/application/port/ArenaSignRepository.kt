package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.WorldPosition

/**
 * Join 看板の配置情報の永続化。アリーナ名 ⇔ 看板座標の対応を管理する。
 */
interface ArenaSignRepository {
    fun signLocation(arenaName: String): WorldPosition?
    fun setSign(arenaName: String, position: WorldPosition)
    fun clearSign(arenaName: String)
    /** 座標から看板の所有アリーナ名を逆引きする。 */
    fun signOwner(world: String, x: Double, y: Double, z: Double): String?
}
