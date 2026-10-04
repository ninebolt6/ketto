package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import java.util.Locale
import kotlin.uuid.Uuid

class InMemoryArenaRepository :
    ArenaRepository,
    LobbyRepository,
    ArenaSignRepository {
    val names = mutableListOf<String>()
    val definitions = mutableMapOf<String, Arena>()
    var lobbyPosition: WorldPosition? = null
    val signs = mutableMapOf<Arena.Id, BlockPosition>()
    var failOnSave = false
    var failOnLoad = false
    var failOnSignRead = false

    override fun loadAll(): List<Arena> {
        if (failOnLoad) throw PersistenceException("load failed")
        val seen = mutableSetOf<String>()
        return names.mapNotNull { name ->
            if (Arena.Id.of(name) == null || !seen.add(name.lowercase(Locale.ROOT))) null else find(name)
        }
    }

    fun find(name: String): Arena = definitions[name] ?: Arena.Disabled.new(arenaId(name))

    override fun save(arena: Arena) {
        if (failOnSave) throw PersistenceException("save failed")
        definitions[arena.name] = arena
        if (names.none { it.equals(arena.name, ignoreCase = true) }) names += arena.name
    }

    override fun delete(id: Arena.Id) {
        definitions.keys.removeIf { it.equals(id.name, ignoreCase = true) }
        names.removeIf { it.equals(id.name, ignoreCase = true) }
    }

    override fun findLobby(): WorldPosition? = lobbyPosition
    override fun setLobby(position: WorldPosition) {
        lobbyPosition = position
    }

    override fun findSignLocation(arena: Arena.Id): BlockPosition? {
        if (failOnSignRead) throw PersistenceException("sign read failed")
        return signs[arena]
    }
    override fun setSign(arena: Arena.Id, position: BlockPosition) {
        signs[arena] = position
    }

    override fun clearSign(arena: Arena.Id) {
        signs.remove(arena)
    }

    override fun findSignOwner(position: BlockPosition): Arena.Id? = signs.entries.firstOrNull { (_, pos) -> pos == position }?.key
}

class InMemoryPlayerStatsRepository : PlayerStatsRepository {
    val stats = mutableMapOf<Uuid, PlayerStats>()
    var failOnSaveFor: Uuid? = null
    var failOnFind: Throwable? = null

    override fun find(playerId: Uuid): PlayerStats? {
        failOnFind?.let { throw it }
        return stats[playerId]
    }

    override fun save(stats: PlayerStats) {
        if (stats.playerId == failOnSaveFor) throw PersistenceException("save failed")
        this.stats[stats.playerId] = stats
    }
}
