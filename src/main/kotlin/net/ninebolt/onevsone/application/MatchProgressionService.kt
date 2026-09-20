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
 * 開始カウントダウン・ラウンド遷移・決着・中断の進行機構。
 * ArenaApplicationService からの委譲先として、アリーナごとのタイマーを所有し、
 * 遅延コールバックは世代(ArenaMatch.epoch)一致と生存確認で有効性を検証する。
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
    private val timers = mutableMapOf<Arena.Id, Cancellation>()

    /** 死亡したが敗北として受理されなかった場合のリスポーン予約。 */
    fun requestRespawn(playerId: Uuid) {
        scheduler.schedule(0) {
            players.handle(playerId)?.takeIf { it.dead }?.respawn()
        }
    }

    // ---- 中断 ---------------------------------------------------------------

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

    /** 走行中のカウントダウンだけを止める(shutdown 用)。 */
    internal fun cancelCountdown(arenaId: Arena.Id) {
        timers.remove(arenaId)?.cancel()
    }

    // ---- ラウンド・終了 -------------------------------------------------------

    internal fun endRound(match: ArenaMatch, outcome: DefeatOutcome.RoundWon, death: Boolean) {
        val arenaId = match.arenaId
        cancelCountdown(arenaId)
        val gen = match.epoch

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

        // 復元対象を先に確保してから登録解除・タスク停止へ
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
            // 非死亡の敗者は即時処理。quit 経由の死者も含むため dead 判定で遅延化しない
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

    internal fun startInitialCountdown(arenaId: Arena.Id) {
        runCountdown(arenaId, ticks = 5, stillCounting = { it.canBeginMatch }) {
            if (remaining > 0) {
                presentation.countdownTick(participantIds, remaining)
                return@runCountdown false
            }
            // 開始直前に両者の接続・生存を再確認(死亡中は開始を保留)
            if (p1.dead || p2.dead) return@runCountdown false
            // 両者の持ち物を一括保存してから装備を交換する
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
                // 交換途中失敗: 取得済みバックアップで中断・復元する
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
     * カウントダウン共通骨格。毎 tick 最新の match を再読みし、世代トークン一致と
     * stillCounting の進行条件を確認してから両者のハンドルを解決する。
     * ハンドル消失(切断)時はタスク終了と併せて abort する。onTick が true を
     * 返した tick で終了。remaining は ticks から減り 0 以下でも呼ばれる。
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

    /** 台帳解除の失敗は warn に潰す。復元記録はインメモリに保持される。 */
    private fun unregisterKeepingRestore(participant: Participant) =
        failures.warnOnFailure("Could not unregister ${participant.name} from players.yml; pending restore retained in memory") {
            sync.unregister(participant)
        }

    /** 体力・飛行を対戦用に整えてアリーナ装備を適用する。 */
    private fun rearm(arenaId: Arena.Id, participant: Participant, handle: PlayerHandle) {
        handle.prepareForMatch()
        kit.applyKit(arenaId, participant.id)
    }

    /** 体力を戻してバックアップを復元し、ロビーへ送る。 */
    private fun resetAndRestore(handle: PlayerHandle, ticket: PlayerRecoveryService.RestoreTicket?) {
        handle.resetVitals()
        recovery.restoreNow(handle, ticket, respawn = false, lobby = true)
    }

    /**
     * 退出・後処理対象の参加者へ action を振り分ける。生存なら即時、
     * 死亡中なら次 tick の respawn 後に実行する。オフライン(ハンドル無し)は
     * 何もしない。遅延経路の有効条件は ticket 同一性(優先)、無ければ valid。
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
     * 次 tick に死亡中プレイヤーの後処理を行う。
     * valid が実行時点でも成立するときだけハンドルを解決し、
     * 未リスポーンなら先に respawn してから action を実行する。
     * オフライン等でハンドルを得られなければ何もしない。
     */
    private fun scheduleDeferred(playerId: Uuid, valid: () -> Boolean, action: (PlayerHandle) -> Unit) {
        scheduler.schedule(0) {
            if (!valid()) return@schedule
            val h = players.handle(playerId)?.takeIf { it.online } ?: return@schedule
            if (h.dead) h.respawn()
            action(h)
        }
    }

    /** pending 中の復元 ticket の同一性を有効条件とする scheduleDeferred。 */
    private fun scheduleTicketed(
        playerId: Uuid,
        ticket: PlayerRecoveryService.RestoreTicket,
        action: (PlayerHandle) -> Unit
    ) = scheduleDeferred(playerId, { recovery.pending(playerId) === ticket }, action)

    /** ticket があれば同一性で、無ければ valid で有効性を検証する scheduleDeferred。 */
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
