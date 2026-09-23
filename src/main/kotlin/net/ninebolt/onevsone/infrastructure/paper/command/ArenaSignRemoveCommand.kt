package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

/** `arena <name> sign remove`. Unregisters the arena's join sign. */
internal class ArenaSignRemoveCommand(
    admin: ArenaAdministrationService,
    private val signs: ArenaSignService,
    messenger: Messenger
) : ArenaSubcommand(admin, messenger) {

    override val usage: Message = Message.UsageSignRemove

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        if (rest.isNotEmpty()) {
            messenger.send(sender, usage)
            return
        }
        val arena = arenaOrWarn(sender, arenaName) ?: return
        if (signs.signLocation(arena.name) == null) {
            messenger.send(sender, Message.SignNotRegistered)
            return
        }
        signs.clearSign(arena.name)
        messenger.send(sender, Message.SignRemoved(arena.name))
    }
}
