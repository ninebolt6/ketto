package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.MatchParticipationService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.command.CommandSender

internal class ArenaInfoCommand(
    private val participation: MatchParticipationService,
    private val messenger: Messenger,
) {

    fun execute(sender: CommandSender, arenaName: String) {
        val match = participation.findMatchIn(arenaName) ?: run {
            messenger.send(sender, Message.ArenaNotFound)
            return
        }
        messenger.send(sender, Message.ArenaInfoHeader(match.arenaId.name))
        messenger.send(sender, Message.ArenaInfoState(match.state.kind))
        match.matchup()?.let { (p1, p2) ->
            messenger.send(sender, Message.ArenaInfoVersus(p1.name, p2.name))
            messenger.send(sender, Message.ArenaInfoWinCount(match.winsOf(p1.id), match.winsOf(p2.id)))
        }
    }
}
