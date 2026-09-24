package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.application.port.SchedulerPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.SpawnSlot
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

// ArenaMatch is immutable: never capture it inside a deferred callback — re-read via registry.match(arenaId) and validate by epoch
class MatchProgressionService(
    private val registry: ArenaRegistry,
    private val sync: MatchStateSync,
    private val stats: PlayerStatsRepository,
    private val kit: KitPort,
    private val players: PlayerPort,
    private val scheduler: SchedulerPort,
    private val presentation: PresentationPort,
    private val recovery: PlayerRecoveryService,
    private val logger: Logger
) {
    private val timers = mutableMapOf<Arena.Id, Cancellation>()

    fun requestRespawn(playerId: Uuid) {
        scheduler.schedule(0) {
            players.handle(playerId)?.takeIf { it.dead }?.respawn()
        }
    }

    fun abort(arenaId: Arena.Id) {
        cancelCountdown(arenaId)
        val step = registry.transact(arenaId, persist = sync::persistMatch) { it.abort() } ?: return
        val left = step.outcome
        val tickets = left.map { it to recovery.pending(it.id) }
        tickets.forEach { (participant, ticket) ->
            runNowOrAfterRespawn(participant.id, ticket) { h ->
                ticket?.let { recovery.restoreNow(h, it) }
            }
        }
        sync.refreshSign(step.match)
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

            players.handle(outcome.loser.id)?.position()?.let { presentation.roundEndSound(it) }

            val ids = match.participants.map { it.id }
            presentation.roundWon(ids, outcome.round, outcome.winner.name)
            presentation.updateScoreboard(match)

            val loserHandle = players.handle(outcome.loser.id)
            val release = {
                // The resolution marker is memory-only coordination metadata, so nothing is persisted
                registry.updateMatch(arenaId, persist = {}) { it.releaseResolution(gen) }
            }
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

            sync.refreshSign(match)
            startRoundCountdown(arenaId)
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
        forfeit: Boolean,
        death: Boolean
    ) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)
        val gen = match.epoch

        // Tickets must be secured first: the committing transition has already unregistered both participants
        val winnerTicket = recovery.pending(winner.id)
        val loserTicket = recovery.pending(loser.id)

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
            scheduleDeferred(
                loser.id,
                loserTicket,
                valid = { registry.match(arenaId)?.epoch == gen && registry.arenaOf(loser.id) == null }
            ) { h ->
                resetAndRestore(h, loserTicket)
            }
        } else {
            // Losers who died via quit arrive here dead, so do not defer on a dead check
            players.handle(loser.id)?.let { h ->
                if (forfeit) {
                    loserTicket?.let { recovery.restoreNow(h, it) }
                } else {
                    resetAndRestore(h, loserTicket)
                }
            }
        }

        sync.refreshSign(match)
        recordResult(winner, loser)
    }

    // Stats failures are reported per participant so one failure never blocks the other's record
    private fun recordResult(winner: Participant, loser: Participant) {
        listOf(winner to true, loser to false).forEach { (participant, win) ->
            try {
                if (win) stats.recordWin(participant.id) else stats.recordLoss(participant.id)
            } catch (e: IllegalStateException) {
                logger.log(
                    Level.SEVERE,
                    "Failed to record ${if (win) "win" else "loss"} for ${participant.name} (${participant.id}); " +
                        "arena cleanup completed, statistics require manual recovery",
                    e
                )
            }
        }
    }

    internal fun startInitialCountdown(arenaId: Arena.Id) {
        runCountdown(arenaId, ticks = 5, stillCounting = { it.canBeginMatch }) {
            if (remaining > 0) {
                presentation.countdownTick(participantIds, remaining)
                return@runCountdown false
            }
            if (p1.dead || p2.dead) return@runCountdown false
            try {
                recovery.backupBeforeMatch(match.participants)
            } catch (e: PersistenceFailure) {
                logger.log(Level.SEVERE, "Could not save inventories before starting arena ${arenaId.name}; match aborted", e)
                abort(arenaId)
                return@runCountdown true
            }
            try {
                rearm(arenaId, first, p1)
                rearm(arenaId, second, p2)
                teleportToSlot(match, first, p1)
                teleportToSlot(match, second, p2)
                presentation.matchStart(participantIds)
                val began = registry.transact(arenaId, persist = sync::persistMatch) { it.beginMatch() }
                if (began?.outcome == true) {
                    presentation.updateScoreboard(began.match)
                    sync.refreshSign(began.match)
                }
            } catch (e: Exception) {
                // Mid-swap failure: abort restores the players from the backups already taken
                logger.log(Level.SEVERE, "Could not apply equipment before starting arena ${arenaId.name}; match aborted", e)
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
                    val resumed = registry.transact(arenaId, persist = sync::persistMatch) { it.resumeRound() }
                    if (resumed?.outcome == true) {
                        sync.refreshSign(resumed.match)
                    }
                }
            }
            remaining == 0
        }
    }

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
            val first = match.participantAt(SpawnSlot.FIRST)
            val second = match.participantAt(SpawnSlot.SECOND)
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

    private fun rearm(arenaId: Arena.Id, participant: Participant, handle: PlayerHandle) {
        handle.prepareForMatch()
        kit.applyKit(arenaId, participant.id)
    }

    private fun resetAndRestore(handle: PlayerHandle, ticket: PlayerRecoveryService.RestoreTicket?) {
        handle.resetVitals()
        recovery.restoreToLobby(handle, ticket)
    }

    // A dead player cannot be acted on until it respawns, so dead handles defer to next tick
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

    private fun scheduleDeferred(playerId: Uuid, valid: () -> Boolean, action: (PlayerHandle) -> Unit) {
        scheduler.schedule(0) {
            if (!valid()) return@schedule
            val h = players.handle(playerId)?.takeIf { it.online } ?: return@schedule
            if (h.dead) h.respawn()
            action(h)
        }
    }

    // A deferred restore is valid only while the exact same ticket is still pending
    private fun scheduleTicketed(
        playerId: Uuid,
        ticket: PlayerRecoveryService.RestoreTicket,
        action: (PlayerHandle) -> Unit
    ) = scheduleDeferred(playerId, { recovery.pending(playerId) === ticket }, action)

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
            logger.warning("Arena ${match.arenaId.name} spawn ${slot.number} is not set; skipping teleport")
            return
        }
        handle.teleport(spawn)
    }
}
