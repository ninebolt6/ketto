package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Message
import net.ninebolt.onevsone.infrastructure.paper.Messenger
import org.bukkit.command.CommandSender
import org.bukkit.util.StringUtil
import java.util.Locale

/**
 * Namespace bound to an arena: args[0] is the arena name and args[1] selects
 * the op. Children keep the ArenaSubcommand contract, so the bound name is
 * passed back as their first argument. defaultOp answers the bare
 * `arena <name>` form. For ops-only namespaces, passing a denial message as
 * `usage` keeps non-OP replies consistent with leaf denial.
 */
internal class ArenaScopedGroup(
    private val usage: Message,
    private val opsUsage: Message,
    private val messenger: Messenger,
    private val admin: ArenaAdministrationService,
    ops: Map<String, Subcommand>,
    private val defaultOp: Subcommand? = null
) : Subcommand {

    /** lowercase name -> (registered name, op). Completion returns names with their registered case. */
    private val byName: Map<String, Pair<String, Subcommand>> = ops.entries.associateBy(
        { it.key.lowercase(Locale.ROOT) },
        { it.key to it.value }
    )

    // Visible only when at least one child op is (nested namespaces hide their ops-only groups from non-OPs)
    override fun visibleTo(sender: CommandSender): Boolean =
        byName.values.any { (_, op) -> op.visibleTo(sender) }

    override fun execute(sender: CommandSender, args: List<String>) {
        val op = when {
            args.isEmpty() -> null
            args.size == 1 -> defaultOp
            else -> byName[args[1].lowercase(Locale.ROOT)]?.second
        }
        if (op == null) {
            sendUsage(sender)
            return
        }
        op.execute(sender, listOf(args[0]) + args.drop(2))
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> = when (args.size) {
        1 -> StringUtil.copyPartialMatches(args[0], admin.arenaNames(), mutableListOf())
        2 -> {
            val visible = byName.values.filter { (_, op) -> op.visibleTo(sender) }.map { (name, _) -> name }
            StringUtil.copyPartialMatches(args[1], visible, mutableListOf())
        }
        else -> byName[args.getOrNull(1)?.lowercase(Locale.ROOT)]?.second
            ?.tabComplete(sender, listOf(args[0]) + args.drop(2)) ?: emptyList()
    }

    private fun sendUsage(sender: CommandSender) =
        messenger.send(sender, if (sender.isOp) opsUsage else usage)
}
