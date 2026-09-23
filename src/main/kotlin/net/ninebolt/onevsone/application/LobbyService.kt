package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.domain.WorldPosition

/**
 * The server-wide lobby setting. A single lobby exists, not per arena.
 * Calls are serialized by the main thread; each repository call is one
 * atomic persistence unit.
 */
class LobbyService(private val lobby: LobbyRepository) {
    fun setLobby(position: WorldPosition) {
        lobby.setLobby(position)
    }
}
