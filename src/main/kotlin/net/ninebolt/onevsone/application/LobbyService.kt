package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.domain.WorldPosition

class LobbyService(private val lobbyRepository: LobbyRepository) {
    fun setLobby(position: WorldPosition) {
        lobbyRepository.setLobby(position)
    }
}
