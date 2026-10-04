package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.ForfeitOutcome
import net.ninebolt.onevsone.domain.JoinOutcome
import net.ninebolt.onevsone.domain.LeaveOutcome
import net.ninebolt.onevsone.domain.Participant
import kotlin.uuid.Uuid

class MatchParticipationService(
    private val sessions: ArenaSessions,
    private val players: PlayerPort,
    private val recovery: InventoryRecoveryService,
    private val progression: MatchProgressionService,
    private val signs: ArenaSignService,
) {

    fun matchOf(playerId: Uuid): ArenaMatch? = sessions.matchOf(playerId)

    fun matchIn(name: String): ArenaMatch? = sessions.resolveArenaId(name)?.let { sessions.match(it) }

    fun join(playerId: Uuid, playerName: String, arenaId: Arena.Id): JoinOutput {
        if (sessions.isJoined(playerId)) return JoinOutput.AlreadyJoined
        val (arena, match) = sessions.entry(arenaId) ?: return JoinOutput.NotFound
        if (arena !is Arena.Enabled) return JoinOutput.NotEnabled
        val participant = Participant.new(playerId, playerName)

        val step = match.join(participant)
        val output = when (step.outcome) {
            JoinOutcome.FirstJoined -> JoinOutput.JoinedWaiting
            JoinOutcome.MatchReady -> JoinOutput.JoinedStarting
            JoinOutcome.Rejected -> return JoinOutput.Rejected
        }

        val handle = players.handle(playerId)
        recovery.pending(playerId)?.let { ref ->
            if (handle == null || handle.dead) return JoinOutput.Rejected
            if (!recovery.restoreNow(handle, ref)) return JoinOutput.RestorePending
        }

        sessions.putMatch(step.match)
        if (step.outcome == JoinOutcome.MatchReady) progression.startInitialCountdown(arenaId, step.match.epoch)
        signs.refreshSign(step.match)
        return output
    }

    fun leave(playerId: Uuid): LeaveError? {
        val step = sessions.transactFor(playerId) { it.leaveWaiting(playerId) }
            ?: return LeaveError.NotJoined
        when (val outcome = step.outcome) {
            LeaveOutcome.NotWaiting -> return LeaveError.NotWaiting

            is LeaveOutcome.Left -> {
                signs.refreshSign(step.match)
                return null
            }
        }
    }

    // During QuitEvent the adapter provides the disconnecting player's handle, so only the UUID is needed
    fun quit(playerId: Uuid) {
        val step = sessions.transactFor(playerId) { it.forfeit(playerId) }
        if (step == null) {
            recovery.pending(playerId)?.let { ref ->
                players.handle(playerId)?.let { handle ->
                    recovery.restoreNow(handle, ref)
                }
            }
            return
        }
        when (val outcome = step.outcome) {
            is ForfeitOutcome.WaitingExit -> {
                signs.refreshSign(step.match)
            }

            is ForfeitOutcome.MatchEnded -> {
                progression.finishMatch(step.match, outcome.winner, outcome.loser, DefeatCause.FORFEIT)
            }

            ForfeitOutcome.NotParticipant -> Unit
        }
    }

    fun restorePending(playerId: Uuid) {
        if (sessions.isJoined(playerId)) return
        val ref = recovery.pending(playerId) ?: return
        val handle = players.handle(playerId) ?: return
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
