package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.CreateError
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaCreateCommand(
    private val administration: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        when (administration.create(arenaName)) {
            null -> messenger.send(sender, Message.ArenaCreated(arenaName))
            CreateError.AlreadyExists -> messenger.send(sender, Message.ArenaExists)
            CreateError.InvalidName -> messenger.send(sender, Message.UsageCreate)
        }
    }
}
