package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaSignRemoveCommand(
    private val admin: ArenaAdministrationService,
    private val signs: ArenaSignService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        val arena = arenaOrWarn(admin, messenger, sender, arenaName) ?: return
        if (signs.signLocation(arena.name) == null) {
            messenger.send(sender, Message.SignNotRegistered)
            return
        }
        signs.clearSign(arena.name)
        messenger.send(sender, Message.SignRemoved(arena.name))
    }
}
