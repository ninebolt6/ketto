package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.EnableError
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaEnableCommand(
    private val admin: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        when (val error = admin.enable(arenaName)) {
            null -> messenger.send(sender, Message.ArenaEnabled(arenaName))
            EnableError.NotFound -> messenger.send(sender, Message.ArenaNotFound)
            EnableError.AlreadyEnabled -> messenger.send(sender, Message.ArenaAlreadyEnabled)
            is EnableError.MissingSpawns -> messenger.send(sender, Message.ArenaMissingSpawns(arenaName, error.slots))
        }
    }
}
