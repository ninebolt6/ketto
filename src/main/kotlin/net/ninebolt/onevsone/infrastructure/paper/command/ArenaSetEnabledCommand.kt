package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ToggleReply
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender

/** enable / disable. `enabled` switches the usage and reply messages. */
internal class ArenaSetEnabledCommand(
    private val enabled: Boolean,
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override val usage: Msg get() = if (enabled) messages.usageEnable else messages.usageDisable

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        if (rest.isNotEmpty()) {
            messages.send(sender, usage)
            return
        }
        when (admin.setEnabled(arenaName, enabled)) {
            ToggleReply.NotFound -> messages.send(sender, messages.noArena)
            ToggleReply.AlreadyEnabled -> messages.send(sender, messages.alreadyEnabled)
            ToggleReply.AlreadyDisabled -> messages.send(sender, messages.alreadyDisabled)
            ToggleReply.Changed -> messages.send(sender, if (enabled) messages.enabled(arenaName) else messages.disabled(arenaName))
        }
    }
}
