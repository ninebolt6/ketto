package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.application.port.warnOnFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.JoinOutcome
import net.ninebolt.onevsone.domain.LeaveOutcome
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.domain.QuitOutcome
import kotlin.uuid.Uuid

/**
 * Orchestration of join/leave/quit and the defeat entry point.
 * The progression engine (initial countdown, round transitions, resolution,
 * aborts) is delegated to MatchProgressionService (one-way dependency).
 * Inputs are UUIDs etc.; outputs are results or aggregate snapshots. It takes
 * neither JavaPlugin nor Messenger.
 * All operations are assumed to be serialized on the main thread.
 */
class ArenaApplicationService(
    private val registry: ArenaRegistry,
    private val stats: PlayerStatsRepository,
    private val players: PlayerPort,
    private val recovery: PlayerRecoveryService,
    private val failures: FailureReporter,
    private val progression: MatchProgressionService,
    private val sync: MatchStateSync
) {

    // ---- Queries -------------------------------------------------------

    fun arenaIdOf(playerId: Uuid): Arena.Id? = registry.arenaOf(playerId)

    fun matchOf(playerId: Uuid): ArenaMatch? =
        registry.arenaOf(playerId)?.let { registry.match(it) }

    fun arena(name: String): Arena? = registry.resolveArenaId(name)?.let { registry.arena(it) }

    fun matchOf(name: String): ArenaMatch? = registry.resolveArenaId(name)?.let { registry.match(it) }

    /** Throws PersistenceFailure on corruption (handled by the caller). */
    fun statsFor(playerId: Uuid): PlayerStats? = stats.find(playerId)

    /**
     * Rate-limits the named-stats lookup, which resolves uncached names through
     * an external call. Once per cooldown window per requester.
     */
    private val statsLookupThrottle = RequestThrottle(STATS_LOOKUP_COOLDOWN_NANOS)

    /** true when the requester may run a named-stats lookup now. */
    fun tryAcquireStatsLookup(playerId: Uuid, nowNanos: Long): Boolean =
        statsLookupThrottle.tryAcquire(playerId, nowNanos)

    fun pendingRestore(playerId: Uuid) = recovery.pending(playerId)

    // ---- Join, leave, quit ---------------------------------------------------

    fun join(playerId: Uuid, playerName: String, arenaId: Arena.Id): JoinReply {
        if (registry.isJoined(playerId)) return JoinReply.AlreadyJoined
        val arena = registry.arena(arenaId) ?: return JoinReply.NotFound
        val match = registry.match(arenaId) ?: return JoinReply.NotFound
        if (!arena.enabled) return JoinReply.NotEnabled
        val participant = Participant.new(playerId, playerName)

        // join is a pure function: rejection is decided before committing
        val step = match.join(participant)
        if (step.outcome == JoinOutcome.Rejected) return JoinReply.InMatch

        // Complete any unrestored backup from a previous match before rejoining (does not read the inventory)
        val handle = players.handle(playerId)
        recovery.pending(playerId)?.let { ticket ->
            if (handle == null || handle.dead) return JoinReply.InMatch
            recovery.restoreNow(handle, ticket)
        }

        // Membership registration. On failure we are still pre-commit,
        // so the exception propagates with nothing changed.
        sync.register(participant, arenaId)
        registry.putMatch(step.match)
        if (step.outcome == JoinOutcome.MatchReady) progression.startInitialCountdown(arenaId)
        sync.publish(step.match)
        return when (step.outcome) {
            JoinOutcome.FirstJoined -> JoinReply.JoinedWaiting
            JoinOutcome.MatchReady -> JoinReply.JoinedStarting
            JoinOutcome.Rejected -> JoinReply.InMatch
        }
    }

    fun leave(playerId: Uuid): LeaveReply {
        val arenaId = registry.arenaOf(playerId) ?: return LeaveReply.NotJoined
        val step = registry.transact(arenaId) { it.leaveWaiting(playerId) }
            ?: return LeaveReply.NotJoined
        when (val outcome = step.outcome) {
            LeaveOutcome.NotWaiting -> return LeaveReply.NotWaiting
            is LeaveOutcome.Left -> {
                unregister(outcome.participant)
                // Leaving before the match starts does not touch the inventory (no backup, no restore)
                sync.publish(step.match)
                return LeaveReply.Left
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
        val step = registry.transact(arenaId) { it.forfeit(playerId) } ?: return
        when (val outcome = step.outcome) {
            is QuitOutcome.WaitingExit -> {
                unregister(outcome.participant)
                sync.publish(step.match)
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
        val step = registry.transact(arenaId) { it.recordDefeat(playerId, cause) } ?: return false
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

    /** Ledger-unregister failures are swallowed into a warn so later processing continues. */
    private fun unregister(participant: Participant) =
        failures.warnOnFailure("Could not unregister ${participant.name} from players.yml; membership record may be stale") {
            sync.unregister(participant)
        }

    private companion object {
        const val STATS_LOOKUP_COOLDOWN_NANOS = 3_000_000_000L
    }
}
