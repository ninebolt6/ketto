package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.WorldPosition
import org.bukkit.Location
import org.bukkit.block.Block

/** Location -> pure coordinates conversion for the command side. null when world is absent. */
internal fun Location.toWorldPosition(): WorldPosition? {
    val world = world ?: return null
    return WorldPosition.new(world.name, x, y, z, yaw, pitch)
}

/** Block -> integer block coordinates (sign positions etc.). */
internal fun Block.toBlockPosition(): BlockPosition =
    BlockPosition.new(world.name, x, y, z)
