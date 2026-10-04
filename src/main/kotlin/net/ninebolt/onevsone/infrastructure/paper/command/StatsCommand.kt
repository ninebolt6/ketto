package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.PlayerStatsService
import net.ninebolt.onevsone.application.StatsOutput
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.uuid.toKotlinUuid

internal class StatsCommand(
    private val statsService: PlayerStatsService,
    private val messenger: Messenger,
) {

    fun execute(player: Player, targetName: String?) {
        val playerId = player.uniqueId.toKotlinUuid()
        if (targetName == null) {
            render(player, statsService.ownStats(playerId))
            return
        }
        statsService.lookupStats(playerId, targetName, System.nanoTime()) { output ->
            if (player.isOnline) render(player, output)
        }
    }

    private fun render(sender: CommandSender, output: StatsOutput) {
        when (output) {
            is StatsOutput.Found -> {
                messenger.send(sender, Message.StatsWin(output.stats.wins))
                messenger.send(sender, Message.StatsLoss(output.stats.losses))
                messenger.send(sender, Message.StatsRatio(output.stats))
            }

            StatsOutput.Missing -> messenger.send(sender, Message.StatsNone)

            StatsOutput.Cooldown -> messenger.send(sender, Message.StatsCooldown)
        }
    }
}
