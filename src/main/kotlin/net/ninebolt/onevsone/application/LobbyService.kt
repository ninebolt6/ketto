package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.domain.WorldPosition

/**
 * The server-wide lobby setting. A single lobby exists, not per arena.
 * All operations are assumed to be serialized on the main thread.
 */
class LobbyService(private val lobby: LobbyRepository) {
    fun setLobby(position: WorldPosition) {
        lobby.setLobby(position)
    }
}
