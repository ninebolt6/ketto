package net.ninebolt.onevsone.infrastructure.paper

import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEvent

/** 処理済みのクリック(登録看板・預け入れブロック等)はブロック操作とアイテム使用の両方を拒否する。 */
internal fun PlayerInteractEvent.denyUse() {
    setUseInteractedBlock(Event.Result.DENY)
    setUseItemInHand(Event.Result.DENY)
}
