package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender

internal class ArenaRemoveSignCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override fun execute(sender: CommandSender, args: List<String>): String? {
        if (sender.denyUnlessOp()) return null
        if (args.size != 1) return messages.usageRemoveSign
        if (definitionOrWarn(sender, args[0]) == null) return null
        if (admin.signLocation(args[0]) == null) {
            messages.send(sender, messages.signNotRegistered)
            return null
        }
        admin.clearSign(args[0])
        messages.send(sender, messages.signRemoved(args[0]))
        return null
    }
}
