package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.domain.WorldPosition

/** lobby.yml のロビー座標の永続化。 */
class YamlLobbyRepository(private val store: YamlStore) : LobbyRepository {

    override fun lobby(): WorldPosition? =
        store.readLocation(store.load(store.lobbyFile), "lobby")

    override fun setLobby(position: WorldPosition) {
        val yaml = store.load(store.lobbyFile)
        store.writeLocation(yaml, "lobby", position)
        store.save(yaml, store.lobbyFile)
    }
}
