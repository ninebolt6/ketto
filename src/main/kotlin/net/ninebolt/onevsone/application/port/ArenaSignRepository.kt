package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.WorldPosition

/**
 * Persistence of Join sign placement. Manages the arena-name <-> sign
 * coordinates mapping.
 */
interface ArenaSignRepository {
    fun signLocation(arenaName: String): WorldPosition?
    fun setSign(arenaName: String, position: WorldPosition)
    fun clearSign(arenaName: String)
    fun signOwner(world: String, x: Int, y: Int, z: Int): String?
}
