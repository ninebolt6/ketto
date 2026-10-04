package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition

class ArenaSignService(
    private val sessions: ArenaSessions,
    private val signRepository: ArenaSignRepository,
    private val presentationPort: PresentationPort,
) {
    fun signOwner(position: BlockPosition): Arena.Id? = signRepository.signOwner(position)

    fun setSign(id: Arena.Id, position: BlockPosition): Boolean {
        val (arena, match) = sessions.entry(id) ?: return false
        val owner = signRepository.signOwner(position)
        if (owner != null && owner != arena.id) return false
        signRepository.setSign(arena.id, position)
        presentationPort.updateSign(arena, position, match.state.kind)
        return true
    }

    fun refreshSign(arena: Arena.Id, state: ArenaState) {
        val position = signRepository.signLocation(arena) ?: return
        val resolved = sessions.arena(arena) ?: return
        presentationPort.updateSign(resolved, position, state.kind)
    }

    // A superseded match snapshot must not overwrite the live sign
    fun refreshSign(match: ArenaMatch) {
        if (sessions.match(match.arenaId) === match) refreshSign(match.arenaId, match.state)
    }

    fun clearSign(id: Arena.Id): Boolean {
        val arena = sessions.arena(id) ?: return false
        if (signRepository.signLocation(arena.id) == null) return false
        signRepository.clearSign(arena.id)
        return true
    }
}
