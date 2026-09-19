package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant
import org.bukkit.configuration.file.YamlConfiguration

/** status/<name>.yml と players.yml の参加登録(players/arena セクション)の永続化。 */
class YamlMatchStateRepository(private val store: YamlStore) : MatchStateRepository {

    override fun saveStatus(match: ArenaMatch) {
        val yaml = YamlConfiguration()
        yaml.set("status", match.state.name)
        yaml.set("players", match.participants.map { it.name })
        val winMap = mutableMapOf<String, Int>()
        match.wins.forEach { (id, wins) ->
            val name = match.participants.firstOrNull { it.id == id }?.name ?: return@forEach
            winMap[name] = wins
        }
        yaml.set("win", winMap)
        store.save(yaml, store.statusFile(match.arenaId.name))
    }

    override fun registerParticipant(participant: Participant, arena: ArenaId) {
        val yaml = store.load(store.playersFile)
        val players = yaml.getStringList("players")
        if (!players.contains(participant.name)) players.add(participant.name)
        yaml.set("players", players)
        yaml.set("arena.${participant.name}", arena.name)
        store.save(yaml, store.playersFile)
    }

    /** 参加登録だけを解除する。バックアップ(inv.*)には触れない。 */
    override fun unregisterParticipant(playerName: String) {
        val yaml = store.load(store.playersFile)
        val players = yaml.getStringList("players")
        players.remove(playerName)
        yaml.set("players", players)
        yaml.set("arena.$playerName", null)
        store.save(yaml, store.playersFile)
    }

    override fun clearRegistrations() {
        val yaml = store.load(store.playersFile)
        yaml.set("players", emptyList<String>())
        yaml.set("arena", null)
        store.save(yaml, store.playersFile)
    }
}
