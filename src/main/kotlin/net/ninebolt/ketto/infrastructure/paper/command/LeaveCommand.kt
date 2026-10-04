package net.ninebolt.ketto.infrastructure.paper.command

import net.ninebolt.ketto.application.LeaveError
import net.ninebolt.ketto.application.MatchParticipationService
import net.ninebolt.ketto.infrastructure.paper.message.Message
import net.ninebolt.ketto.infrastructure.paper.message.Messenger
import org.bukkit.entity.Player
import kotlin.uuid.toKotlinUuid

internal class LeaveCommand(
    private val participation: MatchParticipationService,
    private val messenger: Messenger,
) {

    fun execute(player: Player) {
        when (participation.leave(player.uniqueId.toKotlinUuid())) {
            null -> messenger.send(player, Message.MatchLeft)
            LeaveError.NotWaiting -> Unit
            LeaveError.NotJoined -> messenger.send(player, Message.MatchNotJoined)
        }
    }
}
