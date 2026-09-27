package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.EnableOutcome
import net.ninebolt.onevsone.domain.SpawnSlot
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

class ArenaAdministrationService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val signRepo: ArenaSignRepository,
    private val kit: KitPort,
    private val progression: MatchProgressionService,
    private val signs: ArenaSignService,
) {
    fun arenaNames(): List<String> = registry.arenaIds().map { it.name }

    fun resolveArenaId(name: String): Arena.Id? = registry.resolveArenaId(name)

    fun create(name: String): CreateError? {
        val id = Arena.Id.of(name) ?: return CreateError.InvalidName
        if (registry.resolveArenaId(name) != null) return CreateError.AlreadyExists
        val arena = Arena.Disabled.new(id)
        arenas.save(arena)
        registry.installArena(arena)
        return null
    }

    fun remove(name: String): RemoveError? {
        val arena = registry.resolveArena(name) ?: return RemoveError.NotFound
        progression.abort(arena.id)
        arenas.delete(arena.name)
        registry.removeArena(arena.id)
        signRepo.clearSign(arena.name)
        kit.forgetKit(arena.id)
        return null
    }

    fun enable(name: String): EnableError? {
        val arena = registry.resolveArena(name) ?: return EnableError.NotFound
        val next = when (arena) {
            is Arena.Enabled -> return EnableError.AlreadyEnabled

            is Arena.Disabled -> when (val outcome = arena.enable()) {
                is EnableOutcome.MissingSpawns -> return EnableError.MissingSpawns(outcome.slots)
                is EnableOutcome.Ready -> outcome.arena
            }
        }
        arenas.save(next)
        registry.replaceArena(next)
        registry.match(arena.id)?.let(signs::refreshSign)
        return null
    }

    fun disable(name: String): DisableError? {
        val arena = registry.resolveArena(name) ?: return DisableError.NotFound
        val next = when (arena) {
            is Arena.Disabled -> return DisableError.AlreadyDisabled
            is Arena.Enabled -> arena.disable()
        }
        arenas.save(next)
        registry.replaceArena(next)
        progression.abort(arena.id)
        return null
    }

    fun setSpawn(id: Arena.Id, slot: SpawnSlot, position: WorldPosition) {
        val arena = registry.arena(id) ?: return
        val next = arena.withSpawn(slot, position)
        arenas.save(next)
        registry.replaceArena(next)
    }

    fun setKit(id: Arena.Id, playerId: Uuid) {
        if (registry.arena(id) == null) return
        kit.saveKit(id, playerId)
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
