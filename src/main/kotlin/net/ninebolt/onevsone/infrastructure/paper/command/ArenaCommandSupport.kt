package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal fun arenaOrWarn(
    admin: ArenaAdministrationService,
    messenger: Messenger,
    sender: CommandSender,
    arenaName: String,
): Arena? = admin.arena(arenaName) ?: run {
    messenger.send(sender, Message.ArenaNotFound)
    null
}
