package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.WorldPosition
import org.bukkit.block.Block
import org.bukkit.entity.Player

internal fun Player.toWorldPosition(): WorldPosition = WorldPosition.new(world.name, location.x, location.y, location.z, location.yaw, location.pitch)

internal fun Block.toBlockPosition(): BlockPosition = BlockPosition.new(world.name, x, y, z)
