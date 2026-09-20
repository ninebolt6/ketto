package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender

internal class ArenaRemoveSignCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        if (sender.denyUnlessOp()) return null
        if (args.size != 1) return messages.usageRemoveSign
        val arena = arenaOrWarn(sender, args[0]) ?: return null
        if (admin.signLocation(arena.name) == null) {
            messages.send(sender, messages.signNotRegistered)
            return null
        }
        admin.clearSign(arena.name)
        messages.send(sender, messages.signRemoved(arena.name))
        return null
    }
}
