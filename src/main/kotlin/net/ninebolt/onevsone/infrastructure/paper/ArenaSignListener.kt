package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.JoinReply
import net.ninebolt.onevsone.domain.Arena
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.entity.Player
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
 * Event side of join signs: joining via clicks and protecting registered signs
 * from destruction. Registration/coordinate persistence is
 * ArenaSignRepository's job; display updates are MatchPresentationPort's.
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
        // A handled click denies both block interaction and item use, because anyone can
        // open the edit screen by right-clicking an unwaxed sign in vanilla
        event.denyUse()
        // No joinable pre-check; leave the outcome to join's rejection result rendering (InMatch etc.)
        val reply = Arena.Id.of(name)
            ?.let { service.join(event.player.uniqueId.toKotlinUuid(), event.player.name, it) }
            ?: JoinReply.NotFound
        renderJoin(event.player, name, reply)
    }

    /** Nobody can break a registered sign. Removal is only via /1vs1 arena removesign or arena remove. */
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

    private fun isRegisteredSign(block: Block): Boolean =
        block.state is Sign && admin.signOwner(block.world.name, block.x, block.y, block.z) != null

    /** Maps the join use-case result to message text. Shared by the sign-join path. */
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
