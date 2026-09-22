package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender

internal class ArenaRemoveCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override val usage: Msg = messages.usageRemove

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        if (rest.isNotEmpty()) {
            messages.send(sender, usage)
            return
        }
        if (!admin.remove(arenaName)) {
            messages.send(sender, messages.noArena)
            return
        }
        messages.send(sender, messages.removed(arenaName))
    }
}
