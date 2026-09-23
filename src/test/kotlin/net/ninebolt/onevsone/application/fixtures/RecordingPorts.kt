package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

class RecordingPresentation : MatchPresentationPort {
    data class Countdown(val ids: List<Uuid>, val seconds: Int)

    val countdownTicks = mutableListOf<Countdown>()
    val roundCountdownTicks = mutableListOf<Countdown>()
    val matchStarts = mutableListOf<List<Uuid>>()
    val roundStarts = mutableListOf<List<Uuid>>()
    val roundWins = mutableListOf<Triple<List<Uuid>, Int, String>>()
    val roundEndSounds = mutableListOf<WorldPosition>()
    val champions = mutableListOf<Pair<Arena.Id, String>>()
    val fireworks = mutableListOf<Uuid>()
    val scoreboards = mutableListOf<ArenaMatch>()
    val clearedScoreboards = mutableListOf<Uuid>()
    val signUpdates = mutableListOf<Triple<Arena.Id, BlockPosition, ArenaState>>()

    override fun countdownTick(participantIds: List<Uuid>, secondsLeft: Int) {
        countdownTicks += Countdown(participantIds, secondsLeft)
    }

    override fun roundCountdownTick(participantIds: List<Uuid>, secondsLeft: Int) {
        roundCountdownTicks += Countdown(participantIds, secondsLeft)
    }

    override fun matchStart(participantIds: List<Uuid>) {
        matchStarts += participantIds
    }

    override fun roundStart(participantIds: List<Uuid>) {
        roundStarts += participantIds
    }

    override fun roundWon(participantIds: List<Uuid>, round: Int, winnerName: String) {
        roundWins += Triple(participantIds, round, winnerName)
    }

    override fun roundEndSound(position: WorldPosition) {
        roundEndSounds += position
    }

    override fun champion(arena: Arena.Id, winnerName: String) {
        champions += arena to winnerName
    }

    override fun championFirework(playerId: Uuid) {
        fireworks += playerId
    }

    override fun updateScoreboard(match: ArenaMatch) {
        scoreboards += match
    }

    override fun clearScoreboard(playerId: Uuid) {
        clearedScoreboards += playerId
    }

    override fun updateSign(arena: Arena.Id, position: BlockPosition, state: ArenaState) {
        signUpdates += Triple(arena, position, state)
    }
}

class RecordingFailures : FailureReporter {
    val warnings = mutableListOf<String>()
    val reports = mutableListOf<Pair<String, Throwable>>()

    override fun warn(message: String) {
        warnings += message
    }

    override fun report(context: String, error: Throwable) {
        reports += context to error
    }
}
