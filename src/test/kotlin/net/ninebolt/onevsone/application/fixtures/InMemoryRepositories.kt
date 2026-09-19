package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.domain.WorldPosition
import java.util.UUID

class InMemoryArenaRepository : ArenaRepository, LobbyRepository, ArenaSignRepository {
    val names = mutableListOf<String>()
    val definitions = mutableMapOf<String, ArenaDefinition>()
    var lobbyPosition: WorldPosition? = null
    val signs = mutableMapOf<String, WorldPosition>()
    var failOnSave = false

    override fun arenaNames(): List<String> = names.toList()
    override fun saveArenaNames(names: List<String>) {
        this.names.clear()
        this.names += names
    }

    override fun find(name: String): ArenaDefinition? = definitions[name] ?: ArenaDefinition(ArenaId(name))
    override fun save(arena: ArenaDefinition) {
        if (failOnSave) throw PersistenceFailure("save failed")
        definitions[arena.name] = arena
    }

    override fun delete(name: String) {
        definitions.remove(name)
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

    override fun signOwner(world: String, x: Double, y: Double, z: Double): String? =
        signs.entries.firstOrNull { (_, pos) ->
            pos.world == world && pos.x == x && pos.y == y && pos.z == z
        }?.key
}

class InMemoryMatchStateRepository : MatchStateRepository {
    val savedViews = mutableListOf<ArenaMatch>()
    val registrations = linkedMapOf<String, ArenaId>()
    var failOnRegister = false
    var failOnUnregister = false
    var failOnSaveStatus = false

    override fun saveStatus(match: ArenaMatch) {
        if (failOnSaveStatus) throw PersistenceFailure("status save failed")
        savedViews += match
    }

    override fun registerParticipant(participant: Participant, arena: ArenaId) {
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
    val stats = mutableMapOf<UUID, PlayerStats>()
    var failOnWin: Throwable? = null
    var failOnLoss: Throwable? = null
    var failOnFind: Throwable? = null

    override fun find(playerId: UUID): PlayerStats? {
        failOnFind?.let { throw it }
        return stats[playerId]
    }

    override fun recordWin(playerId: UUID) {
        failOnWin?.let { throw it }
        val s = stats[playerId] ?: PlayerStats(0, 0)
        stats[playerId] = PlayerStats(s.wins + 1, s.losses)
    }

    override fun recordLoss(playerId: UUID) {
        failOnLoss?.let { throw it }
        val s = stats[playerId] ?: PlayerStats(0, 0)
        stats[playerId] = PlayerStats(s.wins, s.losses + 1)
    }
}
