package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.LeaveError
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.entity.Player
import kotlin.uuid.toKotlinUuid

internal class LeaveCommand(
    private val service: ArenaApplicationService,
    private val messenger: Messenger,
) {

    fun execute(player: Player) {
        when (service.leave(player.uniqueId.toKotlinUuid())) {
            null -> messenger.send(player, Message.MatchLeft)
            LeaveError.NotWaiting -> messenger.send(player, Message.MatchCannotLeave)
            LeaveError.NotJoined -> messenger.send(player, Message.MatchNotJoined)
        }
    }
}
