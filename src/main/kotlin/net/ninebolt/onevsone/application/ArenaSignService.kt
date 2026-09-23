package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition

/**
 * Join sign management: coordinate persistence via ArenaSignRepository and
 * sign display refresh via PresentationPort.
 * All operations are assumed to be serialized on the main thread.
 */
class ArenaSignService(
    private val registry: ArenaRegistry,
    private val signs: ArenaSignRepository,
    private val presentation: PresentationPort
) {
    fun signLocation(arenaName: String): BlockPosition? = signs.signLocation(arenaName)

    fun signOwner(position: BlockPosition): String? = signs.signOwner(position)

    fun setSign(name: String, position: BlockPosition): Boolean {
        val arena = arena(name) ?: return false
        signs.setSign(arena.name, position)
        val state = registry.match(arena.id)?.state ?: return true
        presentation.updateSign(arena.id, position, state)
        return true
    }

    /** Repaints the join sign with the given state. Does nothing when no sign is registered. */
    fun refreshSign(arena: Arena.Id, state: ArenaState) {
        val position = signs.signLocation(arena.name) ?: return
        presentation.updateSign(arena, position, state)
    }

    /** Unregisters only the sign. The sign block itself remains and becomes breakable. */
    fun clearSign(name: String): Boolean {
        val arena = arena(name) ?: return false
        signs.clearSign(arena.name)
        return true
    }

    private fun arena(name: String): Arena? = registry.resolveArenaId(name)?.let { registry.arena(it) }
}
