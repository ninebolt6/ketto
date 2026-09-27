package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.paper.toBlockPosition
import org.bukkit.block.Sign
import org.bukkit.entity.Player

internal class ArenaSignSetCommand(
    private val admin: ArenaAdministrationService,
    private val signs: ArenaSignService,
    private val messenger: Messenger,
) {

    fun execute(player: Player, arenaName: String) {
        val id = admin.resolveArenaId(arenaName) ?: run {
            messenger.send(player, Message.ArenaNotFound)
            return
        }
        val target = player.getTargetBlockExact(10)
        if (target == null || target.state !is Sign) {
            messenger.send(player, Message.SignLookAt)
            return
        }
        if (!signs.setSign(id, target.toBlockPosition())) {
            messenger.send(player, Message.SignTaken)
        }
    }
}
