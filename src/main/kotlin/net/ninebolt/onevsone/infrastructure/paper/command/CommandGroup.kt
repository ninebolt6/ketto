package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender
import org.bukkit.util.StringUtil
import java.util.Locale

/** サブコマンド名でルーティングする名前空間。root と arena 配下で共用する。 */
internal class CommandGroup(
    private val usage: Msg,
    private val messages: Messages,
    subs: Map<String, Subcommand>
) : Subcommand {
    /** 小文字名 → (登録名, サブコマンド)。補完では登録どおりの大小文字を返す。 */
    private val byName: Map<String, Pair<String, Subcommand>> = subs.entries.associateBy(
        { it.key.lowercase(Locale.ROOT) },
        { it.key to it.value }
    )

    // 名前空間自体は常に表示する。配下の可視性は子の visibleTo が判断する
    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        val sub = args.firstOrNull()?.let { byName[it.lowercase(Locale.ROOT)] }?.second ?: return usage
        return sub.execute(sender, args.drop(1))
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
