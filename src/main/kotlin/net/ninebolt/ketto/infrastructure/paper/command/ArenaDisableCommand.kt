package net.ninebolt.ketto.infrastructure.paper.command

import net.ninebolt.ketto.application.ArenaAdministrationService
import net.ninebolt.ketto.application.DisableError
import net.ninebolt.ketto.infrastructure.paper.message.Message
import net.ninebolt.ketto.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaDisableCommand(
    private val administration: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        when (administration.disable(arenaName)) {
            null -> messenger.send(sender, Message.ArenaDisabled(arenaName))
            DisableError.NotFound -> messenger.send(sender, Message.ArenaNotFound)
            DisableError.AlreadyDisabled -> messenger.send(sender, Message.ArenaAlreadyDisabled)
        }
    }
}
