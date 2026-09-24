package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.SpawnSlot
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

class ArenaAdministrationService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val signs: ArenaSignRepository,
    private val kit: KitPort,
    private val progression: MatchProgressionService,
) {
    fun arenaNames(): List<String> = registry.arenaIds().map { it.name }

    fun arena(name: String): Arena? = registry.resolveArenaId(name)?.let { registry.arena(it) }

    fun create(name: String): CreateError? {
        val id = Arena.Id.of(name) ?: return CreateError.InvalidName
        if (registry.resolveArenaId(name) != null) return CreateError.AlreadyExists
        val arena = Arena.new(id)
        // Authoritative data: a save failure propagates and the arena is never registered
        registry.installArena(arena, persist = arenas::save)
        return null
    }

    fun remove(name: String): RemoveError? {
        val arena = arena(name) ?: return RemoveError.NotFound
        progression.abort(arena.id)
        registry.removeArena(arena.id, persist = { arenas.delete(it.name) })
        signs.clearSign(arena.name)
        kit.forgetKit(arena.id)
        return null
    }

    fun setEnabled(name: String, enabled: Boolean): ToggleError? {
        val id = registry.resolveArenaId(name) ?: return ToggleError.NotFound
        val arena = registry.arena(id) ?: return ToggleError.NotFound
        if (arena.enabled == enabled) {
            return if (enabled) ToggleError.AlreadyEnabled else ToggleError.AlreadyDisabled
        }
        registry.updateArena(id, persist = arenas::save) { if (enabled) it.enable() else it.disable() }
            ?: return ToggleError.NotFound
        if (!enabled) progression.abort(id)
        return null
    }

    fun setSpawn(name: String, slot: SpawnSlot, position: WorldPosition): SetSpawnError? {
        val id = registry.resolveArenaId(name) ?: return SetSpawnError.NotFound
        registry.updateArena(id, persist = arenas::save) { it.withSpawn(slot, position) }
            ?: return SetSpawnError.NotFound
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

sealed interface ToggleError {
    data object AlreadyEnabled : ToggleError
    data object AlreadyDisabled : ToggleError
    data object NotFound : ToggleError
}

sealed interface SetSpawnError {
    data object NotFound : SetSpawnError
}

sealed interface SetKitError {
    data object NotFound : SetKitError
}
