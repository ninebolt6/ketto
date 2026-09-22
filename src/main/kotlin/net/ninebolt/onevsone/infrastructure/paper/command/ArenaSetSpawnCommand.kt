package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import net.ninebolt.onevsone.infrastructure.paper.toWorldPosition
import org.bukkit.command.CommandSender

/** setspawn1 / setspawn2. `number` switches the usage and reply messages. */
internal class ArenaSetSpawnCommand(
    private val number: Int,
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        if (sender.denyUnlessOp()) return null
        val player = sender.requirePlayer() ?: return null
        if (args.size != 1) return messages.usageSetSpawn(number)
        val arena = arenaOrWarn(sender, args[0]) ?: return null
        // Positions without a world skip saving, but the reply stays the success message as before
        player.location.toWorldPosition()?.let { admin.setSpawn(arena.name, number, it) }
        messages.send(sender, messages.spawnSet(arena.name, number))
        return null
    }
}
