package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.WorldPosition

interface LobbyRepository {
    fun findLobby(): WorldPosition?
    fun setLobby(position: WorldPosition)
}
