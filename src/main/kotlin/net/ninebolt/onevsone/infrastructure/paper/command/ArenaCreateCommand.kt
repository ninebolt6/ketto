package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender

internal class ArenaCreateCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        if (sender.denyUnlessOp()) return null
        if (args.size != 1 || Arena.Id.of(args[0]) == null) return messages.usageCreate
        if (!admin.create(args[0])) {
            messages.send(sender, messages.arenaExists)
            return null
        }
        messages.send(sender, messages.created(args[0]))
        return null
    }
}
