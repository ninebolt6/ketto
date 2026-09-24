package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.CreateError
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaCreateCommand(
    admin: ArenaAdministrationService,
    messenger: Messenger,
) : ArenaSubcommand(admin, messenger) {

    override val usage: Message = Message.UsageCreate

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        if (rest.isNotEmpty()) {
            messenger.send(sender, usage)
            return
        }
        when (admin.create(arenaName)) {
            null -> messenger.send(sender, Message.ArenaCreated(arenaName))
            CreateError.AlreadyExists -> messenger.send(sender, Message.ArenaExists)
            CreateError.InvalidName -> messenger.send(sender, usage)
        }
    }
}
