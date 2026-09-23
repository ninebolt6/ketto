package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.paper.toBlockPosition
import org.bukkit.block.Sign
import org.bukkit.command.CommandSender

/** `arena <name> sign set`. Registers the sign the executor is looking at. */
internal class ArenaSignSetCommand(
    admin: ArenaAdministrationService,
    private val signs: ArenaSignService,
    messenger: Messenger
) : ArenaSubcommand(admin, messenger) {

    override val requiresPlayer: Boolean = true
    override val usage: Message = Message.UsageSignSet

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        val player = sender.requirePlayer() ?: return
        if (rest.isNotEmpty()) {
            messenger.send(sender, usage)
            return
        }
        val arena = arenaOrWarn(sender, arenaName) ?: return
        val target = player.getTargetBlockExact(10)
        if (target == null || target.state !is Sign) {
            messenger.send(sender, Message.SignLookAt)
            return
        }
        val position = target.toBlockPosition()
        val existing = signs.signOwner(position)
        if (existing != null && existing != arena.name) {
            messenger.send(sender, Message.SignTaken)
            return
        }
        signs.setSign(arena.name, position)
    }
}
