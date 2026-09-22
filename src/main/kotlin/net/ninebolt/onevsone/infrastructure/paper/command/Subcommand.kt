package net.ninebolt.onevsone.infrastructure.paper.command

import org.bukkit.command.CommandSender

/**
 * A single /1vs1 subcommand. CommandGroup routes on the first argument;
 * permission, argument, and existence checks are each implementation's job.
 * Replies — including usage on syntax errors — are sent inside execute.
 */
internal interface Subcommand {
    /** Whether the name may be suggested in tab completion. Whether it can actually run is execute's responsibility. */
    fun visibleTo(sender: CommandSender): Boolean = sender.isOp

    fun execute(sender: CommandSender, args: List<String>)

    fun tabComplete(sender: CommandSender, args: List<String>): List<String> = emptyList()
}
