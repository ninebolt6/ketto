package net.ninebolt.ketto.application.port

import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.BlockPosition

interface ArenaSignRepository {
    fun findSignLocation(arena: Arena.Id): BlockPosition?
    fun setSign(arena: Arena.Id, position: BlockPosition)
    fun clearSign(arena: Arena.Id)
    fun findSignOwner(position: BlockPosition): Arena.Id?
}
