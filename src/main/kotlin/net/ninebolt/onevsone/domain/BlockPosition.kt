package net.ninebolt.onevsone.domain

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
