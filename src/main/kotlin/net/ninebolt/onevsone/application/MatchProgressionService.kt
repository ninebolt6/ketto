package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.application.port.SchedulerPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.SpawnSlot
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

// ArenaMatch is immutable: never capture it inside a deferred callback — re-read via registry.match(arenaId) and validate by epoch
class MatchProgressionService(
    private val registry: ArenaRegistry,
    private val signs: ArenaSignService,
    private val stats: PlayerStatsService,
    private val kit: KitPort,
    private val players: PlayerPort,
    private val scheduler: SchedulerPort,
    private val presentation: PresentationPort,
    private val recovery: PlayerRecoveryService,
    private val logger: Logger,
) {
    private val timers = mutableMapOf<Arena.Id, Cancellation>()

    fun requestRespawn(playerId: Uuid) {
        scheduler.schedule(0) {
            players.handle(playerId)?.takeIf { it.dead }?.respawn()
        }
    }

    fun abort(arenaId: Arena.Id) {
        cancelCountdown(arenaId)
        val step = registry.transact(arenaId) { it.abort() } ?: return
        val left = step.outcome
        val pendingRefs = left.map { it to recovery.pending(it.id) }
        pendingRefs.forEach { (participant, ref) ->
            runNowOrAfterRespawn(participant.id, ref) { h ->
                ref?.let { recovery.restoreNow(h, it) }
            }
        }
        signs.refreshSign(step.match)
    }

    internal fun cancelCountdown(arenaId: Arena.Id) {
        timers.remove(arenaId)?.cancel()
    }

    internal fun endRound(match: ArenaMatch, outcome: DefeatOutcome.RoundWon, death: Boolean) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)
        val gen = match.epoch
        try {
            val winnerHandle = players.handle(outcome.winner.id)
            if (winnerHandle != null) {
                rearm(arenaId, outcome.winner, winnerHandle)
            }

            players.handle(outcome.loser.id)?.let { presentation.roundEndSound(it.position()) }

            val ids = match.participants.map { it.id }
            presentation.roundWon(ids, outcome.round, outcome.winner.name)
            presentation.updateScoreboard(match)

            val loserHandle = players.handle(outcome.loser.id)
            if (death) {
                scheduleDeferred(outcome.loser.id, {
                    registry.match(arenaId)?.epoch == gen
                }) { h ->
                    rearm(arenaId, outcome.loser, h)
                    registry.match(arenaId)?.slotOf(outcome.loser.id)?.let { teleportToSlot(arenaId, it, h) }
                }
            } else if (loserHandle != null) {
                rearm(arenaId, outcome.loser, loserHandle)
                match.slotOf(outcome.loser.id)?.let { teleportToSlot(arenaId, it, loserHandle) }
            }
            winnerHandle?.let { h -> match.slotOf(outcome.winner.id)?.let { teleportToSlot(arenaId, it, h) } }

            signs.refreshSign(match)
            startRoundCountdown(arenaId, gen)
        } catch (e: Exception) {
            // A failure after the resolution commit would leave a timer-less ROUNDCOUNTDOWN stuck, so abort
            logger.log(Level.SEVERE, "Could not finish round ${outcome.round} in arena ${arenaId.name}; match aborted", e)
            abort(arenaId)
        }
    }

    internal fun finishMatch(
        match: ArenaMatch,
        winner: Participant,
        loser: Participant,
        end: MatchEnd,
    ) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)

        // Backup refs must be secured first: the committing transition has already unregistered both participants
        val winnerRef = recovery.pending(winner.id)
        val loserRef = recovery.pending(loser.id)
        if (winnerRef == null || loserRef == null) {
            logger.severe("Match in arena ${arenaId.name} ended without a pending backup; retained rows restore on next login")
        }

        presentation.champion(arenaId, winner.name)

        runNowOrAfterRespawn(winner.id, winnerRef) { h ->
            resetAndRestore(h, winnerRef)
            if (end != MatchEnd.FORFEITED) presentation.championFirework(winner.id)
        }

        if (end == MatchEnd.KILLED) {
            runAfterRespawn(loser.id, loserRef) { h ->
                resetAndRestore(h, loserRef)
            }
        } else {
            // Losers who died via quit arrive here dead, so do not defer on a dead check
            players.handle(loser.id)?.let { h ->
                if (end == MatchEnd.FORFEITED) {
                    loserRef?.let { recovery.restoreNow(h, it) }
                } else {
                    resetAndRestore(h, loserRef)
                }
            }
        }

        signs.refreshSign(match)
        recordResult(winner, loser)
    }

    // Stats failures are reported per participant so one failure never blocks the other's record
    private fun recordResult(winner: Participant, loser: Participant) {
        listOf(winner to true, loser to false).forEach { (participant, win) ->
            try {
                if (win) stats.recordWin(participant.id) else stats.recordLoss(participant.id)
            } catch (e: PersistenceException) {
                logger.log(
                    Level.SEVERE,
                    "Failed to record ${if (win) "win" else "loss"} for ${participant.name} (${participant.id}); " +
                        "arena cleanup completed, statistics require manual recovery",
                    e,
                )
            }
        }
    }

    internal fun startInitialCountdown(arenaId: Arena.Id, gen: Long) {
        runCountdown(arenaId, gen, ticks = 5) {
            if (remaining > 0) {
                presentation.countdownTick(participantIds, remaining)
                return@runCountdown false
            }
            if (p1.dead || p2.dead) return@runCountdown false
            try {
                recovery.backupBeforeMatch(match.participants)
            } catch (e: PersistenceException) {
                logger.log(Level.SEVERE, "Could not save inventories before starting arena ${arenaId.name}; match aborted", e)
                abort(arenaId)
                return@runCountdown true
            }
            try {
                rearm(arenaId, first, p1)
                rearm(arenaId, second, p2)
                teleportToSlot(arenaId, SpawnSlot.FIRST, p1)
                teleportToSlot(arenaId, SpawnSlot.SECOND, p2)
                presentation.matchStart(participantIds)
                val began = registry.transact(arenaId) { it.beginMatch() }
                    ?: run {
                        logger.severe("Arena ${arenaId.name} vanished before the match could begin; aborting")
                        abort(arenaId)
                        return@runCountdown true
                    }
                presentation.updateScoreboard(began.match)
                signs.refreshSign(began.match)
            } catch (e: Exception) {
                // Mid-swap failure: abort restores the players from the backups already taken
                logger.log(Level.SEVERE, "Could not apply equipment before starting arena ${arenaId.name}; match aborted", e)
                abort(arenaId)
            }
            true
        }
    }

    private fun startRoundCountdown(arenaId: Arena.Id, gen: Long) {
        runCountdown(arenaId, gen, ticks = 7) {
            when (remaining) {
                7 -> {
                    rearm(arenaId, first, p1)
                    rearm(arenaId, second, p2)
                }

                in 1..5 -> presentation.roundCountdownTick(participantIds, remaining)

                0 -> {
                    presentation.roundStart(participantIds)
                    registry.transact(arenaId) { it.resumeRound() }
                        ?.let { signs.refreshSign(it.match) }
                        ?: logger.severe("Arena ${arenaId.name} vanished while resuming a round")
                }
            }
            remaining == 0
        }
    }

    private fun runCountdown(
        arenaId: Arena.Id,
        gen: Long,
        ticks: Int,
        onTick: CountdownTick.() -> Boolean,
    ) {
        timers[arenaId] = scheduler.repeat(10, 20) { task, iteration ->
            val match = registry.match(arenaId)
            val paired = match?.state as? ArenaState.Paired
            if (match == null || match.epoch != gen || paired == null) {
                task.cancel()
                return@repeat
            }
            val (first, second) = paired.pair
            val p1 = players.handle(first.id)
            val p2 = players.handle(second.id)
            if (p1 == null || p2 == null) {
                task.cancel()
                abort(arenaId)
                return@repeat
            }
            if (CountdownTick(match, first, second, p1, p2, ticks - iteration).onTick()) task.cancel()
        }
    }

    private class CountdownTick(
        val match: ArenaMatch,
        val first: Participant,
        val second: Participant,
        val p1: PlayerHandle,
        val p2: PlayerHandle,
        val remaining: Int,
    ) {
        val participantIds: List<Uuid> get() = match.participants.map { it.id }
    }

    private fun rearm(arenaId: Arena.Id, participant: Participant, handle: PlayerHandle) {
        handle.prepareForMatch()
        kit.applyKit(arenaId, participant.id)
    }

    private fun resetAndRestore(handle: PlayerHandle, ref: BackupRef?) {
        handle.resetVitals()
        recovery.restoreToLobby(handle, ref)
    }

    // A dead player cannot be acted on until it respawns, so dead handles defer to next tick
    private fun runNowOrAfterRespawn(
        playerId: Uuid,
        ref: BackupRef?,
        action: (PlayerHandle) -> Unit,
    ) {
        val handle = players.handle(playerId) ?: return
        if (!handle.dead) {
            action(handle)
            return
        }
        if (ref != null) {
            scheduleWithRef(playerId, ref, action)
        } else {
            scheduleDeferred(playerId, { true }, action)
        }
    }

    private fun scheduleDeferred(playerId: Uuid, valid: () -> Boolean, action: (PlayerHandle) -> Unit) {
        scheduler.schedule(0) {
            if (!valid()) return@schedule
            val h = players.handle(playerId)?.takeIf { it.online } ?: return@schedule
            if (h.dead) h.respawn()
            action(h)
        }
    }

    // A killed participant must wait for the respawn tick even if the handle still reports alive
    private fun runAfterRespawn(playerId: Uuid, ref: BackupRef?, action: (PlayerHandle) -> Unit) {
        if (ref != null) scheduleWithRef(playerId, ref, action) else scheduleDeferred(playerId, { true }, action)
    }

    // A deferred restore is valid only while the exact same backup is still pending
    private fun scheduleWithRef(
        playerId: Uuid,
        ref: BackupRef,
        action: (PlayerHandle) -> Unit,
    ) = scheduleDeferred(playerId, { recovery.pending(playerId) == ref }, action)

    private fun teleportToSlot(arenaId: Arena.Id, slot: SpawnSlot, handle: PlayerHandle) {
        val spawn = registry.enabledArena(arenaId)?.spawn(slot)
        if (spawn == null) {
            logger.warning("Arena ${arenaId.name} is not enabled during an active match; skipping teleport")
            return
        }
        handle.teleport(spawn)
    }
}

internal enum class MatchEnd {
    KILLED,

    FELL,

    FORFEITED,
}
