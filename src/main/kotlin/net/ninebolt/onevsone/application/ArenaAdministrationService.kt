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

    fun arena(name: String): Arena? = registry.resolveArena(name)

    fun create(name: String): CreateError? {
        val id = Arena.Id.of(name) ?: return CreateError.InvalidName
        if (registry.resolveArenaId(name) != null) return CreateError.AlreadyExists
        val arena = Arena.Disabled.new(id)
        // Authoritative data: a save failure propagates and the arena is never registered
        registry.installArena(arena, persist = arenas::save)
        return null
    }

    fun remove(name: String): RemoveError? {
        val arena = arena(name) ?: return RemoveError.NotFound
        progression.abort(arena.id)
        registry.removeArena(arena.id, persist = { arenas.delete(it.name) })
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
        registry.updateArena(arena.id, persist = arenas::save) { next }
        registry.match(arena.id)?.let(signs::refreshSign)
        return null
    }

    fun disable(name: String): DisableError? {
        val arena = registry.resolveArena(name) ?: return DisableError.NotFound
        val next = when (arena) {
            is Arena.Disabled -> return DisableError.AlreadyDisabled
            is Arena.Enabled -> arena.disable()
        }
        registry.updateArena(arena.id, persist = arenas::save) { next }
        progression.abort(arena.id)
        return null
    }

    fun setSpawn(name: String, slot: SpawnSlot, position: WorldPosition): SetSpawnError? {
        val id = registry.resolveArenaId(name) ?: return SetSpawnError.NotFound
        registry.updateArena(id, persist = arenas::save) { it.withSpawn(slot, position) }
        return null
    }

    fun setKit(name: String, playerId: Uuid): SetKitError? {
        val arena = arena(name) ?: return SetKitError.NotFound
        kit.saveKit(arena.id, playerId)
        return null
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

sealed interface SetSpawnError {
    data object NotFound : SetSpawnError
}

sealed interface SetKitError {
    data object NotFound : SetKitError
}
