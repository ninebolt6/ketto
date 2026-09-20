package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** /1vs1 stats。offline プレイヤーの UUID 解決は非同期で行いメインスレッドへ戻す。 */
internal class StatsCommand(
    private val plugin: JavaPlugin,
    private val service: ArenaApplicationService,
    messages: Messages,
    private val resolveOffline: (String) -> CompletableFuture<UUID> = { name ->
        CompletableFuture.supplyAsync { plugin.server.getOfflinePlayer(name).uniqueId }
    }
) : AbstractSubcommand(messages) {

    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>): String? {
        val player = sender.requirePlayer() ?: return null
        if (args.isEmpty()) {
            showStats(player, player.uniqueId)
            return null
        }
        val online = plugin.server.getPlayerExact(args[0])
        if (online != null) {
            showStats(player, online.uniqueId)
            return null
        }
        val cached = plugin.server.getOfflinePlayerIfCached(args[0])
        if (cached != null) {
            showStats(player, cached.uniqueId)
            return null
        }
        resolveOffline(args[0]).handle { uuid, error ->
            try {
                if (plugin.isEnabled) {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        if (player.isOnline) {
                            if (error != null) messages.send(player, messages.noStats) else showStats(player, uuid)
                        }
                    })
                }
            } catch (e: IllegalPluginAccessException) {
            }
            null
        }
        return null
    }

    private fun showStats(sender: CommandSender, uuid: UUID) {
        // 破損した stats は警告のうえ「なし」として扱う
        val stats = try {
            service.statsFor(uuid)
        } catch (e: PersistenceFailure) {
            plugin.logger.warning("Could not read stats for $uuid: ${e.message}")
            null
        }
        if (stats == null) {
            messages.send(sender, messages.noStats)
            return
        }
        messages.send(sender, messages.statWin(stats.wins))
        messages.send(sender, messages.statLose(stats.losses))
        messages.send(sender, messages.statRatio(stats))
    }
}
