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
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import kotlin.uuid.Uuid

/**
 * 開始カウントダウン・ラウンド遷移・決着・中断の進行機構。
 * ArenaApplicationService からの委譲先として、アリーナごとのタイマーを所有し、
 * 遅延コールバックは世代トークン(MatchToken)一致と生存確認で有効性を検証する。
 *
 * ArenaMatch は immutable: 遅延実行されるコールバック内では参照をキャプチャせず
 * registry.match(arenaId) で最新状態を再読みすること。
 * すべての操作はメインスレッドで直列化されている前提。
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
    private val timers = mutableMapOf<ArenaId, Cancellation>()

    /** 死亡したが敗北として受理されなかった場合のリスポーン予約。 */
    fun requestRespawn(playerId: Uuid) {
        scheduler.schedule(0) {
            players.handle(playerId)?.takeIf { it.dead }?.respawn()
        }
    }

    // ---- 中断 ---------------------------------------------------------------

    fun abort(arenaId: ArenaId) {
        cancelCountdown(arenaId)
        val step = registry.transact(arenaId) { it.abort() } ?: return
        val left = step.outcome
        val tickets = left.map { it to recovery.pending(it.id) }
        left.forEach { sync.unregisterKeepingRestore(it) }
        tickets.forEach { (participant, ticket) ->
            val handle = players.handle(participant.id) ?: return@forEach
            if (handle.dead) {
                scheduleDeferred(participant.id, ticket, { true }) { h ->
                    recovery.restoreNow(h, ticket, respawn = false, lobby = false)
                }
            } else {
                recovery.restoreNow(handle, ticket, respawn = false, lobby = false)
            }
        }
        sync.publish(step.match)
    }

    /** 走行中のカウントダウンだけを止める(shutdown 用)。 */
    internal fun cancelCountdown(arenaId: ArenaId) {
        timers.remove(arenaId)?.cancel()
    }

    // ---- ラウンド・終了 -------------------------------------------------------

    internal fun endRound(match: ArenaMatch, outcome: DefeatOutcome.RoundWon, death: Boolean) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)
        val gen = match.token

        val winnerHandle = players.handle(outcome.winner.id)
        if (winnerHandle != null) {
            winnerHandle.prepareForMatch()
            kit.applyKit(arenaId, outcome.winner.id)
        }

        players.handle(outcome.loser.id)?.position()?.let { presentation.roundEndSound(it) }

        val ids = match.participants.map { it.id }
        presentation.roundWon(ids, outcome.round, outcome.winner.name)
        presentation.updateScoreboard(match)

        val loserHandle = players.handle(outcome.loser.id)
        if (death) {
            scheduleDeferred(outcome.loser.id, null, {
                registry.match(arenaId)?.token == gen && registry.arenaOf(outcome.loser.id) == arenaId
            }) { h ->
                h.prepareForMatch()
                kit.applyKit(arenaId, outcome.loser.id)
                registry.match(arenaId)?.let { teleportToSlot(it, outcome.loser, h) }
                registry.updateMatch(arenaId) { it.releaseResolution(gen) }
            }
        } else if (loserHandle != null) {
            loserHandle.prepareForMatch()
            kit.applyKit(arenaId, outcome.loser.id)
            teleportToSlot(match, outcome.loser, loserHandle)
            scheduler.schedule(0) {
                registry.updateMatch(arenaId) { it.releaseResolution(gen) }
            }
        } else {
            registry.updateMatch(arenaId) { it.releaseResolution(gen) }
        }
        winnerHandle?.let { teleportToSlot(match, outcome.winner, it) }

        sync.publish(match)
        startRoundCountdown(arenaId)
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
        val gen = match.token

        // 復元対象を先に確保してから登録解除・タスク停止へ
        val winnerTicket = recovery.pending(winner.id)
        val loserTicket = recovery.pending(loser.id)
        listOf(winner, loser).forEach { sync.unregisterKeepingRestore(it) }

        presentation.champion(arenaId, winner.name)

        val winnerHandle = players.handle(winner.id)
        if (winnerHandle != null && !winnerHandle.dead) {
            winnerHandle.resetVitals()
            recovery.restoreNow(winnerHandle, winnerTicket, respawn = false, lobby = true)
            if (!forfeit) presentation.championFirework(winner.id)
        } else if (winnerHandle != null) {
            scheduleDeferred(winner.id, winnerTicket, {
                registry.match(arenaId)?.token == gen && registry.arenaOf(winner.id) == null
            }) { h ->
                h.resetVitals()
                recovery.restoreNow(h, winnerTicket, respawn = false, lobby = true)
                if (!forfeit) presentation.championFirework(winner.id)
            }
        }

        if (death) {
            scheduleDeferred(loser.id, loserTicket, {
                registry.match(arenaId)?.token == gen && registry.arenaOf(loser.id) == null
            }) { h ->
                h.resetVitals()
                recovery.restoreNow(h, loserTicket, respawn = false, lobby = true)
            }
        } else {
            val loserHandle = players.handle(loser.id)
            if (loserHandle != null) {
                if (!forfeit) loserHandle.resetVitals()
                recovery.restoreNow(loserHandle, loserTicket, respawn = false, lobby = !forfeit)
            }
        }

        sync.publish(match)
        recordResult(winner, loser)
    }

    /** 戦績更新の失敗は winner/loser それぞれ独立に報告し、復元・相手の記録を止めない。 */
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

    // ---- カウントダウン ---------------------------------------------------------

    internal fun startInitialCountdown(arenaId: ArenaId) {
        runCountdown(arenaId, ticks = 5, stillCounting = { it.canBeginMatch }) {
            if (remaining > 0) {
                presentation.countdownTick(participantIds, remaining)
                return@runCountdown false
            }
            // 開始直前に両者の接続・生存を再確認(死亡中は開始を保留)
            if (p1.dead || p2.dead) return@runCountdown false
            // 両者の持ち物を一括保存してから装備を交換する
            val refs = try {
                backups.backupBeforeMatch(MatchId.newId(), match.participants)
            } catch (e: PersistenceFailure) {
                failures.report("Could not save inventories before starting arena ${arenaId.name}; match aborted", e)
                abort(arenaId)
                return@runCountdown true
            }
            recovery.register(refs)
            try {
                kit.applyKit(arenaId, first.id)
                kit.applyKit(arenaId, second.id)
                p1.prepareForMatch()
                p2.prepareForMatch()
                teleportToSlot(match, first, p1)
                teleportToSlot(match, second, p2)
                presentation.matchStart(participantIds)
                val began = registry.transact(arenaId) { it.beginMatch() }
                if (began?.outcome == true) {
                    presentation.updateScoreboard(began.match)
                    sync.publish(began.match)
                }
            } catch (e: Exception) {
                // 交換途中失敗: 取得済みバックアップで中断・復元する
                failures.report("Could not apply equipment before starting arena ${arenaId.name}; match aborted", e)
                abort(arenaId)
            }
            true
        }
    }

    private fun startRoundCountdown(arenaId: ArenaId) {
        runCountdown(arenaId, ticks = 7, stillCounting = { it.canResumeRound }) {
            when (remaining) {
                7 -> {
                    kit.applyKit(arenaId, first.id)
                    kit.applyKit(arenaId, second.id)
                    p1.prepareForMatch()
                    p2.prepareForMatch()
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
     * カウントダウン共通骨格。毎 tick 最新の match を再読みし、世代トークン一致と
     * stillCounting の進行条件を確認してから両者のハンドルを解決する。
     * ハンドル消失(切断)時はタスク終了と併せて abort する。onTick が true を
     * 返した tick で終了。remaining は ticks から減り 0 以下でも呼ばれる。
     */
    private fun runCountdown(
        arenaId: ArenaId,
        ticks: Int,
        stillCounting: (ArenaMatch) -> Boolean,
        onTick: CountdownTick.() -> Boolean
    ) {
        val gen = registry.match(arenaId)?.token ?: return
        var remaining = ticks
        timers[arenaId] = scheduler.repeat(10, 20) { task ->
            val match = registry.match(arenaId)
            if (match == null || match.token != gen || !stillCounting(match)) {
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

    /** runCountdown の各 tick に渡す、match と両者ハンドルを解決済みのコンテキスト。 */
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

    // ---- 共通 -----------------------------------------------------------------

    /**
     * 次 tick に死亡中プレイヤーの復元系後処理を行う。
     * ticket があれば同一性が、無ければ valid が実行時点でも成立するときだけ
     * ハンドルを解決し、未リスポーンなら先に respawn してから action を実行する。
     * オフライン等でハンドルを得られなければ何もしない。
     */
    private fun scheduleDeferred(
        playerId: Uuid,
        ticket: PlayerRecoveryService.RestoreTicket?,
        valid: () -> Boolean,
        action: (PlayerHandle) -> Unit
    ) {
        scheduler.schedule(0) {
            val stillValid = if (ticket != null) {
                recovery.pending(playerId) === ticket
            } else {
                valid()
            }
            if (!stillValid) return@schedule
            val h = players.handle(playerId)?.takeIf { it.online } ?: return@schedule
            if (h.dead) h.respawn()
            action(h)
        }
    }

    private fun teleportToSlot(match: ArenaMatch, participant: Participant, handle: PlayerHandle) {
        val slot = match.slotOf(participant.id) ?: return
        val spawn = registry.definition(match.arenaId)?.spawn(slot)
        if (spawn == null) {
            failures.warn("Arena ${match.arenaId.name} spawn ${slot + 1} is not set; skipping teleport")
            return
        }
        handle.teleport(spawn)
    }
}
