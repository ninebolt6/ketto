package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** 定型の拒否応答を集約する基底。判定自体は各ハンドラが行う。 */
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
