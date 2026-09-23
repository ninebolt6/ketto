package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.domain.WorldPosition
import java.util.Locale
import kotlin.uuid.Uuid

class InMemoryArenaRepository : ArenaRepository, LobbyRepository, ArenaSignRepository {
    val names = mutableListOf<String>()
    val definitions = mutableMapOf<String, Arena>()
    var lobbyPosition: WorldPosition? = null
    val signs = mutableMapOf<String, BlockPosition>()
    var failOnSave = false

    override fun loadAll(): List<Arena> {
        val seen = mutableSetOf<String>()
        return names.mapNotNull { name ->
            if (Arena.Id.of(name) == null || !seen.add(name.lowercase(Locale.ROOT))) null else find(name)
        }
    }

    override fun find(name: String): Arena =
        definitions[name] ?: Arena.new(Arena.Id.new(name))

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

    override fun signOwner(position: BlockPosition): String? =
        signs.entries.firstOrNull { (_, pos) -> pos == position }?.key
}

class InMemoryMatchStateRepository : MatchStateRepository {
    val savedViews = mutableListOf<ArenaMatch>()
    /** player uuid -> arena, mirroring the ledger the projection writes */
    val registrations = linkedMapOf<Uuid, Arena.Id>()
    var failOnPersist = false
    val failOnPersistFor = mutableSetOf<String>()
    var failOnSaveStatus = false
    val failOnSaveStatusFor = mutableSetOf<String>()

    /**
     * Mirrors the real projection: registrations pointing at this arena are
     * rewritten to the current participants (upsert by player id), then the
     * status snapshot is recorded. A failure leaves earlier writes in place.
     */
    override fun persistMatch(match: ArenaMatch) {
        if (failOnPersist || match.arenaId.name in failOnPersistFor) {
            throw PersistenceFailure("persist failed")
        }
        registrations.entries.removeIf { it.value == match.arenaId }
        match.participants.forEach { registrations[it.id] = match.arenaId }
        saveStatus(match)
    }

    override fun saveStatus(match: ArenaMatch) {
        if (failOnSaveStatus || match.arenaId.name in failOnSaveStatusFor) {
            throw PersistenceFailure("status save failed")
        }
        savedViews += match
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
        val s = stats[playerId] ?: PlayerStats.new(0, 0)
        stats[playerId] = PlayerStats.new(s.wins + 1, s.losses)
    }

    override fun recordLoss(playerId: Uuid) {
        failOnLoss?.let { throw it }
        val s = stats[playerId] ?: PlayerStats.new(0, 0)
        stats[playerId] = PlayerStats.new(s.wins, s.losses + 1)
    }
}
