package net.ninebolt.onevsone.infrastructure.paper

import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEvent

/** A handled click (registered sign, storable block, etc.) denies both block interaction and item use. */
internal fun PlayerInteractEvent.denyUse() {
    setUseInteractedBlock(Event.Result.DENY)
    setUseItemInHand(Event.Result.DENY)
}
