package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.BlockPosition

interface ArenaSignRepository {
    fun signLocation(arenaName: String): BlockPosition?
    fun setSign(arenaName: String, position: BlockPosition)
    fun clearSign(arenaName: String)
    fun signOwner(position: BlockPosition): String?
}
