package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ToggleReply
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender

/** enable / disable。enabled が usage と応答メッセージを切り替える。 */
internal class ArenaSetEnabledCommand(
    private val enabled: Boolean,
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override fun execute(sender: CommandSender, args: List<String>): String? {
        if (sender.denyUnlessOp()) return null
        if (args.size != 1) {
            return if (enabled) messages.usageEnable else messages.usageDisable
        }
        when (admin.setEnabled(args[0], enabled)) {
            ToggleReply.NotFound -> messages.send(sender, messages.noArena)
            ToggleReply.AlreadyEnabled -> messages.send(sender, messages.alreadyEnabled)
            ToggleReply.AlreadyDisabled -> messages.send(sender, messages.alreadyDisabled)
            ToggleReply.Changed -> messages.send(sender, if (enabled) messages.enabled(args[0]) else messages.disabled(args[0]))
        }
        return null
    }
}
