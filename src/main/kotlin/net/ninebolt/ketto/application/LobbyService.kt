package net.ninebolt.ketto.application

import net.ninebolt.ketto.application.port.LobbyRepository
import net.ninebolt.ketto.domain.WorldPosition

class LobbyService(private val lobbyRepository: LobbyRepository) {
    fun setLobby(position: WorldPosition) {
        lobbyRepository.setLobby(position)
    }
}
