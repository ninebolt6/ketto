package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.MatchParticipationService
import net.ninebolt.onevsone.domain.TeleportTrigger
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import kotlin.uuid.toKotlinUuid

class ArenaTeleportListener(
    private val participation: MatchParticipationService,
    private val lookup: PaperPlayerLookup,
) : Listener {

    @EventHandler
    fun onTeleport(event: PlayerTeleportEvent) {
        restrictTeleport(event)
    }

    // PlayerPortalEvent has its own HandlerList and never reaches PlayerTeleportEvent
    @EventHandler
    fun onPortal(event: PlayerPortalEvent) {
        restrictTeleport(event)
    }

    private fun restrictTeleport(event: PlayerTeleportEvent) {
        val restrictions = participation.findRestrictions(event.player) ?: return
        // The plugin's own teleports do not always arrive with cause PLUGIN
        val trigger = when {
            lookup.isPluginTeleport(event.player.uniqueId.toKotlinUuid()) -> TeleportTrigger.PLUGIN
            event.cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL -> TeleportTrigger.ENDER_PEARL
            else -> TeleportTrigger.EXTERNAL
        }
        if (!restrictions.teleportRestriction.allows(trigger)) event.isCancelled = true
    }

    // Vehicle mounts move the player without firing PlayerMoveEvent
    @EventHandler
    fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? Player ?: return
        if (participation.findRestrictions(player)?.horizontalMoveFrozen == true) event.isCancelled = true
    }
}
