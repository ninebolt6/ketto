package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.PlayerStatsService
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender
import java.util.logging.Logger
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

internal class StatsCommand(
    private val statsService: PlayerStatsService,
    private val players: PlayerPort,
    private val logger: Logger,
    messenger: Messenger,
) : AbstractSubcommand(messenger) {

    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>) {
        val player = sender.requirePlayer() ?: return
        val playerId = player.uniqueId.toKotlinUuid()
        if (args.isEmpty()) {
            showStats(player, playerId)
            return
        }
        // UUID resolution of uncached names hits an external lookup, so rate-limit it
        if (!statsService.tryAcquireStatsLookup(playerId, System.nanoTime())) {
            messenger.send(player, Message.StatsCooldown)
            return
        }
        players.resolveOfflineId(args[0]) { uuid ->
            if (player.isOnline) {
                if (uuid == null) messenger.send(player, Message.StatsNone) else showStats(player, uuid)
            }
        }
    }

    private fun showStats(sender: CommandSender, uuid: Uuid) {
        val stats = try {
            statsService.statsFor(uuid)
        } catch (e: PersistenceFailure) {
            logger.warning("Could not read stats for $uuid: ${e.message}")
            null
        }
        if (stats == null) {
            messenger.send(sender, Message.StatsNone)
            return
        }
        messenger.send(sender, Message.StatsWin(stats.wins))
        messenger.send(sender, Message.StatsLose(stats.losses))
        messenger.send(sender, Message.StatsRatio(stats))
    }
}
