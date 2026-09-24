package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

internal abstract class AbstractSubcommand(
    protected val messenger: Messenger,
) : Subcommand {

    protected fun CommandSender.denyUnlessOp(): Boolean {
        if (isOp) return false
        messenger.send(this, Message.CommandNoPermission)
        return true
    }

    protected fun CommandSender.requirePlayer(): Player? = this as? Player ?: run {
        messenger.send(this, Message.CommandPlayerOnly)
        null
    }
}
