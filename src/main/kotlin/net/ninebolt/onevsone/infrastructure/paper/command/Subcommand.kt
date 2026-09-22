package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender

/**
 * A single /1vs1 subcommand. CommandGroup routes on the first argument;
 * permission, argument, and existence checks are each implementation's job.
 */
internal interface Subcommand {
    /** Whether the name may be suggested in tab completion. Whether it can actually run is execute's responsibility. */
    fun visibleTo(sender: CommandSender): Boolean = sender.isOp

    /** @return usage to display on syntax error; null if a reply was already sent */
    fun execute(sender: CommandSender, args: List<String>): Msg?

    fun tabComplete(sender: CommandSender, args: List<String>): List<String> = emptyList()
}
