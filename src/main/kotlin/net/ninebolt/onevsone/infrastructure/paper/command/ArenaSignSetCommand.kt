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
        val arena = arenaOrWarn(admin, messenger, player, arenaName) ?: return
        val target = player.getTargetBlockExact(10)
        if (target == null || target.state !is Sign) {
            messenger.send(player, Message.SignLookAt)
            return
        }
        val position = target.toBlockPosition()
        val existing = signs.signOwner(position)
        if (existing != null && existing != arena.name) {
            messenger.send(player, Message.SignTaken)
            return
        }
        signs.setSign(arena.name, position)
    }
}
