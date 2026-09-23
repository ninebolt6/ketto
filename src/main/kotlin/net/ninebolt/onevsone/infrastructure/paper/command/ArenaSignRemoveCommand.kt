package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Message
import net.ninebolt.onevsone.infrastructure.paper.Messenger
import org.bukkit.command.CommandSender

/** `arena <name> sign remove`. Unregisters the arena's join sign. */
internal class ArenaSignRemoveCommand(
    admin: ArenaAdministrationService,
    messenger: Messenger
) : ArenaSubcommand(admin, messenger) {

    override val usage: Message = Message.UsageSignRemove

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        if (rest.isNotEmpty()) {
            messenger.send(sender, usage)
            return
        }
        val arena = arenaOrWarn(sender, arenaName) ?: return
        if (admin.signLocation(arena.name) == null) {
            messenger.send(sender, Message.SignNotRegistered)
            return
        }
        admin.clearSign(arena.name)
        messenger.send(sender, Message.SignRemoved(arena.name))
    }
}
