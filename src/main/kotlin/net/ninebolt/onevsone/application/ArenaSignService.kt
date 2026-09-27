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
    fun signLocation(arenaName: String): BlockPosition? = signs.signLocation(arenaName)

    fun signOwner(position: BlockPosition): String? = signs.signOwner(position)

    fun setSign(name: String, position: BlockPosition): SetSignError? {
        val (arena, match) = registry.resolveEntry(name) ?: return SetSignError.NotFound
        signs.setSign(arena.name, position)
        presentation.updateSign(arena, position, match.state.kind)
        return null
    }

    fun refreshSign(arena: Arena.Id, state: ArenaState) {
        val position = signs.signLocation(arena.name) ?: return
        val resolved = registry.arena(arena) ?: return
        presentation.updateSign(resolved, position, state.kind)
    }

    fun refreshSign(match: ArenaMatch) = refreshSign(match.arenaId, match.state)

    fun clearSign(name: String): ClearSignError? {
        val arena = arena(name) ?: return ClearSignError.NotFound
        signs.clearSign(arena.name)
        return null
    }

    private fun arena(name: String): Arena? = registry.resolveArena(name)
}

sealed interface SetSignError {
    data object NotFound : SetSignError
}

sealed interface ClearSignError {
    data object NotFound : ClearSignError
}
