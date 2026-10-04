package net.ninebolt.ketto.application

import net.ninebolt.ketto.application.port.ArenaRepository
import net.ninebolt.ketto.application.port.ArenaSignRepository
import net.ninebolt.ketto.application.port.KitPort
import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.EnableOutcome
import net.ninebolt.ketto.domain.SpawnSlot
import net.ninebolt.ketto.domain.WorldPosition
import kotlin.uuid.Uuid

class ArenaAdministrationService(
    private val sessions: ArenaSessions,
    private val arenaRepository: ArenaRepository,
    private val signRepository: ArenaSignRepository,
    private val kitPort: KitPort,
    private val progression: MatchProgressionService,
    private val signService: ArenaSignService,
) {
    fun arenaNames(): List<String> = sessions.arenaIds().map { it.name }

    fun findArenaId(name: String): Arena.Id? = sessions.findArenaId(name)

    fun create(name: String): CreateError? {
        val id = Arena.Id.of(name) ?: return CreateError.InvalidName
        if (sessions.findArenaId(name) != null) return CreateError.AlreadyExists
        val arena = Arena.Disabled.new(id)
        arenaRepository.save(arena)
        sessions.installArena(arena)
        return null
    }

    fun remove(name: String): RemoveError? {
        val arena = sessions.findArena(name) ?: return RemoveError.NotFound
        progression.abort(arena.id)
        arenaRepository.delete(arena.id)
        sessions.removeArena(arena.id)
        signRepository.clearSign(arena.id)
        kitPort.forgetKit(arena.id)
        return null
    }

    fun enable(name: String): EnableError? {
        val arena = sessions.findArena(name) ?: return EnableError.NotFound
        val next = when (arena) {
            is Arena.Enabled -> return EnableError.AlreadyEnabled

            is Arena.Disabled -> when (val outcome = arena.enable()) {
                is EnableOutcome.MissingSpawns -> return EnableError.MissingSpawns(outcome.slots)
                is EnableOutcome.Ready -> outcome.arena
            }
        }
        arenaRepository.save(next)
        sessions.replaceArena(next)
        sessions.findMatch(arena.id)?.let(signService::refreshSign)
        return null
    }

    fun disable(name: String): DisableError? {
        val arena = sessions.findArena(name) ?: return DisableError.NotFound
        val next = when (arena) {
            is Arena.Disabled -> return DisableError.AlreadyDisabled
            is Arena.Enabled -> arena.disable()
        }
        arenaRepository.save(next)
        sessions.replaceArena(next)
        progression.abort(arena.id)
        return null
    }

    fun setSpawn(id: Arena.Id, slot: SpawnSlot, position: WorldPosition) {
        val arena = sessions.findArena(id) ?: return
        val next = arena.withSpawn(slot, position)
        arenaRepository.save(next)
        sessions.replaceArena(next)
    }

    fun setKit(id: Arena.Id, playerId: Uuid) {
        if (sessions.findArena(id) == null) return
        kitPort.saveKit(id, playerId)
    }
}

sealed interface CreateError {
    data object AlreadyExists : CreateError
    data object InvalidName : CreateError
}

sealed interface RemoveError {
    data object NotFound : RemoveError
}

sealed interface EnableError {
    data object AlreadyEnabled : EnableError
    data class MissingSpawns(val slots: List<SpawnSlot>) : EnableError
    data object NotFound : EnableError
}

sealed interface DisableError {
    data object AlreadyDisabled : DisableError
    data object NotFound : DisableError
}
