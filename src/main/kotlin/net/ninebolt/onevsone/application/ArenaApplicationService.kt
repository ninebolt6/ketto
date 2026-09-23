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

/**
 * Orchestration of join/leave/quit and the defeat entry point.
 * The progression engine (initial countdown, round transitions, resolution,
 * aborts) is delegated to MatchProgressionService (one-way dependency).
 * Inputs are UUIDs etc.; outputs are results or aggregate snapshots. It takes
 * neither JavaPlugin nor Messenger.
 *
 * Match commits go through ArenaRegistry with the persist hook: the
 * participant ledger and status projection are written before the in-memory
 * state is replaced. Projection failures degrade to a report and never block
 * the flow; world side effects (inventory restores, teleports) run outside
 * that boundary.
 */
class ArenaApplicationService(
    private val registry: ArenaRegistry,
    private val players: PlayerPort,
    private val recovery: PlayerRecoveryService,
    private val progression: MatchProgressionService,
    private val sync: MatchStateSync
) {

    // ---- Queries -------------------------------------------------------

    fun arenaIdOf(playerId: Uuid): Arena.Id? = registry.arenaOf(playerId)

    fun matchOf(playerId: Uuid): ArenaMatch? =
        registry.arenaOf(playerId)?.let { registry.match(it) }

    fun arena(name: String): Arena? = registry.resolveArenaId(name)?.let { registry.arena(it) }

    fun matchOf(name: String): ArenaMatch? = registry.resolveArenaId(name)?.let { registry.match(it) }

    fun pendingRestore(playerId: Uuid) = recovery.pending(playerId)

    // ---- Join, leave, quit ---------------------------------------------------

    fun join(playerId: Uuid, playerName: String, arenaId: Arena.Id): JoinOutput {
        if (registry.isJoined(playerId)) return JoinOutput.AlreadyJoined
        val arena = registry.arena(arenaId) ?: return JoinOutput.NotFound
        val match = registry.match(arenaId) ?: return JoinOutput.NotFound
        if (!arena.enabled) return JoinOutput.NotEnabled
        val participant = Participant.new(playerId, playerName)

        // join is a pure function: rejection is decided before committing
        val step = match.join(participant)
        if (step.outcome == JoinOutcome.Rejected) return JoinOutput.InMatch

        // Complete any unrestored backup from a previous match before rejoining (does not read the inventory)
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
                // Leaving before the match starts does not touch the inventory (no backup, no restore)
                sync.refreshSign(step.match)
                return null
            }
        }
    }

    /**
     * During QuitEvent the adapter provides an operation handle for the
     * disconnecting player, so processing here needs only the UUID.
     */
    fun quit(playerId: Uuid) {
        val arenaId = registry.arenaOf(playerId)
        if (arenaId == null) {
            // Even when not participating, restore any unrestored backup so the disconnect is safe
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

    /** Equivalent of PlayerJoinEvent. */
    fun restorePending(playerId: Uuid) {
        if (registry.isJoined(playerId)) return
        val ticket = recovery.pending(playerId) ?: return
        val handle = players.handle(playerId) ?: return
        // A login can arrive dead; revive first so the restore lands on a live player
        handle.respawn()
        recovery.restoreNow(handle, ticket)
    }

    // ---- Win/loss --------------------------------------------------------------

    /** true when the defeat was accepted. */
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

    /** Respawn reservation for a player who died but was not accepted as a defeat. */
    fun requestRespawn(playerId: Uuid) = progression.requestRespawn(playerId)

    // ---- Abort ---------------------------------------------------------------

    fun abort(arenaId: Arena.Id) = progression.abort(arenaId)
}

// ---- Use-case results. Conversion to message text happens on the caller's
// side (infrastructure) ------------------------------------------------------

/**
 * Join can succeed in more than one way, so the output is a sealed type
 * rather than a nullable error.
 */
sealed interface JoinOutput {
    /** Registered as the first player; now waiting in ONEMORE. */
    data object JoinedWaiting : JoinOutput
    /** Registered as the second player; the initial countdown has started. */
    data object JoinedStarting : JoinOutput
    data object AlreadyJoined : JoinOutput
    data object NotEnabled : JoinOutput
    /** Cannot join: match in progress / full / holder of an unrestored backup is dead, etc. */
    data object InMatch : JoinOutput
    data object NotFound : JoinOutput
}

/** Rejection reasons; `null` return means the leave was applied. */
sealed interface LeaveError {
    /** The player is joined but the match is past the leavable waiting phase. */
    data object NotWaiting : LeaveError
    /** The player is not registered in any arena. */
    data object NotJoined : LeaveError
}
