package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

/**
 * Match-progression display and effects. String formatting and Adventure live
 * on the Paper side. Direct replies to users (join results etc.) are returned
 * as use-case results and not included here.
 */
interface MatchPresentationPort {
    /** Initial countdown: "Teleport in: Ns" + sound (pitch 1). */
    fun countdownTick(participantIds: List<Uuid>, secondsLeft: Int)
    /** Inter-round countdown: "Starting in: Ns" + sound (pitch 1). */
    fun roundCountdownTick(participantIds: List<Uuid>, secondsLeft: Int)
    /** Match start: "Game Start!" + sound (pitch 2). */
    fun matchStart(participantIds: List<Uuid>)
    /** Round resume: "Start!" + sound (pitch 2). */
    fun roundStart(participantIds: List<Uuid>)
    /** Round decided: "Round [N] Winner: name" to both players. */
    fun roundWon(participantIds: List<Uuid>, round: Int, winnerName: String)
    /** Explosion sound for the round decision (at the loser's position). */
    fun roundEndSound(position: WorldPosition)
    /** Victory broadcast. */
    fun champion(arena: Arena.Id, winnerName: String)
    /** Firework at the winner's position. */
    fun championFirework(playerId: Uuid)
    /** Updates the sidebar scoreboard with the latest match state. */
    fun updateScoreboard(match: ArenaMatch)
    fun clearScoreboard(playerId: Uuid)
    /** Repaints the join sign at the given block (joinability + state line). */
    fun updateSign(arena: Arena.Id, position: BlockPosition, state: ArenaState)
}
