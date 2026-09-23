package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

/** /1vs1 arena <name> info. The only arena op available to non-OP users; also the default for bare `arena <name>`. */
internal class ArenaInfoCommand(
    private val service: ArenaApplicationService,
    admin: ArenaAdministrationService,
    messenger: Messenger
) : ArenaSubcommand(admin, messenger) {

    override val usage: Message = Message.UsageArena
    override fun denied(sender: CommandSender): Boolean = false

    override fun visibleTo(sender: CommandSender): Boolean = true

    override fun executeFor(sender: CommandSender, arenaName: String, rest: List<String>) {
        val match = service.matchOf(arenaName) ?: run {
            messenger.send(sender, Message.ArenaNotFound)
            return
        }
        messenger.send(sender, Message.ArenaInfoHeader(match.arenaId.name))
        messenger.send(sender, Message.ArenaInfoState(match.state))
        if (match.inProgress) {
            val p1 = match.participants[0]
            val p2 = match.participants[1]
            messenger.send(sender, Message.ArenaInfoVersus(p1.name, p2.name))
            messenger.send(sender, Message.ArenaInfoWinCount(match.winsOf(p1.id), match.winsOf(p2.id)))
        }
    }
}
