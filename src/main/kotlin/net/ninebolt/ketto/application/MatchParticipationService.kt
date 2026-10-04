package net.ninebolt.ketto.application

import net.ninebolt.ketto.application.port.PlayerPort
import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.ArenaMatch
import net.ninebolt.ketto.domain.DefeatCause
import net.ninebolt.ketto.domain.DefeatOutcome
import net.ninebolt.ketto.domain.ForfeitOutcome
import net.ninebolt.ketto.domain.JoinOutcome
import net.ninebolt.ketto.domain.LeaveOutcome
import net.ninebolt.ketto.domain.Participant
import kotlin.uuid.Uuid

class MatchParticipationService(
    private val sessions: ArenaSessions,
    private val playerPort: PlayerPort,
    private val recovery: InventoryRecoveryService,
    private val progression: MatchProgressionService,
    private val signService: ArenaSignService,
) {

    fun findMatchOf(playerId: Uuid): ArenaMatch? = sessions.findMatchOf(playerId)

    fun findMatchIn(name: String): ArenaMatch? = sessions.findArenaId(name)?.let { sessions.findMatch(it) }

    fun join(playerId: Uuid, playerName: String, arenaId: Arena.Id): JoinOutput {
        if (sessions.isJoined(playerId)) return JoinOutput.AlreadyJoined
        val (arena, match) = sessions.findEntry(arenaId) ?: return JoinOutput.NotFound
        if (arena !is Arena.Enabled) return JoinOutput.NotEnabled
        val participant = Participant.new(playerId, playerName)

        val step = match.join(participant)
        val output = when (step.outcome) {
            JoinOutcome.FirstJoined -> JoinOutput.JoinedWaiting
            JoinOutcome.MatchReady -> JoinOutput.JoinedStarting
            JoinOutcome.Rejected -> return JoinOutput.Rejected
        }

        val handle = playerPort.findHandle(playerId)
        recovery.findPending(playerId)?.let { ref ->
            if (handle == null || handle.dead) return JoinOutput.Rejected
            if (!recovery.restoreNow(handle, ref)) return JoinOutput.RestorePending
        }

        sessions.putMatch(step.match)
        if (step.outcome == JoinOutcome.MatchReady) progression.startInitialCountdown(arenaId, step.match.epoch)
        signService.refreshSign(step.match)
        return output
    }

    fun leave(playerId: Uuid): LeaveError? {
        val step = sessions.transactFor(playerId) { it.leaveWaiting(playerId) }
            ?: return LeaveError.NotJoined
        when (val outcome = step.outcome) {
            LeaveOutcome.NotWaiting -> return LeaveError.NotWaiting

            is LeaveOutcome.Left -> {
                signService.refreshSign(step.match)
                return null
            }
        }
    }

    // During QuitEvent the adapter provides the disconnecting player's handle, so only the UUID is needed
    fun quit(playerId: Uuid) {
        val step = sessions.transactFor(playerId) { it.forfeit(playerId) }
        if (step == null) {
            recovery.findPending(playerId)?.let { ref ->
                playerPort.findHandle(playerId)?.let { handle ->
                    recovery.restoreNow(handle, ref)
                }
            }
            return
        }
        when (val outcome = step.outcome) {
            is ForfeitOutcome.WaitingExit -> {
                signService.refreshSign(step.match)
            }

            is ForfeitOutcome.MatchEnded -> {
                progression.finishMatch(step.match, outcome.winner, outcome.loser, DefeatCause.FORFEIT)
            }

            ForfeitOutcome.NotParticipant -> Unit
        }
    }

    fun restorePending(playerId: Uuid) {
        if (sessions.isJoined(playerId)) return
        val ref = recovery.findPending(playerId) ?: return
        val handle = playerPort.findHandle(playerId) ?: return
        // A login can arrive dead; revive first so the restore lands on a live player
        handle.respawn()
        recovery.restoreNow(handle, ref)
    }

    fun defeat(playerId: Uuid, cause: DefeatCause): Boolean {
        val step = sessions.transactFor(playerId) { it.recordDefeat(playerId, cause) } ?: return false
        return when (val outcome = step.outcome) {
            DefeatOutcome.Rejected -> false

            is DefeatOutcome.RoundWon -> {
                progression.endRound(step.match, outcome, death = cause == DefeatCause.DEATH)
                true
            }

            is DefeatOutcome.MatchEnded -> {
                progression.finishMatch(step.match, outcome.winner, outcome.loser, cause)
                true
            }
        }
    }

    fun requestRespawn(playerId: Uuid) = progression.requestRespawn(playerId)
}

sealed interface JoinOutput {
    data object JoinedWaiting : JoinOutput
    data object JoinedStarting : JoinOutput
    data object AlreadyJoined : JoinOutput
    data object NotEnabled : JoinOutput
    data object Rejected : JoinOutput
    data object RestorePending : JoinOutput
    data object NotFound : JoinOutput
}

sealed interface LeaveError {
    data object NotWaiting : LeaveError
    data object NotJoined : LeaveError
}
