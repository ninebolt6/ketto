package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.JoinReply
import net.ninebolt.onevsone.domain.Arena
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
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
        // 未waxの看板はバニラの右クリックで誰でも編集画面を開けるため、処理済みのクリックは
        // ブロック操作とアイテム使用の両方を拒否する
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        // joinable の事前判定は行わず、join の拒否結果(InMatch 等)の描画に委ねる
        val reply = Arena.Id.of(name)
            ?.let { service.join(event.player.uniqueId.toKotlinUuid(), event.player.name, it) }
            ?: JoinReply.NotFound
        renderJoin(event.player, name, reply)
    }

    /** 登録中の看板は誰も壊せない。解除は /1vs1 arena removesign か arena remove のみ。 */
    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        if (isRegisteredSign(event.block)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf(::isRegisteredSign)
    }

    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf(::isRegisteredSign)
    }

    /** 登録済みアリーナの参加看板か。 */
    private fun isRegisteredSign(block: Block): Boolean =
        block.state is Sign && admin.signOwner(block.world.name, block.x, block.y, block.z) != null

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
