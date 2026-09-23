package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Message
import net.ninebolt.onevsone.infrastructure.paper.Messenger
import org.bukkit.command.CommandSender

internal class ArenaRemoveCommand(
    admin: ArenaAdministrationService,
    messenger: Messenger
) : ArenaSubcommand(admin, messenger) {

    override val usage: Message = Message.UsageRemove

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        if (rest.isNotEmpty()) {
            messenger.send(sender, usage)
            return
        }
        if (!admin.remove(arenaName)) {
            messenger.send(sender, Message.ArenaNotFound)
            return
        }
        messenger.send(sender, Message.ArenaRemoved(arenaName))
    }
}
