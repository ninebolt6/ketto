package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.BlockPosition

interface ArenaSignRepository {
    fun signLocation(arena: Arena.Id): BlockPosition?
    fun setSign(arena: Arena.Id, position: BlockPosition)
    fun clearSign(arena: Arena.Id)
    fun signOwner(position: BlockPosition): Arena.Id?
}
