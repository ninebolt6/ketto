package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender
import org.bukkit.util.StringUtil

/** arena サブコマンド共通部品。全て第 1 引数にアリーナ名を取る前提。 */
internal abstract class ArenaSubcommand(
    protected val admin: ArenaAdministrationService,
    messages: Messages
) : AbstractSubcommand(messages) {

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> =
        if (args.size == 1) StringUtil.copyPartialMatches(args[0], admin.arenaNames(), mutableListOf()) else emptyList()

    protected fun arenaOrWarn(sender: CommandSender, name: String): Arena? =
        admin.arena(name) ?: run {
            messages.send(sender, messages.noArena)
            null
        }
}
