package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender
import kotlin.uuid.toKotlinUuid

/** `arena <name> kit set`. Copies the executor's inventory as the arena kit. */
internal class ArenaKitSetCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override val requiresPlayer: Boolean = true
    override val usage: Msg = messages.usageKit

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        val player = sender.requirePlayer() ?: return
        if (rest.isNotEmpty()) {
            messages.send(sender, usage)
            return
        }
        val arena = arenaOrWarn(sender, arenaName) ?: return
        admin.setKit(arena.name, player.uniqueId.toKotlinUuid())
        messages.send(sender, messages.inventorySet(arena.name))
    }
}
