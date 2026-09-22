package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Base collecting canned denial replies. The checks themselves are up to each handler. */
internal abstract class AbstractSubcommand(
    protected val messages: Messages
) : Subcommand {

    protected fun CommandSender.denyUnlessOp(): Boolean {
        if (isOp) return false
        messages.send(this, messages.noPermission)
        return true
    }

    protected fun CommandSender.requirePlayer(): Player? =
        this as? Player ?: run {
            messages.send(this, messages.playerOnly)
            null
        }
}
