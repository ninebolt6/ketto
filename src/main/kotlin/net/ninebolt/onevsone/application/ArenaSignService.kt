package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.BlockPosition

/**
 * Join sign management: coordinate persistence via ArenaSignRepository and
 * sign display refresh via MatchPresentationPort.
 * All operations are assumed to be serialized on the main thread.
 */
class ArenaSignService(
    private val registry: ArenaRegistry,
    private val signs: ArenaSignRepository,
    private val presentation: MatchPresentationPort
) {
    fun signLocation(arenaName: String): BlockPosition? = signs.signLocation(arenaName)

    fun signOwner(position: BlockPosition): String? = signs.signOwner(position)

    fun setSign(name: String, position: BlockPosition): Boolean {
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

    private fun arena(name: String): Arena? = registry.resolveArenaId(name)?.let { registry.arena(it) }
}
