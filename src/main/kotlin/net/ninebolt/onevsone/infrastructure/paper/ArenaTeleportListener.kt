package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.domain.ParticipantRestrictions
import net.ninebolt.onevsone.domain.TeleportTrigger
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import kotlin.uuid.toKotlinUuid

/**
 * テレポート逃走と乗車バイパスを遮断する入力アダプター。
 * 状態別の許可判定は domain の TeleportRestriction に委譲する。
 */
class ArenaTeleportListener(
    private val service: ArenaApplicationService,
    private val lookup: PaperPlayerLookup
) : Listener {

    @EventHandler
    fun onTeleport(event: PlayerTeleportEvent) {
        restrictTeleport(event)
    }

    // PlayerPortalEvent は独自 HandlerList を持ち PlayerTeleportEvent には届かない
    @EventHandler
    fun onPortal(event: PlayerPortalEvent) {
        restrictTeleport(event)
    }

    private fun restrictTeleport(event: PlayerTeleportEvent) {
        val restrictions = restrictionsOf(event.player) ?: return
        // プラグイン自身の移送は cause が PLUGIN とは限らないため、マーカーで先に識別する
        val trigger = when {
            lookup.isPluginTeleport(event.player.uniqueId.toKotlinUuid()) -> TeleportTrigger.INTERNAL
            event.cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL -> TeleportTrigger.ENDER_PEARL
            else -> TeleportTrigger.EXTERNAL
        }
        if (!restrictions.teleportRestriction.allows(trigger)) event.isCancelled = true
    }

    /** 移動凍結中の乗車は水平移動をバイパスするため遮断する。 */
    @EventHandler
    fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? Player ?: return
        if (restrictionsOf(player)?.horizontalMoveFrozen == true) event.isCancelled = true
    }

    private fun restrictionsOf(player: Player): ParticipantRestrictions? =
        service.matchOf(player.uniqueId.toKotlinUuid())
            ?.let { ParticipantRestrictions.forState(it.state) }
}
