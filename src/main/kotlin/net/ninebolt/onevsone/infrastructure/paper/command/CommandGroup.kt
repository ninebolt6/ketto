package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender
import org.bukkit.util.StringUtil
import java.util.Locale

/** Namespace that routes by subcommand name. Shared by the root and arena groups. */
internal class CommandGroup(
    private val usage: Msg,
    private val messages: Messages,
    subs: Map<String, Subcommand>
) : Subcommand {
    /** lowercase name -> (registered name, subcommand). Completion returns names with their registered case. */
    private val byName: Map<String, Pair<String, Subcommand>> = subs.entries.associateBy(
        { it.key.lowercase(Locale.ROOT) },
        { it.key to it.value }
    )

    // A namespace shows up only when it has at least one visible child
    override fun visibleTo(sender: CommandSender): Boolean =
        byName.values.any { (_, sub) -> sub.visibleTo(sender) }

    override fun execute(sender: CommandSender, args: List<String>) {
        val sub = args.firstOrNull()?.let { byName[it.lowercase(Locale.ROOT)] }?.second
        if (sub == null) {
            messages.send(sender, usage)
            return
        }
        sub.execute(sender, args.drop(1))
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size == 1) {
            val visible = byName.values.filter { (_, sub) -> sub.visibleTo(sender) }.map { (name, _) -> name }
            return StringUtil.copyPartialMatches(args[0], visible, mutableListOf())
        }
        val sub = byName[args.firstOrNull()?.lowercase(Locale.ROOT)]?.second ?: return emptyList()
        return sub.tabComplete(sender, args.drop(1))
    }
}
