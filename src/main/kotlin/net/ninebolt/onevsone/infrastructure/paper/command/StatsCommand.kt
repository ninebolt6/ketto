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

    /** 引数付き検索の実行時刻。間隔が空いた分は毎回捨てるので直近の要求だけを保持する。 */
    private val lastLookup = mutableMapOf<Uuid, Long>()

    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        val player = sender.requirePlayer() ?: return null
        val playerId = player.uniqueId.toKotlinUuid()
        if (args.isEmpty()) {
            showStats(player, playerId)
            return null
        }
        // 未キャッシュ名の UUID 解決は外部参照を伴うため、連投を抑える
        val now = System.nanoTime()
        lastLookup.entries.removeAll { now - it.value >= LOOKUP_COOLDOWN_NANOS }
        if (playerId in lastLookup) {
            messages.send(player, messages.statsCooldown)
            return null
        }
        lastLookup[playerId] = now
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

    private companion object {
        /** 引数付き stats の連投を抑える間隔。 */
        const val LOOKUP_COOLDOWN_NANOS = 3_000_000_000L
    }
}
