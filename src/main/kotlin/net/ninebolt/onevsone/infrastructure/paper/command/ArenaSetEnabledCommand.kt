package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ToggleError
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaSetEnabledCommand(
    private val enabled: Boolean,
    private val admin: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        when (admin.setEnabled(arenaName, enabled)) {
            null -> messenger.send(sender, if (enabled) Message.ArenaEnabled(arenaName) else Message.ArenaDisabled(arenaName))
            ToggleError.NotFound -> messenger.send(sender, Message.ArenaNotFound)
            ToggleError.AlreadyEnabled -> messenger.send(sender, Message.ArenaAlreadyEnabled)
            ToggleError.AlreadyDisabled -> messenger.send(sender, Message.ArenaAlreadyDisabled)
        }
    }
}
