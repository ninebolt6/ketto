package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.isValidArenaName
import java.util.Locale
import kotlin.uuid.Uuid

class InMemoryArenaRepository : ArenaRepository, LobbyRepository, ArenaSignRepository {
    val names = mutableListOf<String>()
    val definitions = mutableMapOf<String, Arena>()
    var lobbyPosition: WorldPosition? = null
    val signs = mutableMapOf<String, WorldPosition>()
    var failOnSave = false

    override fun loadAll(): List<Arena> {
        val seen = mutableSetOf<String>()
        return names.mapNotNull { name ->
            if (!isValidArenaName(name) || !seen.add(name.lowercase(Locale.ROOT))) null else find(name)
        }
    }

    override fun find(name: String): Arena =
        definitions[name] ?: Arena(Arena.Id(name))

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

    override fun signLocation(arenaName: String): WorldPosition? = signs[arenaName]
    override fun setSign(arenaName: String, position: WorldPosition) {
        signs[arenaName] = position
    }

    override fun clearSign(arenaName: String) {
        signs.remove(arenaName)
    }

    override fun signOwner(world: String, x: Int, y: Int, z: Int): String? =
        signs.entries.firstOrNull { (_, pos) ->
            pos.world == world && pos.x.toInt() == x && pos.y.toInt() == y && pos.z.toInt() == z
        }?.key
}

class InMemoryMatchStateRepository : MatchStateRepository {
    val savedViews = mutableListOf<ArenaMatch>()
    val registrations = linkedMapOf<String, Arena.Id>()
    var failOnRegister = false
    var failOnUnregister = false
    var failOnSaveStatus = false
    val failOnSaveStatusFor = mutableSetOf<String>()

    override fun saveStatus(match: ArenaMatch) {
        if (failOnSaveStatus || match.arenaId.name in failOnSaveStatusFor) {
            throw PersistenceFailure("status save failed")
        }
        savedViews += match
    }

    override fun registerParticipant(participant: Participant, arena: Arena.Id) {
        if (failOnRegister) throw PersistenceFailure("register failed")
        registrations[participant.name] = arena
    }

    override fun unregisterParticipant(playerName: String) {
        if (failOnUnregister) throw PersistenceFailure("unregister failed")
        registrations.remove(playerName)
    }

    override fun clearRegistrations() {
        registrations.clear()
    }
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
        val s = stats[playerId] ?: PlayerStats(0, 0)
        stats[playerId] = PlayerStats(s.wins + 1, s.losses)
    }

    override fun recordLoss(playerId: Uuid) {
        failOnLoss?.let { throw it }
        val s = stats[playerId] ?: PlayerStats(0, 0)
        stats[playerId] = PlayerStats(s.wins, s.losses + 1)
    }
}
