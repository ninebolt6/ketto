package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.paper.toWorldPosition
import org.bukkit.command.CommandSender
import org.bukkit.util.StringUtil

/** `arena <name> spawn set <slot>`. The slot (1 or 2) arrives as the op argument. */
internal class ArenaSpawnSetCommand(
    admin: ArenaAdministrationService,
    messenger: Messenger
) : ArenaSubcommand(admin, messenger) {

    override val requiresPlayer: Boolean = true
    override val usage: Message = Message.UsageSpawn

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        val player = sender.requirePlayer() ?: return
        val slot = rest.singleOrNull()?.toIntOrNull()?.takeIf { it in 1..2 }
        if (slot == null) {
            messenger.send(sender, usage)
            return
        }
        val arena = arenaOrWarn(sender, arenaName) ?: return
        // Positions without a world skip saving but still report success
        player.location.toWorldPosition()?.let { admin.setSpawn(arena.name, slot, it) }
        messenger.send(sender, Message.ArenaSpawnSet(arena.name, slot))
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> =
        if (args.size == 2) StringUtil.copyPartialMatches(args[1], SLOTS, mutableListOf())
        else super.tabComplete(sender, args)

    private companion object {
        val SLOTS = listOf("1", "2")
    }
}
