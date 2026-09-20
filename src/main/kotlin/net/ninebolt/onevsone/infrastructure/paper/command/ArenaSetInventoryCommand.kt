package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.CommandSender

internal class ArenaSetInventoryCommand(
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override fun execute(sender: CommandSender, args: List<String>): String? {
        if (sender.denyUnlessOp()) return null
        val player = sender.requirePlayer() ?: return null
        if (args.size != 1) return messages.usageSetInv
        val definition = definitionOrWarn(sender, args[0]) ?: return null
        admin.setKit(definition.name, player.uniqueId)
        messages.send(sender, messages.inventorySet(definition.name))
        return null
    }
}
