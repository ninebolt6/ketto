package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender
import org.bukkit.util.StringUtil

// "create" is a reserved arena name, so it cannot collide with a real arena in first-argument dispatch
internal class ArenaGroup(
    private val usage: Message,
    private val opsUsage: Message,
    private val messenger: Messenger,
    private val admin: ArenaAdministrationService,
    private val create: Subcommand,
    private val ops: Subcommand
) : Subcommand {

    override fun visibleTo(sender: CommandSender): Boolean =
        create.visibleTo(sender) || ops.visibleTo(sender)

    override fun execute(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sendUsage(sender)
            return
        }
        if (args[0].equals(CREATE, ignoreCase = true)) {
            create.execute(sender, args.drop(1))
            return
        }
        ops.execute(sender, args)
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size == 1) {
            val candidates = admin.arenaNames() + listOfNotNull(CREATE.takeIf { create.visibleTo(sender) })
            return StringUtil.copyPartialMatches(args[0], candidates, mutableListOf())
        }
        if (args[0].equals(CREATE, ignoreCase = true)) {
            return create.tabComplete(sender, args.drop(1))
        }
        return ops.tabComplete(sender, args)
    }

    private fun sendUsage(sender: CommandSender) =
        messenger.send(sender, if (sender.isOp) opsUsage else usage)

    private companion object {
        const val CREATE = "create"
    }
}
