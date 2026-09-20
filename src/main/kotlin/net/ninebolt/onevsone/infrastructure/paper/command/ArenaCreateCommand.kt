package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender

internal class ArenaCreateCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override fun execute(sender: CommandSender, args: List<String>): String? {
        if (sender.denyUnlessOp()) return null
        if (args.size != 1 || !admin.isValidName(args[0])) return messages.usageCreate
        if (!admin.create(args[0])) {
            messages.send(sender, messages.arenaExists)
            return null
        }
        messages.send(sender, messages.created(args[0]))
        return null
    }
}
