package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ToggleReply
import net.ninebolt.onevsone.infrastructure.paper.Message
import net.ninebolt.onevsone.infrastructure.paper.Messenger
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
            ToggleReply.NotFound -> messenger.send(sender, Message.ArenaNotFound)
            ToggleReply.AlreadyEnabled -> messenger.send(sender, Message.ArenaAlreadyEnabled)
            ToggleReply.AlreadyDisabled -> messenger.send(sender, Message.ArenaAlreadyDisabled)
            ToggleReply.Changed -> messenger.send(sender, if (enabled) Message.ArenaEnabled(arenaName) else Message.ArenaDisabled(arenaName))
        }
    }
}
