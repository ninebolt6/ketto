package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

/**
 * Admin operations: create/remove/enable/disable and spawn/kit/sign/lobby
 * settings. Any needed abort is requested to MatchProgressionService.
 */
class ArenaAdministrationService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val signs: ArenaSignRepository,
    private val lobby: LobbyRepository,
    private val kit: KitPort,
    private val presentation: MatchPresentationPort,
    private val progression: MatchProgressionService
) {
    /** Arena names in registration order (for tab completion). */
    fun arenaNames(): List<String> = registry.arenaIds().map { it.name }

    fun arena(name: String): Arena? = registry.resolveArenaId(name)?.let { registry.arena(it) }

    fun create(name: String): Boolean {
        val id = Arena.Id.of(name) ?: return false
        if (registry.resolveArenaId(name) != null) return false
        val arena = Arena.new(id)
        registry.installArena(arena)
        arenas.save(arena)
        return true
    }

    fun remove(name: String): Boolean {
        val arena = arena(name) ?: return false
        progression.abort(arena.id)
        registry.removeArena(arena.id)
        // Delete by the resolved canonical name (so case-differing input leaves neither the file nor the sign registration)
        arenas.delete(arena.name)
        signs.clearSign(arena.name)
        kit.forgetKit(arena.id)
        return true
    }

    fun setEnabled(name: String, enabled: Boolean): ToggleReply {
        val id = registry.resolveArenaId(name) ?: return ToggleReply.NotFound
        val arena = registry.arena(id) ?: return ToggleReply.NotFound
        if (arena.enabled == enabled) {
            return if (enabled) ToggleReply.AlreadyEnabled else ToggleReply.AlreadyDisabled
        }
        val updated = registry.updateArena(id) { if (enabled) it.enable() else it.disable() }
            ?: return ToggleReply.NotFound
        arenas.save(updated)
        if (!enabled) progression.abort(id)
        return ToggleReply.Changed
    }

    /** slot is 0-based, same as Arena.withSpawn. */
    fun setSpawn(name: String, slot: Int, position: WorldPosition): Boolean {
        val id = registry.resolveArenaId(name) ?: return false
        val updated = registry.updateArena(id) { it.withSpawn(slot, position) } ?: return false
        arenas.save(updated)
        return true
    }

    /** Saves the executor's current equipment as the arena kit. */
    fun setKit(name: String, playerId: Uuid): Boolean {
        val arena = arena(name) ?: return false
        kit.saveKit(arena.id, playerId)
        return true
    }

    fun setLobby(position: WorldPosition) {
        lobby.setLobby(position)
    }

    fun signLocation(arenaName: String): WorldPosition? = signs.signLocation(arenaName)

    fun signOwner(world: String, x: Int, y: Int, z: Int): String? =
        signs.signOwner(world, x, y, z)

    fun setSign(name: String, position: WorldPosition): Boolean {
        val arena = arena(name) ?: return false
        signs.setSign(arena.name, position)
        val state = registry.match(arena.id)?.state ?: return true
        presentation.updateSign(arena.id, state)
        return true
    }

    /** Unregisters only the sign. The sign block itself remains and becomes breakable. */
    fun clearSign(name: String): Boolean {
        val arena = arena(name) ?: return false
        signs.clearSign(arena.name)
        return true
    }
}
