package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.domain.WorldPosition

class LobbyService(private val lobby: LobbyRepository) {
    fun setLobby(position: WorldPosition) {
        lobby.setLobby(position)
    }
}
