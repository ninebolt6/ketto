package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.application.port.SchedulerPort
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.JoinOutcome
import net.ninebolt.onevsone.domain.LeaveOutcome
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.domain.QuitOutcome
import kotlin.uuid.Uuid

/**
 * 参加・開始・決着・終了・復元・中断のオーケストレーション。
 * 入力は UUID 等、出力は結果または集約スナップショット。JavaPlugin や Messages は受け取らない。
 * すべての操作はメインスレッドで直列化されている前提。
 *
 * ArenaMatch は immutable: 遅延実行されるコールバック内では参照をキャプチャせず
 * registry.match(arenaId) で最新状態を再読みすること。
 */
class ArenaApplicationService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val matchState: MatchStateRepository,
    private val stats: PlayerStatsRepository,
    private val backups: InventoryBackupPort,
    private val kit: KitPort,
    private val players: PlayerPort,
    private val scheduler: SchedulerPort,
    private val presentation: MatchPresentationPort,
    private val recovery: PlayerRecoveryService,
    private val failures: FailureReporter,
    val requiredWins: Int
) {
    private val timers = mutableMapOf<ArenaId, Cancellation>()

    // ---- 起動・停止 -------------------------------------------------------

    fun load() {
        val definitions = try {
            arenas.loadAll()
        } catch (e: PersistenceFailure) {
            failures.warn("arenalist.yml is unreadable; no arenas loaded this session")
            emptyList()
        }
        definitions.forEach { definition ->
            registry.putDefinition(definition)
            val match = ArenaMatch(definition.id, requiredWins)
            registry.installMatch(match)
            warnOnFailure("Could not persist status for arena ${definition.id.name}; continuing startup") {
                matchState.saveStatus(match)
            }
            warnOnFailure("Could not update sign for arena ${definition.id.name}; continuing startup") {
                presentation.updateSign(definition.id, ArenaState.WAITING)
            }
        }
        warnOnFailure("players.yml is unreadable; pending restores unavailable this session") {
            recovery.loadPersisted()
        }
        warnOnFailure("Could not clear stale players.yml registrations") {
            matchState.clearRegistrations()
        }
    }

    fun shutdown() {
        registry.matches().forEach { match ->
            val arenaId = match.arenaId
            timers.remove(arenaId)?.cancel()
            val left = registry.transact(arenaId) { it.abort() }?.outcome ?: emptyList()
            left.forEach { (_, name) ->
                warnOnFailure("Could not unregister $name from players.yml; membership record may be stale") {
                    matchState.unregisterParticipant(name)
                }
            }
            warnOnFailure("Could not persist shutdown state for arena $arenaId; continuing shutdown") {
                registry.match(arenaId)?.let { matchState.saveStatus(it) }
            }
        }
        recovery.restoreAllOnline()
    }

    // ---- 問い合わせ -------------------------------------------------------

    fun arenaIdOf(playerId: Uuid): ArenaId? = registry.arenaOf(playerId)

    fun matchOf(playerId: Uuid): ArenaMatch? =
        registry.arenaOf(playerId)?.let { registry.match(it) }

    fun definition(name: String) = registry.definition(ArenaId(name))

    fun matchOf(name: String): ArenaMatch? = registry.match(ArenaId(name))

    /** 破損時は PersistenceFailure を投げる(呼び出し側で扱う)。 */
    fun statsFor(playerId: Uuid): PlayerStats? = stats.find(playerId)

    fun pendingRestore(playerId: Uuid) = recovery.pending(playerId)

    // ---- 参加・退出・切断 ---------------------------------------------------

    fun join(playerId: Uuid, playerName: String, arenaId: ArenaId): JoinReply {
        if (registry.isJoined(playerId)) return JoinReply.AlreadyJoined
        val definition = registry.definition(arenaId) ?: return JoinReply.NotFound
        val match = registry.match(arenaId) ?: return JoinReply.NotFound
        if (!definition.enabled) return JoinReply.NotEnabled
        val participant = Participant(playerId, playerName)

        // join は純粋関数: コミット前に拒否を確定させる
        val step = match.join(participant)
        if (step.outcome == JoinOutcome.Rejected) return JoinReply.InMatch

        // 前回の未復元バックアップがあれば再参加前に完了させる(持ち物は読まない)
        val handle = players.handle(playerId)
        recovery.ticketFor(playerId, playerName)?.let { ticket ->
            if (handle == null || handle.dead) return JoinReply.InMatch
            recovery.restoreNow(handle, ticket, respawn = true, lobby = false)
        }

        // メンバーシップ登録。失敗時はまだコミット前なので、
        // 何も変わっていない状態で例外を投げる。
        matchState.registerParticipant(participant, arenaId)
        registry.installMatch(step.match)
        if (step.outcome == JoinOutcome.MatchReady) startInitialCountdown(arenaId)
        matchState.saveStatus(step.match)
        presentation.updateSign(arenaId, step.match.state)
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
                warnOnFailure("Could not unregister ${outcome.participant.name} from players.yml; membership record may be stale") {
                    matchState.unregisterParticipant(outcome.participant.name)
                }
                // 未開始の退出では持ち物を変更しない(バックアップ無し・フォールバック無し)
                matchState.saveStatus(step.match)
                presentation.updateSign(arenaId, step.match.state)
                return LeaveReply.Left
            }
        }
    }

    /**
     * QuitEvent 中はアダプターが切断中プレイヤーの操作ハンドルを
     * 提供するので、ここでは UUID だけで処理する。
     */
    fun quit(playerId: Uuid, playerName: String) {
        val arenaId = registry.arenaOf(playerId)
        if (arenaId == null) {
            // 参加していなくても未復元バックアップがあれば復元して切断に備える
            recovery.ticketFor(playerId, playerName)?.let { ticket ->
                players.handle(playerId)?.let { handle ->
                    recovery.restoreNow(handle, ticket, respawn = false, lobby = false)
                }
            }
            return
        }
        val step = registry.transact(arenaId) { it.forfeit(playerId) } ?: return
        when (val outcome = step.outcome) {
            is QuitOutcome.WaitingExit -> {
                warnOnFailure("Could not unregister ${outcome.participant.name} from players.yml; membership record may be stale") {
                    matchState.unregisterParticipant(outcome.participant.name)
                }
                matchState.saveStatus(step.match)
                presentation.updateSign(arenaId, step.match.state)
            }
            is QuitOutcome.MatchEnded -> {
                finishMatch(step.match, outcome.winner, outcome.loser, forfeit = true, death = false)
            }
            QuitOutcome.NotParticipant -> Unit
        }
    }

    /** PlayerJoinEvent 相当。 */
    fun restorePending(playerId: Uuid, playerName: String) {
        if (registry.isJoined(playerId)) return
        val ticket = recovery.ticketFor(playerId, playerName) ?: return
        val handle = players.handle(playerId) ?: return
        recovery.restoreNow(handle, ticket, respawn = true, lobby = false)
    }

    // ---- 勝敗 --------------------------------------------------------------

    /** 受理されれば true。 */
    fun defeat(playerId: Uuid, cause: DefeatCause): Boolean {
        val arenaId = registry.arenaOf(playerId) ?: return false
        val step = registry.transact(arenaId) { it.recordDefeat(playerId, cause) } ?: return false
        return when (val outcome = step.outcome) {
            DefeatOutcome.Rejected -> false
            is DefeatOutcome.RoundWon -> {
                endRound(step.match, outcome, death = cause == DefeatCause.DEATH)
                true
            }
            is DefeatOutcome.MatchFinished -> {
                finishMatch(step.match, outcome.winner, outcome.loser, forfeit = false, death = cause == DefeatCause.DEATH)
                true
            }
        }
    }

    /** 死亡したが敗北として受理されなかった場合のリスポーン予約。 */
    fun requestRespawn(playerId: Uuid) {
        scheduler.schedule(0) {
            players.handle(playerId)?.takeIf { it.dead }?.respawn()
        }
    }

    // ---- 中断 ---------------------------------------------------------------

    fun abort(arenaId: ArenaId) {
        timers.remove(arenaId)?.cancel()
        val step = registry.transact(arenaId) { it.abort() } ?: return
        val left = step.outcome
        val tickets = left.map { it to recovery.pending(it.id) }
        left.forEach { (_, name) ->
            warnOnFailure("Could not unregister $name from players.yml; pending restore retained in memory") {
                matchState.unregisterParticipant(name)
            }
        }
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
        matchState.saveStatus(step.match)
        presentation.updateSign(arenaId, ArenaState.WAITING)
    }

    // ---- 内部: ラウンド・終了 -----------------------------------------------

    private fun endRound(match: ArenaMatch, outcome: DefeatOutcome.RoundWon, death: Boolean) {
        val arenaId = match.arenaId
        timers.remove(arenaId)?.cancel()
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

        matchState.saveStatus(match)
        presentation.updateSign(arenaId, ArenaState.ROUNDCOUNTDOWN)
        startRoundCountdown(arenaId)
    }

    private fun finishMatch(
        match: ArenaMatch,
        winner: Participant,
        loser: Participant,
        forfeit: Boolean,
        death: Boolean
    ) {
        val arenaId = match.arenaId
        timers.remove(arenaId)?.cancel()
        val gen = match.token

        // 復元対象を先に確保してから登録解除・タスク停止へ
        val winnerTicket = recovery.pending(winner.id)
        val loserTicket = recovery.pending(loser.id)
        listOf(winner, loser).forEach { (_, name) ->
            warnOnFailure("Could not unregister $name from players.yml; pending restore retained in memory") {
                matchState.unregisterParticipant(name)
            }
        }

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

        matchState.saveStatus(match)
        presentation.updateSign(arenaId, ArenaState.WAITING)
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

    // ---- 内部: カウントダウン -------------------------------------------------

    private fun startInitialCountdown(arenaId: ArenaId) {
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
                if (began != null && began.outcome) {
                    presentation.updateScoreboard(began.match)
                    matchState.saveStatus(began.match)
                    presentation.updateSign(arenaId, ArenaState.INGAME)
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
                    if (resumed != null && resumed.outcome) {
                        matchState.saveStatus(resumed.match)
                        presentation.updateSign(arenaId, ArenaState.INGAME)
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

    // ---- 内部: 共通 ---------------------------------------------------------

    /** PersistenceFailure を warn に潰す共通の失敗経路。 */
    private inline fun warnOnFailure(message: String, block: () -> Unit) {
        try {
            block()
        } catch (e: PersistenceFailure) {
            failures.warn(message)
        }
    }

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
