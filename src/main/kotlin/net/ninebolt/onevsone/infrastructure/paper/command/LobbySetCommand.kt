package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.LobbyService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.paper.toWorldPosition
import org.bukkit.entity.Player

internal class LobbySetCommand(
    private val lobbyService: LobbyService,
    private val messenger: Messenger,
) {

    fun execute(player: Player) {
        lobbyService.setLobby(player.toWorldPosition())
        messenger.send(player, Message.LobbySet)
    }
}
