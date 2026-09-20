package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.ArenaId
import kotlin.uuid.Uuid

/**
 * アリーナ装備(キット)の適用・保存。ItemStack 実データは infrastructure 内に閉じ込める。
 */
interface KitPort {
    fun applyKit(arena: ArenaId, playerId: Uuid)

    /** プレイヤーの現在装備をアリーナ装備として保存(setInv)。 */
    fun saveKit(arena: ArenaId, playerId: Uuid)
}
