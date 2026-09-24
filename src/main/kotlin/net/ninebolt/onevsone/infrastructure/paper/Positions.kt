package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.WorldPosition
import org.bukkit.Location
import org.bukkit.block.Block

internal fun Location.toWorldPosition(): WorldPosition? {
    val world = world ?: return null
    return WorldPosition.new(world.name, x, y, z, yaw, pitch)
}

internal fun Block.toBlockPosition(): BlockPosition =
    BlockPosition.new(world.name, x, y, z)
