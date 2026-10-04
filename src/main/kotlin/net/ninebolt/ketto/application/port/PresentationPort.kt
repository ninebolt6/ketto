package net.ninebolt.ketto.application.port

import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.ArenaMatch
import net.ninebolt.ketto.domain.ArenaState
import net.ninebolt.ketto.domain.BlockPosition
import net.ninebolt.ketto.domain.WorldPosition
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
    fun updateSign(arena: Arena, position: BlockPosition, kind: ArenaState.Kind)
}
