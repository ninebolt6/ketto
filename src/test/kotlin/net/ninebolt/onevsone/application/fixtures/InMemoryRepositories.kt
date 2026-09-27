package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
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
    val signs = mutableMapOf<String, BlockPosition>()
    var failOnSave = false
    var failOnLoad = false

    override fun loadAll(): List<Arena> {
        if (failOnLoad) throw PersistenceFailure("load failed")
        val seen = mutableSetOf<String>()
        return names.mapNotNull { name ->
            if (Arena.Id.of(name) == null || !seen.add(name.lowercase(Locale.ROOT))) null else find(name)
        }
    }

    override fun find(name: String): Arena = definitions[name] ?: Arena.Disabled.new(arenaId(name))

    override fun save(arena: Arena) {
        if (failOnSave) throw PersistenceFailure("save failed")
        definitions[arena.name] = arena
        if (names.none { it.equals(arena.name, ignoreCase = true) }) names += arena.name
    }

    override fun delete(name: String) {
        definitions.remove(name)
        names.removeIf { it.equals(name, ignoreCase = true) }
    }

    override fun lobby(): WorldPosition? = lobbyPosition
    override fun setLobby(position: WorldPosition) {
        lobbyPosition = position
    }

    override fun signLocation(arenaName: String): BlockPosition? = signs[arenaName]
    override fun setSign(arenaName: String, position: BlockPosition) {
        signs[arenaName] = position
    }

    override fun clearSign(arenaName: String) {
        signs.remove(arenaName)
    }

    override fun signOwner(position: BlockPosition): String? = signs.entries.firstOrNull { (_, pos) -> pos == position }?.key
}

class InMemoryPlayerStatsRepository : PlayerStatsRepository {
    val stats = mutableMapOf<Uuid, PlayerStats>()
    var failOnWin: Throwable? = null
    var failOnLoss: Throwable? = null
    var failOnFind: Throwable? = null

    override fun find(playerId: Uuid): PlayerStats? {
        failOnFind?.let { throw it }
        return stats[playerId]
    }

    override fun recordWin(playerId: Uuid) {
        failOnWin?.let { throw it }
        val s = stats[playerId] ?: PlayerStats.new(0, 0)
        stats[playerId] = PlayerStats.new(s.wins + 1, s.losses)
    }

    override fun recordLoss(playerId: Uuid) {
        failOnLoss?.let { throw it }
        val s = stats[playerId] ?: PlayerStats.new(0, 0)
        stats[playerId] = PlayerStats.new(s.wins, s.losses + 1)
    }
}
