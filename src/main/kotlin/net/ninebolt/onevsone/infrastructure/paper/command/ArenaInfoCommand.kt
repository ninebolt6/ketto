package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.Msg
import org.bukkit.command.CommandSender

/** /1vs1 arena info。非 OP でも参照可能な唯一の arena サブコマンド。 */
internal class ArenaInfoCommand(
    private val service: ArenaApplicationService,
    admin: ArenaAdministrationService,
    messages: Messages
) : ArenaSubcommand(admin, messages) {

    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun execute(sender: CommandSender, args: List<String>): Msg? {
        if (args.isEmpty()) return messages.usageArena
        val match = service.matchOf(args[0]) ?: run {
            messages.send(sender, messages.noArena)
            return null
        }
        messages.send(sender, messages.arenaHeader(match.arenaId.name))
        messages.send(sender, messages.arenaState(match.state))
        if (match.inProgress) {
            val p1 = match.participants[0]
            val p2 = match.participants[1]
            messages.send(sender, messages.versus(p1.name, p2.name))
            messages.send(sender, messages.winCount(match.winsOf(p1.id), match.winsOf(p2.id)))
        }
        return null
    }
}
