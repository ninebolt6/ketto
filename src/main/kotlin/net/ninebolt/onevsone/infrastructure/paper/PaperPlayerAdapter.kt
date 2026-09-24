package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.domain.WorldPosition
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

// A disconnecting player can no longer be fetched from Server during QuitEvent
class PaperPlayerLookup(private val server: Server) {
    private val quitting = HashMap<Uuid, Player>()
    private val pluginTeleports = HashSet<Uuid>()

    fun <R> scopeQuitting(player: Player, block: () -> R): R {
        val id = player.uniqueId.toKotlinUuid()
        quitting[id] = player
        try {
            return block()
        } finally {
            quitting.remove(id)
        }
    }

    // PlayerTeleportEvent fires synchronously inside teleport()
    fun <R> scopePluginTeleport(playerId: Uuid, block: () -> R): R {
        pluginTeleports += playerId
        try {
            return block()
        } finally {
            pluginTeleports -= playerId
        }
    }

    fun isPluginTeleport(playerId: Uuid): Boolean = playerId in pluginTeleports

    fun resolve(id: Uuid): Player? = quitting[id] ?: server.getPlayer(id.toJavaUuid())

    fun resolveByName(name: String): Player? =
        quitting.values.firstOrNull { it.name == name } ?: server.getPlayerExact(name)
}

class PaperPlayerAdapter(
    private val lookup: PaperPlayerLookup,
    private val server: Server,
    private val plugin: JavaPlugin,
    private val logger: Logger
) : PlayerPort {
    override fun handle(playerId: Uuid): PlayerHandle? =
        lookup.resolve(playerId)?.let { PaperPlayerHandle(it, server, logger, lookup) }

    override fun resolveOfflineId(name: String, callback: (Uuid?) -> Unit) {
        val known = server.getPlayerExact(name) ?: server.getOfflinePlayerIfCached(name)
        if (known != null) {
            callback(known.uniqueId.toKotlinUuid())
            return
        }
        // Offline name resolution blocks, so run it on asyncScheduler and bounce the reply back to the main thread
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
    private val logger: Logger,
    private val lookup: PaperPlayerLookup
) : PlayerHandle {
    override val id: Uuid get() = player.uniqueId.toKotlinUuid()
    override val name: String get() = player.name
    override val online: Boolean get() = player.isOnline
    override val dead: Boolean get() = player.isDead

    override fun position(): WorldPosition? {
        val location = player.location
        val world = location.world ?: return null
        return WorldPosition.new(world.name, location.x, location.y, location.z, location.yaw, location.pitch)
    }

    override fun respawn() {
        if (player.isDead) player.spigot().respawn()
    }

    override fun resetVitals() {
        if (player.isDead) return
        player.fireTicks = 0
        player.health = minOf(20.0, player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0)
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
            logger.warning("World '${position.world}' is not loaded; skipping teleport")
            return
        }
        lookup.scopePluginTeleport(id) {
            player.teleport(
                Location(world, position.x, position.y, position.z, position.yaw, position.pitch),
                PlayerTeleportEvent.TeleportCause.PLUGIN
            )
        }
    }
}
