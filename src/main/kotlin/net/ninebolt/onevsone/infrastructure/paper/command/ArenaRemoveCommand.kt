package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.RemoveError
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaRemoveCommand(
    private val admin: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        when (admin.remove(arenaName)) {
            null -> messenger.send(sender, Message.ArenaRemoved(arenaName))
            RemoveError.NotFound -> messenger.send(sender, Message.ArenaNotFound)
        }
    }
}
