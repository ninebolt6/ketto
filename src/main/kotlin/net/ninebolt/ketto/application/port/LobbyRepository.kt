package net.ninebolt.ketto.application.port

import net.ninebolt.ketto.domain.WorldPosition

interface LobbyRepository {
    fun findLobby(): WorldPosition?
    fun setLobby(position: WorldPosition)
}
