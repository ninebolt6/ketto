package net.ninebolt.ketto.infrastructure.paper

import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEvent

internal fun PlayerInteractEvent.denyUse() {
    setUseInteractedBlock(Event.Result.DENY)
    setUseItemInHand(Event.Result.DENY)
}
