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

    override val requiresPlayer: Boolean = true
    override val usage: Msg get() = messages.usageSetSpawn(number)

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        val player = sender.requirePlayer() ?: return
        if (rest.isNotEmpty()) {
            messages.send(sender, usage)
            return
        }
        val arena = arenaOrWarn(sender, arenaName) ?: return
        // Positions without a world skip saving, but the reply stays the success message as before
        player.location.toWorldPosition()?.let { admin.setSpawn(arena.name, number, it) }
        messages.send(sender, messages.spawnSet(arena.name, number))
    }
}
