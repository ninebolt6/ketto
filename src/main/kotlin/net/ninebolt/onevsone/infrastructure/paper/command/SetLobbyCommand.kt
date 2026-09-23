package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.LobbyService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.paper.toWorldPosition
import org.bukkit.command.CommandSender

internal class SetLobbyCommand(
    private val lobby: LobbyService,
    messenger: Messenger
) : AbstractSubcommand(messenger) {

    override fun execute(sender: CommandSender, args: List<String>) {
        if (sender.denyUnlessOp()) return
        val player = sender.requirePlayer() ?: return
        val position = player.location.toWorldPosition()
        if (position != null) {
            lobby.setLobby(position)
            messenger.send(player, Message.LobbySet)
        }
    }
}
