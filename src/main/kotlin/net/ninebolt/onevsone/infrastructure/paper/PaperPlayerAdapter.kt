package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.domain.WorldPosition
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * オンラインプレイヤーの解決。QuitEvent 中は Server から取得できなくなる
 * 切断者を、イベントの Player を短時間参照できるスコープとして提供する。
 */
class PaperPlayerLookup(private val server: Server) {
    private val quitting = HashMap<Uuid, Player>()

    /** QuitEvent の Player を同期処理の間だけ UUID で解決可能にする。 */
    fun <R> scopeQuitting(player: Player, block: () -> R): R {
        val id = player.uniqueId.toKotlinUuid()
        quitting[id] = player
        try {
            return block()
        } finally {
            quitting.remove(id)
        }
    }

    fun resolve(id: Uuid): Player? = quitting[id] ?: server.getPlayer(id.toJavaUuid())

    fun resolveByName(name: String): Player? =
        quitting.values.firstOrNull { it.name == name } ?: server.getPlayerExact(name)
}

class PaperPlayerAdapter(
    private val lookup: PaperPlayerLookup,
    private val server: Server,
    private val plugin: JavaPlugin,
    private val failures: FailureReporter
) : PlayerPort {
    override fun handle(playerId: Uuid): PlayerHandle? =
        lookup.resolve(playerId)?.let { PaperPlayerHandle(it, server, failures) }

    override fun resolveOfflineId(name: String, callback: (Uuid?) -> Unit) {
        val known = server.getPlayerExact(name) ?: server.getOfflinePlayerIfCached(name)
        if (known != null) {
            callback(known.uniqueId.toKotlinUuid())
            return
        }
        // オフライン名解決はブロッキングなので asyncScheduler で実行し、応答はメインスレッドへ戻す
        server.asyncScheduler.runNow(plugin) {
            val uuid = runCatching { server.getOfflinePlayer(name).uniqueId.toKotlinUuid() }.getOrNull()
            try {
                if (plugin.isEnabled) {
                    server.scheduler.runTask(plugin, Runnable { callback(uuid) })
                }
            } catch (e: IllegalPluginAccessException) {
            }
        }
    }
}

private class PaperPlayerHandle(
    private val player: Player,
    private val server: Server,
    private val failures: FailureReporter
) : PlayerHandle {
    override val id: Uuid get() = player.uniqueId.toKotlinUuid()
    override val name: String get() = player.name
    override val online: Boolean get() = player.isOnline
    override val dead: Boolean get() = player.isDead

    override fun position(): WorldPosition? {
        val location = player.location
        val world = location.world ?: return null
        return WorldPosition(world.name, location.x, location.y, location.z, location.yaw, location.pitch)
    }

    override fun respawn() {
        if (player.isDead) player.spigot().respawn()
    }

    override fun resetVitals() {
        if (player.isDead) return
        player.fireTicks = 0
        player.health = minOf(20.0, player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0)
        player.foodLevel = 20
    }

    override fun prepareForMatch() {
        player.gameMode = GameMode.SURVIVAL
        player.allowFlight = false
        resetVitals()
    }

    override fun teleport(position: WorldPosition) {
        val world = server.getWorld(position.world)
        if (world == null) {
            failures.warn("World '${position.world}' is not loaded; skipping teleport")
            return
        }
        player.teleport(Location(world, position.x, position.y, position.z, position.yaw, position.pitch))
    }
}

/** コマンド側での Location → 純粋座標への変換。world 無しは null。 */
fun Location.toWorldPosition(): WorldPosition? {
    val world = world ?: return null
    return WorldPosition(world.name, x, y, z, yaw, pitch)
}
