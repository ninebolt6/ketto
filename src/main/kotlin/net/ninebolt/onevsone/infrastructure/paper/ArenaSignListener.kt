package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.JoinReply
import net.ninebolt.onevsone.domain.Arena
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import kotlin.uuid.toKotlinUuid

/**
 * 参加看板のイベント面。クリックによる参加と、登録中の看板の破壊防止を担う。
 * 登録・座標の永続化は ArenaSignRepository、表示更新は MatchPresentationPort の責務。
 */
class ArenaSignListener(
    private val service: ArenaApplicationService,
    private val admin: ArenaAdministrationService,
    private val messages: Messages
) : Listener {

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.state !is Sign) return
        val name = admin.signOwner(block.world.name, block.x, block.y, block.z) ?: return
        // joinable の事前判定は行わず、join の拒否結果(InMatch 等)の描画に委ねる
        renderJoin(event.player, name, service.join(event.player.uniqueId.toKotlinUuid(), event.player.name, Arena.Id(name)))
    }

    /** 登録中の看板は誰も壊せない。解除は /1vs1 arena removesign か arena remove のみ。 */
    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (block.state !is Sign) return
        if (admin.signOwner(block.world.name, block.x, block.y, block.z) != null) {
            event.isCancelled = true
        }
    }

    /** join ユースケース結果の文言変換。看板参加の経路で共有する。 */
    fun renderJoin(player: Player, arenaName: String, reply: JoinReply) {
        when (reply) {
            JoinReply.JoinedWaiting -> {
                messages.send(player, messages.joined(arenaName))
                messages.send(player, messages.waitOneMore)
            }
            JoinReply.JoinedStarting -> messages.send(player, messages.joined(arenaName))
            JoinReply.AlreadyJoined -> messages.send(player, messages.alreadyJoined)
            JoinReply.NotEnabled -> messages.send(player, messages.notEnabled)
            JoinReply.InMatch -> messages.send(player, messages.arenaInGame)
            JoinReply.NotFound -> messages.send(player, messages.noArena)
        }
    }
}
