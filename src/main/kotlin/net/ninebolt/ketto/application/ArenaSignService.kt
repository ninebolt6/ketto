package net.ninebolt.ketto.application

import net.ninebolt.ketto.application.port.ArenaSignRepository
import net.ninebolt.ketto.application.port.PresentationPort
import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.ArenaMatch
import net.ninebolt.ketto.domain.ArenaState
import net.ninebolt.ketto.domain.BlockPosition

class ArenaSignService(
    private val sessions: ArenaSessions,
    private val signRepository: ArenaSignRepository,
    private val presentationPort: PresentationPort,
) {
    fun findSignOwner(position: BlockPosition): Arena.Id? = signRepository.findSignOwner(position)

    fun setSign(id: Arena.Id, position: BlockPosition): Boolean {
        val (arena, match) = sessions.findEntry(id) ?: return false
        val owner = signRepository.findSignOwner(position)
        if (owner != null && owner != arena.id) return false
        signRepository.setSign(arena.id, position)
        presentationPort.updateSign(arena, position, match.state.kind)
        return true
    }

    fun refreshSign(arena: Arena.Id, state: ArenaState) {
        val position = signRepository.findSignLocation(arena) ?: return
        val resolved = sessions.findArena(arena) ?: return
        presentationPort.updateSign(resolved, position, state.kind)
    }

    // A superseded match snapshot must not overwrite the live sign
    fun refreshSign(match: ArenaMatch) {
        if (sessions.findMatch(match.arenaId) === match) refreshSign(match.arenaId, match.state)
    }

    fun clearSign(id: Arena.Id): Boolean {
        val arena = sessions.findArena(id) ?: return false
        if (signRepository.findSignLocation(arena.id) == null) return false
        signRepository.clearSign(arena.id)
        return true
    }
}
