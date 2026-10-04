package net.ninebolt.ketto.infrastructure.paper.command

import net.ninebolt.ketto.application.ArenaAdministrationService
import net.ninebolt.ketto.application.RemoveError
import net.ninebolt.ketto.infrastructure.paper.message.Message
import net.ninebolt.ketto.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaRemoveCommand(
    private val administration: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        when (administration.remove(arenaName)) {
            null -> messenger.send(sender, Message.ArenaRemoved(arenaName))
            RemoveError.NotFound -> messenger.send(sender, Message.ArenaNotFound)
        }
    }
}
