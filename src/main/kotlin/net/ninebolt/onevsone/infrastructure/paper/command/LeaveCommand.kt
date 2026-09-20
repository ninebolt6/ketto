package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.LeaveReply
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender

internal class LeaveCommand(
    private val service: ArenaApplicationService,
    messages: Messages
) : AbstractSubcommand(messages) {

    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        val player = sender.requirePlayer() ?: return null
        when (service.leave(player.uniqueId)) {
            LeaveReply.Left -> messages.send(player, messages.leftArena)
            LeaveReply.NotWaiting -> messages.send(player, messages.cannotLeave)
            LeaveReply.NotJoined -> messages.send(player, messages.notJoined)
        }
        return null
    }
}
