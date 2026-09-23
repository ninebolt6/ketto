package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.infrastructure.paper.Message
import net.ninebolt.onevsone.infrastructure.paper.Messenger
import org.bukkit.command.CommandSender

internal class ArenaCreateCommand(
    admin: ArenaAdministrationService,
    messenger: Messenger
) : ArenaSubcommand(admin, messenger) {

    override val usage: Message = Message.UsageCreate

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        if (rest.isNotEmpty() || Arena.Id.of(arenaName) == null) {
            messenger.send(sender, usage)
            return
        }
        if (!admin.create(arenaName)) {
            messenger.send(sender, Message.ArenaExists)
            return
        }
        messenger.send(sender, Message.ArenaCreated(arenaName))
    }
}
