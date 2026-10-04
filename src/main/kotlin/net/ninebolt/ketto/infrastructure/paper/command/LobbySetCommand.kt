package net.ninebolt.ketto.infrastructure.paper.command

import net.ninebolt.ketto.application.LobbyService
import net.ninebolt.ketto.infrastructure.paper.message.Message
import net.ninebolt.ketto.infrastructure.paper.message.Messenger
import net.ninebolt.ketto.infrastructure.paper.toWorldPosition
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
