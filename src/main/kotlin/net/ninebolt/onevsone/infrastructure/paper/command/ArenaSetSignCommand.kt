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

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        if (sender.denyUnlessOp()) return null
        val player = sender.requirePlayer() ?: return null
        if (args.size != 1) return messages.usageSetSign
        val arena = arenaOrWarn(sender, args[0]) ?: return null
        val target = player.getTargetBlockExact(10)
        if (target == null || target.state !is Sign) {
            messages.send(sender, messages.lookAtSign)
            return null
        }
        val existing = admin.signOwner(target.world.name, target.x, target.y, target.z)
        if (existing != null && existing != arena.name) {
            messages.send(sender, messages.signTaken)
            return null
        }
        target.location.toWorldPosition()?.let { admin.setSign(arena.name, it) }
        return null
    }
}
