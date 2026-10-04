package net.ninebolt.ketto.infrastructure.paper.command

import net.ninebolt.ketto.application.ArenaAdministrationService
import net.ninebolt.ketto.application.EnableError
import net.ninebolt.ketto.infrastructure.paper.message.Message
import net.ninebolt.ketto.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaEnableCommand(
    private val administration: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        when (val error = administration.enable(arenaName)) {
            null -> messenger.send(sender, Message.ArenaEnabled(arenaName))
            EnableError.NotFound -> messenger.send(sender, Message.ArenaNotFound)
            EnableError.AlreadyEnabled -> messenger.send(sender, Message.ArenaAlreadyEnabled)
            is EnableError.MissingSpawns -> messenger.send(sender, Message.ArenaMissingSpawns(arenaName, error.slots))
        }
    }
}
