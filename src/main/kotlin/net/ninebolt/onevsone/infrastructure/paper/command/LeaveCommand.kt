package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.LeaveReply
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender
import kotlin.uuid.toKotlinUuid

internal class LeaveCommand(
    private val service: ArenaApplicationService,
    messages: Messages
) : AbstractSubcommand(messages) {

    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>) {
        val player = sender.requirePlayer() ?: return
        when (service.leave(player.uniqueId.toKotlinUuid())) {
            LeaveReply.Left -> messages.send(player, messages.leftArena)
            LeaveReply.NotWaiting -> messages.send(player, messages.cannotLeave)
            LeaveReply.NotJoined -> messages.send(player, messages.notJoined)
        }
    }
}
