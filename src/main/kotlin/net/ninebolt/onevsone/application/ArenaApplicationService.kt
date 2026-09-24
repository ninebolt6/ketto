package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.JoinOutcome
import net.ninebolt.onevsone.domain.LeaveOutcome
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.QuitOutcome
import kotlin.uuid.Uuid

class ArenaApplicationService(
    private val registry: ArenaRegistry,
    private val players: PlayerPort,
    private val recovery: PlayerRecoveryService,
    private val progression: MatchProgressionService,
    private val sync: MatchStateSync
) {

    fun arenaIdOf(playerId: Uuid): Arena.Id? = registry.arenaOf(playerId)

    fun matchOf(playerId: Uuid): ArenaMatch? =
        registry.arenaOf(playerId)?.let { registry.match(it) }

    fun arena(name: String): Arena? = registry.resolveArenaId(name)?.let { registry.arena(it) }

    fun matchOf(name: String): ArenaMatch? = registry.resolveArenaId(name)?.let { registry.match(it) }

    fun pendingRestore(playerId: Uuid) = recovery.pending(playerId)

    fun join(playerId: Uuid, playerName: String, arenaId: Arena.Id): JoinOutput {
        if (registry.isJoined(playerId)) return JoinOutput.AlreadyJoined
        val arena = registry.arena(arenaId) ?: return JoinOutput.NotFound
        val match = registry.match(arenaId) ?: return JoinOutput.NotFound
        if (!arena.enabled) return JoinOutput.NotEnabled
        val participant = Participant.new(playerId, playerName)

        val step = match.join(participant)
        if (step.outcome == JoinOutcome.Rejected) return JoinOutput.InMatch

        val handle = players.handle(playerId)
        recovery.pending(playerId)?.let { ticket ->
            if (handle == null || handle.dead) return JoinOutput.InMatch
            recovery.restoreNow(handle, ticket)
        }

        registry.putMatch(step.match, persist = sync::persistMatch)
        if (step.outcome == JoinOutcome.MatchReady) progression.startInitialCountdown(arenaId)
        sync.refreshSign(step.match)
        return when (step.outcome) {
            JoinOutcome.FirstJoined -> JoinOutput.JoinedWaiting
            JoinOutcome.MatchReady -> JoinOutput.JoinedStarting
            JoinOutcome.Rejected -> JoinOutput.InMatch
        }
    }

    fun leave(playerId: Uuid): LeaveError? {
        val arenaId = registry.arenaOf(playerId) ?: return LeaveError.NotJoined
        val step = registry.transact(arenaId, persist = sync::persistMatch) { it.leaveWaiting(playerId) }
            ?: return LeaveError.NotJoined
        when (val outcome = step.outcome) {
            LeaveOutcome.NotWaiting -> return LeaveError.NotWaiting
            is LeaveOutcome.Left -> {
                sync.refreshSign(step.match)
                return null
            }
        }
    }

    // During QuitEvent the adapter provides the disconnecting player's handle, so only the UUID is needed
    fun quit(playerId: Uuid) {
        val arenaId = registry.arenaOf(playerId)
        if (arenaId == null) {
            recovery.pending(playerId)?.let { ticket ->
                players.handle(playerId)?.let { handle ->
                    recovery.restoreNow(handle, ticket)
                }
            }
            return
        }
        val step = registry.transact(arenaId, persist = sync::persistMatch) { it.forfeit(playerId) } ?: return
        when (val outcome = step.outcome) {
            is QuitOutcome.WaitingExit -> {
                sync.refreshSign(step.match)
            }
            is QuitOutcome.MatchEnded -> {
                progression.finishMatch(step.match, outcome.winner, outcome.loser, forfeit = true, death = false)
            }
            QuitOutcome.NotParticipant -> Unit
        }
    }

    fun restorePending(playerId: Uuid) {
        if (registry.isJoined(playerId)) return
        val ticket = recovery.pending(playerId) ?: return
        val handle = players.handle(playerId) ?: return
        // A login can arrive dead; revive first so the restore lands on a live player
        handle.respawn()
        recovery.restoreNow(handle, ticket)
    }

    fun defeat(playerId: Uuid, cause: DefeatCause): Boolean {
        val arenaId = registry.arenaOf(playerId) ?: return false
        val step = registry.transact(arenaId, persist = sync::persistMatch) { it.recordDefeat(playerId, cause) }
            ?: return false
        return when (val outcome = step.outcome) {
            DefeatOutcome.Rejected -> false
            is DefeatOutcome.RoundWon -> {
                progression.endRound(step.match, outcome, death = cause == DefeatCause.DEATH)
                true
            }
            is DefeatOutcome.MatchFinished -> {
                progression.finishMatch(step.match, outcome.winner, outcome.loser, forfeit = false, death = cause == DefeatCause.DEATH)
                true
            }
        }
    }

    fun requestRespawn(playerId: Uuid) = progression.requestRespawn(playerId)

    fun abort(arenaId: Arena.Id) = progression.abort(arenaId)
}

sealed interface JoinOutput {
    data object JoinedWaiting : JoinOutput
    data object JoinedStarting : JoinOutput
    data object AlreadyJoined : JoinOutput
    data object NotEnabled : JoinOutput
    data object InMatch : JoinOutput
    data object NotFound : JoinOutput
}

sealed interface LeaveError {
    data object NotWaiting : LeaveError
    data object NotJoined : LeaveError
}
