package net.ninebolt.onevsone

import org.bukkit.scheduler.BukkitTask
import java.util.UUID

enum class ArenaState(val display: String) {
    WAITING("§aWaiting"),
    ONEMORE("§e1 More"),
    COUNTDOWN("§cCountdown"),
    ROUNDCOUNTDOWN("§c§lIngame"),
    INGAME("§c§lIngame")
}

data class Participant(val id: UUID, val name: String, val snapshot: InventorySnapshot)

class Arena(
    val name: String,
    var enabled: Boolean = false,
    var spawn1: SavedLocation? = null,
    var spawn2: SavedLocation? = null,
    var kit: InventorySnapshot = InventorySnapshot()
) {
    var state = ArenaState.WAITING
    val players = mutableListOf<Participant>()
    val wins = mutableMapOf<UUID, Int>()
    var task: BukkitTask? = null
    var generation = 0L
}

data class SavedLocation(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f
)
