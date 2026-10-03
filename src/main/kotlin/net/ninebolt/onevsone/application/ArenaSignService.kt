package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition

class ArenaSignService(
    private val registry: ArenaRegistry,
    private val signs: ArenaSignRepository,
    private val presentation: PresentationPort,
) {
    fun signOwner(position: BlockPosition): Arena.Id? = signs.signOwner(position)

    fun setSign(id: Arena.Id, position: BlockPosition): Boolean {
        val (arena, match) = registry.entry(id) ?: return false
        val owner = signs.signOwner(position)
        if (owner != null && owner != arena.id) return false
        signs.setSign(arena.id, position)
        presentation.updateSign(arena, position, match.state.kind)
        return true
    }

    fun refreshSign(arena: Arena.Id, state: ArenaState) {
        val position = signs.signLocation(arena) ?: return
        val resolved = registry.arena(arena) ?: return
        presentation.updateSign(resolved, position, state.kind)
    }

    fun refreshSign(match: ArenaMatch) = refreshSign(match.arenaId, match.state)

    fun clearSign(id: Arena.Id): Boolean {
        val arena = registry.arena(id) ?: return false
        if (signs.signLocation(arena.id) == null) return false
        signs.clearSign(arena.id)
        return true
    }
}
