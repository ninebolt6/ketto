package net.ninebolt.ketto.infrastructure.paper.command

import net.ninebolt.ketto.application.ArenaAdministrationService
import net.ninebolt.ketto.application.ArenaSignService
import net.ninebolt.ketto.infrastructure.paper.message.Message
import net.ninebolt.ketto.infrastructure.paper.message.Messenger
import net.ninebolt.ketto.infrastructure.paper.toBlockPosition
import org.bukkit.block.Sign
import org.bukkit.entity.Player

internal class ArenaSignSetCommand(
    private val administration: ArenaAdministrationService,
    private val signService: ArenaSignService,
    private val messenger: Messenger,
) {

    fun execute(player: Player, arenaName: String) {
        val id = administration.findArenaId(arenaName) ?: run {
            messenger.send(player, Message.ArenaNotFound)
            return
        }
        val target = player.getTargetBlockExact(10)
        if (target == null || target.state !is Sign) {
            messenger.send(player, Message.SignLookAt)
            return
        }
        if (!signService.setSign(id, target.toBlockPosition())) {
            messenger.send(player, Message.SignTaken)
        }
    }
}
