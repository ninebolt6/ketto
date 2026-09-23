package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.infrastructure.paper.Message
import net.ninebolt.onevsone.infrastructure.paper.Messenger
import org.bukkit.command.CommandSender
import org.bukkit.util.StringUtil

/** Base for /1vs1 arena * commands. Runs the deny -> player -> arity checks, then delegates with the first argument as the arena name. */
internal abstract class ArenaSubcommand(
    protected val admin: ArenaAdministrationService,
    messenger: Messenger
) : AbstractSubcommand(messenger) {

    protected abstract val usage: Message
    protected open val requiresPlayer: Boolean = false

    /** Reply has been sent when this returns true. Default requires OP. */
    protected open fun denied(sender: CommandSender): Boolean = sender.denyUnlessOp()

    final override fun execute(sender: CommandSender, args: List<String>) {
        if (denied(sender)) return
        if (requiresPlayer && sender.requirePlayer() == null) return
        if (args.isEmpty()) {
            messenger.send(sender, usage)
            return
        }
        executeFor(sender, args[0], args.drop(1))
    }

    protected abstract fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>)

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> =
        if (args.size == 1) StringUtil.copyPartialMatches(args[0], admin.arenaNames(), mutableListOf()) else emptyList()

    protected fun arenaOrWarn(sender: CommandSender, name: String): Arena? =
        admin.arena(name) ?: run {
            messenger.send(sender, Message.ArenaNotFound)
            null
        }
}
