package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaSignRemoveCommand(
    private val administration: ArenaAdministrationService,
    private val signService: ArenaSignService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        val id = administration.resolveArenaId(arenaName) ?: run {
            messenger.send(sender, Message.ArenaNotFound)
            return
        }
        if (signService.clearSign(id)) {
            messenger.send(sender, Message.SignRemoved(id.name))
        } else {
            messenger.send(sender, Message.SignNotRegistered)
        }
    }
}
