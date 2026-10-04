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
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.SlottedParticipant
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

// ArenaMatch is immutable: never capture it inside a deferred callback — re-read via sessions.match(arenaId) and validate by epoch
class MatchProgressionService(
    private val sessions: ArenaSessions,
    private val signService: ArenaSignService,
    private val statsService: PlayerStatsService,
    private val kitPort: KitPort,
    private val playerPort: PlayerPort,
    private val schedulerPort: SchedulerPort,
    private val presentationPort: PresentationPort,
    private val recovery: InventoryRecoveryService,
    private val logger: Logger,
) {
    private val timers = mutableMapOf<Arena.Id, Cancellation>()

    fun requestRespawn(playerId: Uuid) {
        schedulerPort.schedule(0) {
            playerPort.handle(playerId)?.takeIf { it.dead }?.respawn()
        }
    }

    fun abort(arenaId: Arena.Id) {
        cancelCountdown(arenaId)
        val step = sessions.transact(arenaId) { it.abort() } ?: return
        val left = step.outcome
        val pendingRefs = left.map { it to recovery.pending(it.id) }
        pendingRefs.forEach { (participant, ref) ->
            runNowOrAfterRespawn(participant.id, ref) { h ->
                ref?.let { recovery.restoreNow(h, it) }
            }
        }
        signService.refreshSign(step.match)
    }

    internal fun cancelCountdown(arenaId: Arena.Id) {
        timers.remove(arenaId)?.cancel()
    }

    internal fun endRound(match: ArenaMatch, outcome: DefeatOutcome.RoundWon, death: Boolean) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)
        val epoch = match.epoch
        try {
            val winnerHandle = playerPort.handle(outcome.winner.id)
            if (winnerHandle != null) {
                rearm(arenaId, outcome.winner, winnerHandle)
            }

            playerPort.handle(outcome.loser.id)?.let { presentationPort.roundEndSound(it.position()) }

            val ids = match.participants.map { it.id }
            presentationPort.roundWon(ids, outcome.round, outcome.winner.name)
            presentationPort.updateScoreboard(match)

            val loserHandle = playerPort.handle(outcome.loser.id)
            if (death) {
                scheduleDeferred(outcome.loser.id, {
                    sessions.match(arenaId)?.epoch == epoch
                }) { h ->
                    rearm(arenaId, outcome.loser, h)
                    teleportToSlot(arenaId, outcome.loser, h)
                }
            } else if (loserHandle != null) {
                rearm(arenaId, outcome.loser, loserHandle)
                teleportToSlot(arenaId, outcome.loser, loserHandle)
            }
            winnerHandle?.let { h -> teleportToSlot(arenaId, outcome.winner, h) }

            signService.refreshSign(match)
            startRoundCountdown(arenaId, epoch)
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
        cause: DefeatCause,
    ) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)

        // Backup refs must be secured first: the committing transition has already unregistered both participants
        val winnerRef = recovery.pending(winner.id)
        val loserRef = recovery.pending(loser.id)
        if (winnerRef == null || loserRef == null) {
            logger.severe("Match in arena ${arenaId.name} ended without a pending backup; retained rows restore on next login")
        }

        presentationPort.champion(arenaId, winner.name)

        runNowOrAfterRespawn(winner.id, winnerRef) { h ->
            resetAndRestore(h, winnerRef)
            if (cause != DefeatCause.FORFEIT) presentationPort.championFirework(winner.id)
        }

        if (cause == DefeatCause.DEATH) {
            runAfterRespawn(loser.id, loserRef) { h ->
                resetAndRestore(h, loserRef)
            }
        } else {
            // Losers who died via quit arrive here dead, so do not defer on a dead check
            playerPort.handle(loser.id)?.let { h ->
                if (cause == DefeatCause.FORFEIT) {
                    loserRef?.let { recovery.restoreNow(h, it) }
                } else {
                    resetAndRestore(h, loserRef)
                }
            }
        }

        signService.refreshSign(match)
        recordResult(winner, loser)
    }

    // Stats failures are reported per participant so one failure never blocks the other's record
    private fun recordResult(winner: Participant, loser: Participant) {
        listOf(winner to true, loser to false).forEach { (participant, win) ->
            try {
                if (win) statsService.recordWin(participant.id) else statsService.recordLoss(participant.id)
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

    internal fun startInitialCountdown(arenaId: Arena.Id, epoch: Long) {
        runCountdown(arenaId, epoch, ticks = 5) {
            if (remaining > 0) {
                presentationPort.countdownTick(participantIds, remaining)
                return@runCountdown false
            }
            if (online.any { (_, h) -> h.dead }) return@runCountdown false
            try {
                recovery.backupBeforeMatch(match.participants)
            } catch (e: PersistenceException) {
                logger.log(Level.SEVERE, "Could not save inventories before starting arena ${arenaId.name}; match aborted", e)
                abort(arenaId)
                return@runCountdown true
            }
            try {
                online.forEach { (sp, h) -> rearm(arenaId, sp, h) }
                // Teleports fire events synchronously, so the match can move on mid-loop
                for ((sp, h) in online) {
                    if (sessions.match(arenaId)?.epoch != epoch) {
                        abort(arenaId)
                        return@runCountdown true
                    }
                    teleportToSlot(arenaId, sp, h)
                }
                // An arena removal aborts the match itself, so a missing match means cleanup already ran
                val began = sessions.transact(arenaId) { it.beginMatch() } ?: return@runCountdown true
                if (!began.outcome) {
                    abort(arenaId)
                    return@runCountdown true
                }
                presentationPort.matchStart(participantIds)
                presentationPort.updateScoreboard(began.match)
                signService.refreshSign(began.match)
            } catch (e: Exception) {
                // Mid-swap failure: abort restores the players from the backups already taken
                logger.log(Level.SEVERE, "Could not apply equipment before starting arena ${arenaId.name}; match aborted", e)
                abort(arenaId)
            }
            true
        }
    }

    private fun startRoundCountdown(arenaId: Arena.Id, epoch: Long) {
        runCountdown(arenaId, epoch, ticks = 7) {
            when (remaining) {
                7 -> online.forEach { (sp, h) -> rearm(arenaId, sp, h) }

                in 1..5 -> presentationPort.roundCountdownTick(participantIds, remaining)

                0 -> {
                    val resumed = sessions.transact(arenaId) { it.resumeRound() } ?: return@runCountdown true
                    if (resumed.outcome) {
                        presentationPort.roundStart(participantIds)
                        signService.refreshSign(resumed.match)
                    }
                }
            }
            remaining == 0
        }
    }

    private fun runCountdown(
        arenaId: Arena.Id,
        epoch: Long,
        ticks: Int,
        onTick: CountdownTick.() -> Boolean,
    ) {
        timers[arenaId] = schedulerPort.repeat(10, 20) { task, iteration ->
            val match = sessions.match(arenaId)
            val paired = match?.paired
            if (match == null || match.epoch != epoch || paired == null) {
                task.cancel()
                return@repeat
            }
            val slotted = paired.slotted.toList()
            val online = slotted.mapNotNull { sp -> playerPort.handle(sp.id)?.let { sp to it } }
            if (online.size != slotted.size) {
                task.cancel()
                abort(arenaId)
                return@repeat
            }
            if (CountdownTick(match, online, ticks - iteration).onTick()) task.cancel()
        }
    }

    private class CountdownTick(
        val match: ArenaMatch,
        val online: List<Pair<SlottedParticipant, PlayerHandle>>,
        val remaining: Int,
    ) {
        val participantIds: List<Uuid> get() = match.participants.map { it.id }
    }

    private fun rearm(arenaId: Arena.Id, participant: SlottedParticipant, handle: PlayerHandle) {
        handle.prepareForMatch()
        kitPort.applyKit(arenaId, participant.id)
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
        val handle = playerPort.handle(playerId) ?: return
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
        schedulerPort.schedule(0) {
            if (!valid()) return@schedule
            val h = playerPort.handle(playerId)?.takeIf { it.online } ?: return@schedule
            if (h.dead) {
                h.respawn()
                if (!valid()) return@schedule
            }
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

    private fun teleportToSlot(arenaId: Arena.Id, participant: SlottedParticipant, handle: PlayerHandle) {
        val spawn = sessions.enabledArena(arenaId)?.spawn(participant.slot)
        if (spawn == null) {
            logger.warning("Arena ${arenaId.name} is not enabled during an active match; skipping teleport")
            return
        }
        handle.teleport(spawn)
    }
}
