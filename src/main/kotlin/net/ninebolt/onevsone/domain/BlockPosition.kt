package net.ninebolt.onevsone.domain

/**
 * Integer block coordinates (a sign block etc.). Distinct from WorldPosition,
 * which is an exact entity position with yaw/pitch.
 */
data class BlockPosition private constructor(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int
) {
    companion object {
        fun new(world: String, x: Int, y: Int, z: Int): BlockPosition {
            require(world.isNotBlank()) { "world name must not be blank" }
            return BlockPosition(world, x, y, z)
        }
    }
}
