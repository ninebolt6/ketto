package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender
import java.util.Locale

/** サブコマンド名でルーティングする名前空間。root と arena 配下で共用する。 */
internal class CommandGroup(
    private val usage: String,
    private val messages: Messages,
    subs: Map<String, Subcommand>
) : Subcommand {
    private val byName = subs.mapKeys { (name, _) -> name.lowercase(Locale.ROOT) }

    // 名前空間自体は常に表示する。配下の可視性は子の visibleTo が判断する
    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>): String? {
        val sub = args.firstOrNull()?.let { byName[it.lowercase(Locale.ROOT)] } ?: return usage
        return sub.execute(sender, args.drop(1))
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size == 1) {
            return byName.entries
                .filter { it.value.visibleTo(sender) }
                .map { it.key }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        val sub = byName[args.firstOrNull()?.lowercase(Locale.ROOT)] ?: return emptyList()
        return sub.tabComplete(sender, args.drop(1))
    }
}
