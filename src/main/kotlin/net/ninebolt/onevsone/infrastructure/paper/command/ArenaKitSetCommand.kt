package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender
import kotlin.uuid.toKotlinUuid

internal class ArenaKitSetCommand(
    admin: ArenaAdministrationService,
    messenger: Messenger,
) : ArenaSubcommand(admin, messenger) {

    override val requiresPlayer: Boolean = true
    override val usage: Message = Message.UsageKit

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        val player = sender.requirePlayer() ?: return
        if (rest.isNotEmpty()) {
            messenger.send(sender, usage)
            return
        }
        val arena = arenaOrWarn(sender, arenaName) ?: return
        admin.setKit(arena.name, player.uniqueId.toKotlinUuid())
        messenger.send(sender, Message.ArenaInventorySet(arena.name))
    }
}
