package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.Message
import net.ninebolt.onevsone.infrastructure.paper.Messenger
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Base collecting canned denial replies. The checks themselves are up to each handler. */
internal abstract class AbstractSubcommand(
    protected val messenger: Messenger
) : Subcommand {

    protected fun CommandSender.denyUnlessOp(): Boolean {
        if (isOp) return false
        messenger.send(this, Message.CommandNoPermission)
        return true
    }

    protected fun CommandSender.requirePlayer(): Player? =
        this as? Player ?: run {
            messenger.send(this, Message.CommandPlayerOnly)
            null
        }
}
