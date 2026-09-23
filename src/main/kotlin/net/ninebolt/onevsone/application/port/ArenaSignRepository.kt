package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.BlockPosition

/**
 * Persistence of Join sign placement. Manages the arena-name <-> sign
 * coordinates mapping.
 */
interface ArenaSignRepository {
    fun signLocation(arenaName: String): BlockPosition?
    fun setSign(arenaName: String, position: BlockPosition)
    fun clearSign(arenaName: String)
    fun signOwner(position: BlockPosition): String?
}
