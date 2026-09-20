package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

/** /1vs1 stats。offline プレイヤーの UUID 解決は PlayerPort の非同期経路に委譲する。 */
internal class StatsCommand(
    private val service: ArenaApplicationService,
    private val players: PlayerPort,
    private val failures: FailureReporter,
    messages: Messages
) : AbstractSubcommand(messages) {

    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        val player = sender.requirePlayer() ?: return null
        if (args.isEmpty()) {
            showStats(player, player.uniqueId.toKotlinUuid())
            return null
        }
        players.resolveOfflineId(args[0]) { uuid ->
            if (player.isOnline) {
                if (uuid == null) messages.send(player, messages.noStats) else showStats(player, uuid)
            }
        }
        return null
    }

    private fun showStats(sender: CommandSender, uuid: Uuid) {
        // 破損した stats は警告のうえ「なし」として扱う
        val stats = try {
            service.statsFor(uuid)
        } catch (e: PersistenceFailure) {
            failures.warn("Could not read stats for $uuid: ${e.message}")
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
