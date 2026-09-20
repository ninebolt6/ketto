package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import net.ninebolt.onevsone.infrastructure.paper.toWorldPosition
import org.bukkit.command.CommandSender

internal class SetLobbyCommand(
    private val admin: ArenaAdministrationService,
    messages: Messages
) : AbstractSubcommand(messages) {

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        if (sender.denyUnlessOp()) return null
        val player = sender.requirePlayer() ?: return null
        val position = player.location.toWorldPosition()
        if (position != null) {
            admin.setLobby(position)
            messages.send(player, messages.lobbySet)
        }
        return null
    }
}
