package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

interface PresentationPort {
    fun countdownTick(participantIds: List<Uuid>, secondsLeft: Int)
    fun roundCountdownTick(participantIds: List<Uuid>, secondsLeft: Int)
    fun matchStart(participantIds: List<Uuid>)
    fun roundStart(participantIds: List<Uuid>)
    fun roundWon(participantIds: List<Uuid>, round: Int, winnerName: String)
    fun roundEndSound(position: WorldPosition)
    fun champion(arena: Arena.Id, winnerName: String)
    fun championFirework(playerId: Uuid)
    fun updateScoreboard(match: ArenaMatch)
    fun clearScoreboard(playerId: Uuid)
    fun updateSign(arena: Arena.Id, position: BlockPosition, state: ArenaState)
}
