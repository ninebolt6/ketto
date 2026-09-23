package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/** Persistence for status/<name>.yml and the participation registration (players/arena sections) in players.yml. */
class YamlMatchStateRepository(private val store: YamlStore) : MatchStateRepository {

    /**
     * Rewrites this arena's registrations to the current participants, then
     * saves the status file. Rows pointing at this arena but absent from the
     * match are removed; the two writes are adjacent but not atomic.
     */
    override fun persistMatch(match: ArenaMatch) {
        store.update(store.playersFile) { yaml ->
            val arena = match.arenaId.name
            val current = match.participants.mapTo(HashSet()) { it.name }
            val players = yaml.getStringList("players")
            // Drop registrations that point at this arena but are no longer participants
            (yaml.getConfigurationSection("arena")?.getKeys(false) ?: emptySet())
                .filter { it !in current && yaml.getString("arena.$it") == arena }
                .forEach {
                    players.remove(it)
                    yaml.set("arena.$it", null)
                }
            current.forEach { name ->
                if (!players.contains(name)) players.add(name)
                yaml.set("arena.$name", arena)
            }
            yaml.set("players", players)
        }
        saveStatus(match)
    }

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

    fun registerParticipant(participant: Participant, arena: Arena.Id) {
        store.update(store.playersFile) { yaml ->
            val players = yaml.getStringList("players")
            if (!players.contains(participant.name)) players.add(participant.name)
            yaml.set("players", players)
            yaml.set("arena.${participant.name}", arena.name)
        }
    }

    /** Removes only the participation registration. Backups (inv.*) are untouched. */
    fun unregisterParticipant(playerName: String) {
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
