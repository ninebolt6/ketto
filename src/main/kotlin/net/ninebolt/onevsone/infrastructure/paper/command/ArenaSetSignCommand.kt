package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import net.ninebolt.onevsone.infrastructure.paper.toWorldPosition
import org.bukkit.block.Sign
import org.bukkit.command.CommandSender

internal class ArenaSetSignCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override val requiresPlayer: Boolean = true
    override val usage: Msg = messages.usageSetSign

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        val player = sender.requirePlayer() ?: return
        if (rest.isNotEmpty()) {
            messages.send(sender, usage)
            return
        }
        val arena = arenaOrWarn(sender, arenaName) ?: return
        val target = player.getTargetBlockExact(10)
        if (target == null || target.state !is Sign) {
            messages.send(sender, messages.lookAtSign)
            return
        }
        val existing = admin.signOwner(target.world.name, target.x, target.y, target.z)
        if (existing != null && existing != arena.name) {
            messages.send(sender, messages.signTaken)
            return
        }
        target.location.toWorldPosition()?.let { admin.setSign(arena.name, it) }
    }
}
