package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.domain.WorldPosition

/** Persistence for the lobby position in lobby.yml. */
class YamlLobbyRepository(private val store: YamlStore) : LobbyRepository {

    override fun lobby(): WorldPosition? =
        store.readLocation(store.load(store.lobbyFile), "lobby")

    override fun setLobby(position: WorldPosition) {
        store.rewrite(store.lobbyFile) { yaml ->
            store.writeLocation(yaml, "lobby", position)
        }
    }
}
