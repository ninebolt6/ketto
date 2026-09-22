package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.application.port.SchedulerPort
import net.ninebolt.onevsone.application.port.warnOnFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import kotlin.uuid.Uuid

/**
 * Progression engine for the initial countdown, round transitions, resolution,
 * and aborts. As the delegate of ArenaApplicationService it owns a per-arena
 * timer; deferred callbacks are validated by generation (ArenaMatch.epoch)
 * match and liveness checks.
 *
 * ArenaMatch is immutable: never capture a reference inside a deferred
 * callback — re-read the latest state via registry.match(arenaId).
 * All operations are assumed to be serialized on the main thread.
 */
class MatchProgressionService(
    private val registry: ArenaRegistry,
    private val sync: MatchStateSync,
    private val stats: PlayerStatsRepository,
    private val backups: InventoryBackupPort,
    private val kit: KitPort,
    private val players: PlayerPort,
    private val scheduler: SchedulerPort,
    private val presentation: MatchPresentationPort,
    private val recovery: PlayerRecoveryService,
    private val failures: FailureReporter
) {
    private val timers = mutableMapOf<Arena.Id, Cancellation>()

    /** Respawn reservation for a player who died but was not accepted as a defeat. */
    fun requestRespawn(playerId: Uuid) {
        scheduler.schedule(0) {
            players.handle(playerId)?.takeIf { it.dead }?.respawn()
        }
    }

    // ---- Abort ---------------------------------------------------------------

    fun abort(arenaId: Arena.Id) {
        cancelCountdown(arenaId)
        val step = registry.transact(arenaId) { it.abort() } ?: return
        val left = step.outcome
        val tickets = left.map { it to recovery.pending(it.id) }
        left.forEach { unregisterKeepingRestore(it) }
        tickets.forEach { (participant, ticket) ->
            runNowOrAfterRespawn(participant.id, ticket) { h ->
                recovery.restoreNow(h, ticket, respawn = false, lobby = false)
            }
        }
        sync.publish(step.match)
    }

    /** Stops only the running countdown (for shutdown). */
    internal fun cancelCountdown(arenaId: Arena.Id) {
        timers.remove(arenaId)?.cancel()
    }

    // ---- Rounds & finish -------------------------------------------------------

    internal fun endRound(match: ArenaMatch, outcome: DefeatOutcome.RoundWon, death: Boolean) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)
        val gen = match.epoch
        try {
            val winnerHandle = players.handle(outcome.winner.id)
            if (winnerHandle != null) {
                rearm(arenaId, outcome.winner, winnerHandle)
            }

            players.handle(outcome.loser.id)?.position()?.let { presentation.roundEndSound(it) }

            val ids = match.participants.map { it.id }
            presentation.roundWon(ids, outcome.round, outcome.winner.name)
            presentation.updateScoreboard(match)

            val loserHandle = players.handle(outcome.loser.id)
            val release = { registry.updateMatch(arenaId) { it.releaseResolution(gen) } }
            if (death) {
                scheduleDeferred(outcome.loser.id, {
                    registry.match(arenaId)?.epoch == gen && registry.arenaOf(outcome.loser.id) == arenaId
                }) { h ->
                    rearm(arenaId, outcome.loser, h)
                    registry.match(arenaId)?.let { teleportToSlot(it, outcome.loser, h) }
                    release()
                }
            } else if (loserHandle != null) {
                rearm(arenaId, outcome.loser, loserHandle)
                teleportToSlot(match, outcome.loser, loserHandle)
                scheduler.schedule(0) { release() }
            } else {
                release()
            }
            winnerHandle?.let { teleportToSlot(match, outcome.winner, it) }

            sync.publish(match)
            startRoundCountdown(arenaId)
        } catch (e: Exception) {
            // A failure after the resolution commit would leave a timer-less ROUNDCOUNTDOWN stuck, so abort
            failures.report("Could not finish round ${outcome.round} in arena ${arenaId.name}; match aborted", e)
            abort(arenaId)
        }
    }

    internal fun finishMatch(
        match: ArenaMatch,
        winner: Participant,
        loser: Participant,
        forfeit: Boolean,
        death: Boolean
    ) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)
        val gen = match.epoch

        // Secure the restore targets first, then unregister and stop tasks
        val winnerTicket = recovery.pending(winner.id)
        val loserTicket = recovery.pending(loser.id)
        listOf(winner, loser).forEach { unregisterKeepingRestore(it) }

        presentation.champion(arenaId, winner.name)

        runNowOrAfterRespawn(
            winner.id,
            winnerTicket,
            valid = { registry.match(arenaId)?.epoch == gen && registry.arenaOf(winner.id) == null }
        ) { h ->
            resetAndRestore(h, winnerTicket)
            if (!forfeit) presentation.championFirework(winner.id)
        }

        if (death) {
            runNowOrAfterRespawn(
                loser.id,
                loserTicket,
                valid = { registry.match(arenaId)?.epoch == gen && registry.arenaOf(loser.id) == null }
            ) { h ->
                resetAndRestore(h, loserTicket)
            }
        } else {
            // A non-dead loser is processed immediately. Players who died via quit are included here, so do not defer on a dead check
            players.handle(loser.id)?.let { h ->
                if (forfeit) {
                    recovery.restoreNow(h, loserTicket, respawn = false, lobby = false)
                } else {
                    resetAndRestore(h, loserTicket)
                }
            }
        }

        sync.publish(match)
        recordResult(winner, loser)
    }

    /** Stats-update failures are reported independently for winner/loser so neither restore nor the other's record is blocked. */
    private fun recordResult(winner: Participant, loser: Participant) {
        listOf(winner to true, loser to false).forEach { (participant, win) ->
            try {
                if (win) stats.recordWin(participant.id) else stats.recordLoss(participant.id)
            } catch (e: IllegalStateException) {
                failures.report(
                    "Failed to record ${if (win) "win" else "loss"} for ${participant.name} (${participant.id}); " +
                        "arena cleanup completed, statistics require manual recovery",
                    e
                )
            }
        }
    }

    // ---- Countdown ---------------------------------------------------------

    internal fun startInitialCountdown(arenaId: Arena.Id) {
        runCountdown(arenaId, ticks = 5, stillCounting = { it.canBeginMatch }) {
            if (remaining > 0) {
                presentation.countdownTick(participantIds, remaining)
                return@runCountdown false
            }
            // Re-verify both players' connection and liveness just before starting (postponed while either is dead)
            if (p1.dead || p2.dead) return@runCountdown false
            // Bulk-save both players' inventories before swapping equipment
            val refs = try {
                backups.backupBeforeMatch(MatchId.new(), match.participants)
            } catch (e: PersistenceFailure) {
                failures.report("Could not save inventories before starting arena ${arenaId.name}; match aborted", e)
                abort(arenaId)
                return@runCountdown true
            }
            recovery.register(refs)
            try {
                rearm(arenaId, first, p1)
                rearm(arenaId, second, p2)
                teleportToSlot(match, first, p1)
                teleportToSlot(match, second, p2)
                presentation.matchStart(participantIds)
                val began = registry.transact(arenaId) { it.beginMatch() }
                if (began?.outcome == true) {
                    presentation.updateScoreboard(began.match)
                    sync.publish(began.match)
                }
            } catch (e: Exception) {
                // Failed mid-swap: abort and restore using the backups already taken
                failures.report("Could not apply equipment before starting arena ${arenaId.name}; match aborted", e)
                abort(arenaId)
            }
            true
        }
    }

    private fun startRoundCountdown(arenaId: Arena.Id) {
        runCountdown(arenaId, ticks = 7, stillCounting = { it.canResumeRound }) {
            when (remaining) {
                7 -> {
                    rearm(arenaId, first, p1)
                    rearm(arenaId, second, p2)
                }
                in 1..5 -> presentation.roundCountdownTick(participantIds, remaining)
                0 -> {
                    presentation.roundStart(participantIds)
                    val resumed = registry.transact(arenaId) { it.resumeRound() }
                    if (resumed?.outcome == true) {
                        sync.publish(resumed.match)
                    }
                }
            }
            remaining == 0
        }
    }

    /**
     * Shared countdown skeleton. Re-reads the latest match each tick, verifies
     * the generation token and stillCounting's progression condition, then
     * resolves both players' handles. If a handle is lost (disconnect), the
     * task ends and aborts. Ends on the tick where onTick returns true.
     * remaining counts down from ticks and is still called at 0 or below.
     */
    private fun runCountdown(
        arenaId: Arena.Id,
        ticks: Int,
        stillCounting: (ArenaMatch) -> Boolean,
        onTick: CountdownTick.() -> Boolean
    ) {
        val gen = registry.match(arenaId)?.epoch ?: return
        var remaining = ticks
        timers[arenaId] = scheduler.repeat(10, 20) { task ->
            val match = registry.match(arenaId)
            if (match == null || match.epoch != gen || !stillCounting(match)) {
                task.cancel()
                return@repeat
            }
            val first = match.participantAt(0)
            val second = match.participantAt(1)
            if (first == null || second == null) {
                task.cancel()
                return@repeat
            }
            val p1 = players.handle(first.id)
            val p2 = players.handle(second.id)
            if (p1 == null || p2 == null) {
                task.cancel()
                abort(arenaId)
                return@repeat
            }
            if (CountdownTick(match, first, second, p1, p2, remaining).onTick()) task.cancel()
            remaining--
        }
    }

    /** Context handed to each runCountdown tick, with match and both handles already resolved. */
    private class CountdownTick(
        val match: ArenaMatch,
        val first: Participant,
        val second: Participant,
        val p1: PlayerHandle,
        val p2: PlayerHandle,
        val remaining: Int
    ) {
        val participantIds: List<Uuid> get() = match.participants.map { it.id }
    }

    // ---- Shared -----------------------------------------------------------------

    /** Ledger-unregister failures are swallowed into a warn. The restore record stays in memory. */
    private fun unregisterKeepingRestore(participant: Participant) =
        failures.warnOnFailure("Could not unregister ${participant.name} from players.yml; pending restore retained in memory") {
            sync.unregister(participant)
        }

    /** Prepares health/flight for the match and applies the arena kit. */
    private fun rearm(arenaId: Arena.Id, participant: Participant, handle: PlayerHandle) {
        handle.prepareForMatch()
        kit.applyKit(arenaId, participant.id)
    }

    /** Restores health, restores the backup, and sends the player to the lobby. */
    private fun resetAndRestore(handle: PlayerHandle, ticket: PlayerRecoveryService.RestoreTicket?) {
        handle.resetVitals()
        recovery.restoreNow(handle, ticket, respawn = false, lobby = true)
    }

    /**
     * Dispatches action for a leaving/teardown participant: immediately if
     * alive, after next tick's respawn if dead. Does nothing when offline (no
     * handle). The deferred path's validity is ticket identity (preferred), or
     * valid when there is no ticket.
     */
    private fun runNowOrAfterRespawn(
        playerId: Uuid,
        ticket: PlayerRecoveryService.RestoreTicket?,
        valid: () -> Boolean = { true },
        action: (PlayerHandle) -> Unit
    ) {
        val handle = players.handle(playerId) ?: return
        if (!handle.dead) {
            action(handle)
            return
        }
        scheduleDeferred(playerId, ticket, valid, action)
    }

    /**
     * Runs follow-up processing for a dead player on the next tick.
     * The handle is resolved only if valid still holds at execution time;
     * respawn is invoked first if the player has not respawned yet, then
     * action runs. Does nothing if no handle is available (offline etc.).
     */
    private fun scheduleDeferred(playerId: Uuid, valid: () -> Boolean, action: (PlayerHandle) -> Unit) {
        scheduler.schedule(0) {
            if (!valid()) return@schedule
            val h = players.handle(playerId)?.takeIf { it.online } ?: return@schedule
            if (h.dead) h.respawn()
            action(h)
        }
    }

    /** scheduleDeferred whose validity is the identity of the pending restore ticket. */
    private fun scheduleTicketed(
        playerId: Uuid,
        ticket: PlayerRecoveryService.RestoreTicket,
        action: (PlayerHandle) -> Unit
    ) = scheduleDeferred(playerId, { recovery.pending(playerId) === ticket }, action)

    /** scheduleDeferred validating by ticket identity when present, otherwise by valid. */
    private fun scheduleDeferred(
        playerId: Uuid,
        ticket: PlayerRecoveryService.RestoreTicket?,
        valid: () -> Boolean,
        action: (PlayerHandle) -> Unit
    ) = if (ticket != null) {
        scheduleTicketed(playerId, ticket, action)
    } else {
        scheduleDeferred(playerId, valid, action)
    }

    private fun teleportToSlot(match: ArenaMatch, participant: Participant, handle: PlayerHandle) {
        val slot = match.slotOf(participant.id) ?: return
        val spawn = registry.arena(match.arenaId)?.spawn(slot)
        if (spawn == null) {
            failures.warn("Arena ${match.arenaId.name} spawn ${slot + 1} is not set; skipping teleport")
            return
        }
        handle.teleport(spawn)
    }
}
