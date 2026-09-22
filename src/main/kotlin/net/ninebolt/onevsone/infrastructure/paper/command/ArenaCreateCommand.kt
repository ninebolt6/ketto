package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender

internal class ArenaCreateCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override val usage: Msg = messages.usageCreate

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        if (rest.isNotEmpty() || Arena.Id.of(arenaName) == null) {
            messages.send(sender, usage)
            return
        }
        if (!admin.create(arenaName)) {
            messages.send(sender, messages.arenaExists)
            return
        }
        messages.send(sender, messages.created(arenaName))
    }
}
