package net.ninebolt.ketto.infrastructure.paper

import net.ninebolt.ketto.application.ArenaSignService
import net.ninebolt.ketto.application.JoinOutput
import net.ninebolt.ketto.application.MatchParticipationService
import net.ninebolt.ketto.infrastructure.paper.message.Message
import net.ninebolt.ketto.infrastructure.paper.message.Messenger
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

class ArenaSignListener(
    private val participation: MatchParticipationService,
    private val signService: ArenaSignService,
    private val messenger: Messenger,
) : Listener {

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.state !is Sign) return
        val id = signService.findSignOwner(block.toBlockPosition()) ?: return
        // Vanilla lets anyone open the sign edit screen by right-clicking an unwaxed sign
        event.denyUse()
        val output = participation.join(event.player.uniqueId.toKotlinUuid(), event.player.name, id)
        renderJoin(event.player, id.name, output)
    }

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

    private fun isRegisteredSign(block: Block): Boolean = block.state is Sign && signService.findSignOwner(block.toBlockPosition()) != null

    private fun renderJoin(player: Player, arenaName: String, output: JoinOutput) {
        when (output) {
            JoinOutput.JoinedWaiting -> {
                messenger.send(player, Message.MatchJoined(arenaName))
                messenger.send(player, Message.MatchWaitOneMore)
            }

            JoinOutput.JoinedStarting -> messenger.send(player, Message.MatchJoined(arenaName))

            JoinOutput.AlreadyJoined -> messenger.send(player, Message.MatchAlreadyJoined)

            JoinOutput.NotEnabled -> messenger.send(player, Message.ArenaNotEnabled)

            JoinOutput.Rejected -> messenger.send(player, Message.MatchInGame)

            JoinOutput.RestorePending -> messenger.send(player, Message.MatchRestorePending)

            JoinOutput.NotFound -> messenger.send(player, Message.ArenaNotFound)
        }
    }
}
