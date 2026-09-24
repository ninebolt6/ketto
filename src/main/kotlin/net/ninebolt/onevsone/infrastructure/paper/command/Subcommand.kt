package net.ninebolt.onevsone.infrastructure.paper.command

import org.bukkit.command.CommandSender

internal interface Subcommand {
    fun visibleTo(sender: CommandSender): Boolean = sender.isOp

    fun execute(sender: CommandSender, args: List<String>)

    fun tabComplete(sender: CommandSender, args: List<String>): List<String> = emptyList()
}
