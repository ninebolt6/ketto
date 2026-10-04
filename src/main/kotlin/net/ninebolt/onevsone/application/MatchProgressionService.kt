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
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.SlottedParticipant
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

// ArenaMatch is immutable: never capture it inside a deferred callback — re-read via sessions.findMatch(arenaId) and validate by epoch
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
            playerPort.findHandle(playerId)?.takeIf { it.dead }?.respawn()
        }
    }

    fun abort(arenaId: Arena.Id) {
        cancelCountdown(arenaId)
        val wasActive = sessions.findMatch(arenaId)?.state is ArenaState.Active
        val step = sessions.transact(arenaId) { it.abort() } ?: return
        val left = step.outcome
        val pendingRefs = left.map { it to pendingOrNull(it.id) }
        pendingRefs.forEach { (participant, ref) ->
            runNowOrAfterRespawn(participant.id, ref) { h ->
                if (wasActive) {
                    release(h, ref, toLobby = false)
                } else {
                    ref?.let { recovery.restoreNow(h, it) }
                }
            }
        }
        stepSafely("refresh the sign of arena ${arenaId.name}") { signService.refreshSign(step.match) }
    }

    internal fun cancelCountdown(arenaId: Arena.Id) {
        timers.remove(arenaId)?.cancel()
    }

    internal fun endRound(match: ArenaMatch, outcome: DefeatOutcome.RoundWon, death: Boolean) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)
        val epoch = match.epoch
        try {
            val winnerHandle = playerPort.findHandle(outcome.winner.id)
            if (winnerHandle != null) {
                rearm(arenaId, outcome.winner, winnerHandle)
            }

            playerPort.findHandle(outcome.loser.id)?.let { presentationPort.roundEndSound(it.position()) }

            val ids = match.participants.map { it.id }
            presentationPort.roundWon(ids, outcome.round, outcome.winner.name)
            presentationPort.updateScoreboard(match)

            val loserHandle = playerPort.findHandle(outcome.loser.id)
            if (death) {
                scheduleDeferred(outcome.loser.id, {
                    sessions.findMatch(arenaId)?.epoch == epoch
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
        val winnerRef = pendingOrNull(winner.id)
        val loserRef = pendingOrNull(loser.id)
        if (winnerRef == null || loserRef == null) {
            logger.severe("Match in arena ${arenaId.name} ended without a pending backup; retained rows restore on next login")
        }

        stepSafely("present the champion of arena ${arenaId.name}") {
            presentationPort.champion(arenaId, winner.name)
        }

        stepSafely("release the winner of arena ${arenaId.name}") {
            runNowOrAfterRespawn(winner.id, winnerRef) { h ->
                release(h, winnerRef, toLobby = true)
                if (cause != DefeatCause.FORFEIT) presentationPort.championFirework(winner.id)
            }
        }

        stepSafely("release the loser of arena ${arenaId.name}") {
            if (cause == DefeatCause.DEATH) {
                runAfterRespawn(loser.id, loserRef) { h ->
                    release(h, loserRef, toLobby = true)
                }
            } else {
                // Losers who died via quit arrive here dead, so do not defer on a dead check
                playerPort.findHandle(loser.id)?.let { h ->
                    release(h, loserRef, toLobby = cause != DefeatCause.FORFEIT)
                }
            }
        }

        stepSafely("record the result of arena ${arenaId.name}") { recordResult(winner, loser) }
        stepSafely("refresh the sign of arena ${arenaId.name}") { signService.refreshSign(match) }
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
                    if (sessions.findMatch(arenaId)?.epoch != epoch) {
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
            val match = sessions.findMatch(arenaId)
            val paired = match?.paired
            if (match == null || match.epoch != epoch || paired == null) {
                task.cancel()
                return@repeat
            }
            val slotted = paired.slotted.toList()
            val online = slotted.mapNotNull { sp -> playerPort.findHandle(sp.id)?.let { sp to it } }
            if (online.size != slotted.size) {
                task.cancel()
                abort(arenaId)
                return@repeat
            }
            try {
                if (CountdownTick(match, online, ticks - iteration).onTick()) task.cancel()
            } catch (e: Exception) {
                // A failing tick would leave the countdown running with no way to finish it
                logger.log(Level.SEVERE, "Countdown tick failed in arena ${arenaId.name}; match aborted", e)
                task.cancel()
                abort(arenaId)
            }
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

    private fun release(handle: PlayerHandle, ref: BackupRef?, toLobby: Boolean) {
        if (toLobby) handle.resetVitals()
        val restored = ref != null && restoreQuietly(handle, ref)
        if (!restored) {
            stripQuietly(handle)
            presentationPort.clearScoreboard(handle.id)
            logger.severe("Could not restore the inventory for ${handle.name} (${handle.id}); match items removed, any retained backup restores on next login")
        }
        if (toLobby) recovery.teleportLobby(handle)
    }

    private fun restoreQuietly(handle: PlayerHandle, ref: BackupRef): Boolean = try {
        recovery.restoreNow(handle, ref)
    } catch (e: PersistenceException) {
        logger.log(Level.SEVERE, "Could not restore the inventory for ${handle.name} (${handle.id}); backup retained", e)
        false
    }

    private fun stripQuietly(handle: PlayerHandle) {
        try {
            kitPort.stripKit(handle.id)
        } catch (e: PersistenceException) {
            logger.log(Level.SEVERE, "Could not remove match items for ${handle.name} (${handle.id})", e)
        }
    }

    private fun pendingOrNull(playerId: Uuid): BackupRef? = try {
        recovery.findPending(playerId)
    } catch (e: PersistenceException) {
        logger.log(Level.SEVERE, "Could not read the pending backup for $playerId; treating it as missing", e)
        null
    }

    private fun stepSafely(description: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Could not $description; continuing cleanup", e)
        }
    }

    // A dead player cannot be acted on until it respawns, so dead handles defer to next tick
    private fun runNowOrAfterRespawn(
        playerId: Uuid,
        ref: BackupRef?,
        action: (PlayerHandle) -> Unit,
    ) {
        val handle = playerPort.findHandle(playerId) ?: return
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
            val h = playerPort.findHandle(playerId)?.takeIf { it.online } ?: return@schedule
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
    ) = scheduleDeferred(playerId, { recovery.findPending(playerId) == ref }, action)

    private fun teleportToSlot(arenaId: Arena.Id, participant: SlottedParticipant, handle: PlayerHandle) {
        val spawn = sessions.findEnabledArena(arenaId)?.spawn(participant.slot)
        if (spawn == null) {
            logger.warning("Arena ${arenaId.name} is not enabled during an active match; skipping teleport")
            return
        }
        handle.teleport(spawn)
    }
}
