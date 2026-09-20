package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender

/** arena サブコマンド共通部品。全て第 1 引数にアリーナ名を取る前提。 */
internal abstract class ArenaSubcommand(
    protected val admin: ArenaAdministrationService,
    messages: Messages
) : AbstractSubcommand(messages) {

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> =
        if (args.size == 1) admin.arenaNames().filter { it.startsWith(args[0]) } else emptyList()

    protected fun definitionOrWarn(sender: CommandSender, name: String): ArenaDefinition? =
        admin.definition(name) ?: run {
            messages.send(sender, messages.noArena)
            null
        }
}
