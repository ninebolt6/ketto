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

/** /1vs1 stats. UUID resolution of offline players is delegated to the PlayerPort async path. */
internal class StatsCommand(
    private val service: ArenaApplicationService,
    private val players: PlayerPort,
    private val failures: FailureReporter,
    messages: Messages
) : AbstractSubcommand(messages) {

    /** Execution time of argument lookups. Entries past the cooldown are discarded each run, so only the latest request matters. */
    private val lastLookup = mutableMapOf<Uuid, Long>()

    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        val player = sender.requirePlayer() ?: return null
        val playerId = player.uniqueId.toKotlinUuid()
        if (args.isEmpty()) {
            showStats(player, playerId)
            return null
        }
        // UUID resolution of uncached names hits an external lookup, so rate-limit it
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
        // Treat corrupt stats as "none" after warning
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
        /** Cooldown interval limiting repeated argument-lookup stats calls. */
        const val LOOKUP_COOLDOWN_NANOS = 3_000_000_000L
    }
}
