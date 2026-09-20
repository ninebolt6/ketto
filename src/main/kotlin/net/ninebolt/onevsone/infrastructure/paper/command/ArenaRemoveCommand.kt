package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender

internal class ArenaRemoveCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        if (sender.denyUnlessOp()) return null
        if (args.size != 1) return messages.usageRemove
        if (!admin.remove(args[0])) {
            messages.send(sender, messages.noArena)
            return null
        }
        messages.send(sender, messages.removed(args[0]))
        return null
    }
}
