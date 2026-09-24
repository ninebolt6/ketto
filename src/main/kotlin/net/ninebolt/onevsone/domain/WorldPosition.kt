package net.ninebolt.onevsone.domain

data class WorldPosition private constructor(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f
) {
    companion object {
        fun new(
            world: String,
            x: Double,
            y: Double,
            z: Double,
            yaw: Float = 0f,
            pitch: Float = 0f
        ): WorldPosition {
            require(world.isNotBlank()) { "world name must not be blank" }
            require(x.isFinite() && y.isFinite() && z.isFinite()) {
                "coordinates must be finite (x=$x y=$y z=$z)"
            }
            require(yaw.isFinite() && pitch.isFinite()) {
                "yaw/pitch must be finite (yaw=$yaw pitch=$pitch)"
            }
            return WorldPosition(world, x, y, z, yaw, pitch)
        }
    }
}
