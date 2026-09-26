package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.DisableError
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaDisableCommand(
    private val admin: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        when (admin.disable(arenaName)) {
            null -> messenger.send(sender, Message.ArenaDisabled(arenaName))
            DisableError.NotFound -> messenger.send(sender, Message.ArenaNotFound)
            DisableError.AlreadyDisabled -> messenger.send(sender, Message.ArenaAlreadyDisabled)
        }
    }
}
