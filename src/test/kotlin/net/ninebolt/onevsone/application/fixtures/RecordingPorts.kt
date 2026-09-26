package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.WorldPosition
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.uuid.Uuid

class RecordingPresentation : PresentationPort {
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
    val signUpdates = mutableListOf<Triple<Arena, BlockPosition, ArenaState>>()

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

    override fun updateSign(arena: Arena, position: BlockPosition, state: ArenaState) {
        signUpdates += Triple(arena, position, state)
    }
}

class RecordingLogger : Logger("test", null) {
    val records = mutableListOf<LogRecord>()

    override fun log(record: LogRecord) {
        records += record
    }

    val warnings: List<String> get() = records.filter { it.level == Level.WARNING }.map { it.message }
    val reports: List<LogRecord> get() = records.filter { it.level == Level.SEVERE }
}
