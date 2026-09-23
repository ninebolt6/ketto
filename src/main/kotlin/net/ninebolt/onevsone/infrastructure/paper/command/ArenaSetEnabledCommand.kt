package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ToggleError
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

/** enable / disable. `enabled` switches the usage and reply messages. */
internal class ArenaSetEnabledCommand(
    private val enabled: Boolean,
    admin: ArenaAdministrationService,
    messenger: Messenger
) : ArenaSubcommand(admin, messenger) {

    override val usage: Message get() = if (enabled) Message.UsageEnable else Message.UsageDisable

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        if (rest.isNotEmpty()) {
            messenger.send(sender, usage)
            return
        }
        when (admin.setEnabled(arenaName, enabled)) {
            null -> messenger.send(sender, if (enabled) Message.ArenaEnabled(arenaName) else Message.ArenaDisabled(arenaName))
            ToggleError.NotFound -> messenger.send(sender, Message.ArenaNotFound)
            ToggleError.AlreadyEnabled -> messenger.send(sender, Message.ArenaAlreadyEnabled)
            ToggleError.AlreadyDisabled -> messenger.send(sender, Message.ArenaAlreadyDisabled)
        }
    }
}
