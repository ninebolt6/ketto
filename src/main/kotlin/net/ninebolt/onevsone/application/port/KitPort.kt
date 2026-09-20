package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.Arena
import kotlin.uuid.Uuid

/**
 * アリーナ装備(キット)の適用・保存。ItemStack 実データは infrastructure 内に閉じ込める。
 */
interface KitPort {
    fun applyKit(arena: Arena.Id, playerId: Uuid)

    /** プレイヤーの現在装備をアリーナ装備として保存(setInv)。 */
    fun saveKit(arena: Arena.Id, playerId: Uuid)

    /** アリーナ削除時に保持中の装備を破棄する。同名で作り直しても古い装備を適用しない。 */
    fun forgetKit(arena: Arena.Id)
}
