package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/** Persistence for status/<name>.yml and the participation registration (players/arena sections) in players.yml. */
class YamlMatchStateRepository(private val store: YamlStore) : MatchStateRepository {

    override fun saveStatus(match: ArenaMatch) {
        store.rewrite(store.statusFile(match.arenaId.name)) { yaml ->
            yaml.set("status", match.state.name)
            yaml.set("players", match.participants.map { it.name })
            val winMap = match.wins.mapNotNull { (id, wins) ->
                match.participants.firstOrNull { it.id == id }?.name?.let { it to wins }
            }.toMap()
            yaml.set("win", winMap)
        }
    }

    override fun registerParticipant(participant: Participant, arena: Arena.Id) {
        store.update(store.playersFile) { yaml ->
            val players = yaml.getStringList("players")
            if (!players.contains(participant.name)) players.add(participant.name)
            yaml.set("players", players)
            yaml.set("arena.${participant.name}", arena.name)
        }
    }

    /** Removes only the participation registration. Backups (inv.*) are untouched. */
    override fun unregisterParticipant(playerName: String) {
        store.update(store.playersFile) { yaml ->
            val players = yaml.getStringList("players")
            players.remove(playerName)
            yaml.set("players", players)
            yaml.set("arena.$playerName", null)
        }
    }

    override fun clearRegistrations() {
        store.update(store.playersFile) { yaml ->
            yaml.set("players", emptyList<String>())
            yaml.set("arena", null)
        }
    }
}
