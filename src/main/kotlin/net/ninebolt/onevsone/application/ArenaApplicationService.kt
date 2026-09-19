package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerEquipmentPort
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
import net.ninebolt.onevsone.domain.isValidArenaName
import java.util.Locale
import java.util.UUID

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
    private val equipment: PlayerEquipmentPort,
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
        val seen = mutableSetOf<String>()
        for (name in arenas.arenaNames()) {
            if (!isValidArenaName(name)) {
                failures.warn("Ignoring invalid arena name '$name' in arenalist.yml")
                continue
            }
            if (!seen.add(name.lowercase(Locale.ROOT))) {
                failures.warn("Ignoring duplicate arena name '$name' in arenalist.yml")
                continue
            }
            val definition = try {
                arenas.find(name)
            } catch (e: PersistenceFailure) {
                null
            }
            if (definition == null) {
                failures.warn("Arena '$name' could not be loaded; skipping")
                continue
            }
            registry.putDefinition(definition)
            val match = ArenaMatch(definition.id, requiredWins)
            registry.installMatch(match)
            matchState.saveStatus(match)
            presentation.updateSign(definition.id, ArenaState.WAITING)
        }
        try {
            recovery.loadPersisted()
        } catch (e: PersistenceFailure) {
            failures.warn("players.yml is unreadable; pending restores unavailable this session")
        }
        try {
            matchState.clearRegistrations()
        } catch (e: PersistenceFailure) {
            failures.warn("Could not clear stale players.yml registrations")
        }
    }

    fun shutdown() {
        for (match in registry.matches()) {
            timers.remove(match.arenaId)?.cancel()
            val left = registry.transact(match.arenaId) { it.abort() }?.outcome ?: emptyList()
            for (participant in left) {
                registry.unassign(participant.id)
                try {
                    matchState.unregisterParticipant(participant.name)
                } catch (e: PersistenceFailure) {
                    failures.warn("Could not unregister ${participant.name} from players.yml; membership record may be stale")
                }
            }
            registry.match(match.arenaId)?.let { matchState.saveStatus(it) }
        }
        recovery.restoreAllOnline()
    }

    // ---- 問い合わせ -------------------------------------------------------

    fun arenaIdOf(playerId: UUID): ArenaId? = registry.arenaOf(playerId)

    fun matchOf(playerId: UUID): ArenaMatch? =
        registry.arenaOf(playerId)?.let { registry.match(it) }

    fun definition(name: String) = registry.definition(ArenaId(name))

    fun matchOf(name: String): ArenaMatch? = registry.match(ArenaId(name))

    /** stats 問い合わせ。破損時は PersistenceFailure を投げる(呼び出し側で扱う)。 */
    fun statsFor(playerId: UUID): PlayerStats? = stats.find(playerId)

    fun pendingRestore(playerId: UUID) = recovery.pending(playerId)

    // ---- 参加・退出・切断 ---------------------------------------------------

    fun join(playerId: UUID, playerName: String, arenaId: ArenaId): JoinReply {
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
        registry.assign(playerId, arenaId)
        if (step.outcome == JoinOutcome.MatchReady) startInitialCountdown(arenaId)
        matchState.saveStatus(step.match)
        presentation.updateSign(arenaId, step.match.state)
        return when (step.outcome) {
            JoinOutcome.FirstJoined -> JoinReply.JoinedWaiting
            JoinOutcome.MatchReady -> JoinReply.JoinedStarting
            JoinOutcome.Rejected -> JoinReply.InMatch
        }
    }

    fun leave(playerId: UUID): LeaveReply {
        val arenaId = registry.arenaOf(playerId) ?: return LeaveReply.NotJoined
        val step = registry.transact(arenaId) { it.leaveWaiting(playerId) }
            ?: return LeaveReply.NotJoined
        when (val outcome = step.outcome) {
            LeaveOutcome.NotWaiting -> return LeaveReply.NotWaiting
            is LeaveOutcome.Left -> {
                registry.unassign(playerId)
                outcome.participant?.let { participant ->
                    try {
                        matchState.unregisterParticipant(participant.name)
                    } catch (e: PersistenceFailure) {
                        failures.warn("Could not unregister ${participant.name} from players.yml; membership record may be stale")
                    }
                }
                // 未開始の退出では持ち物を変更しない(バックアップ無し・フォールバック無し)
                matchState.saveStatus(step.match)
                presentation.updateSign(arenaId, step.match.state)
                return LeaveReply.Left
            }
        }
    }

    /**
     * ログアウト。QuitEvent 中はアダプターが切断中プレイヤーの操作ハンドルを
     * 提供するので、ここでは UUID だけで処理する。
     */
    fun quit(playerId: UUID, playerName: String) {
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
        val step = registry.transact(arenaId) { it.forfeit(playerId) } ?: run {
            registry.unassign(playerId)
            return
        }
        when (val outcome = step.outcome) {
            is QuitOutcome.WaitingExit -> {
                registry.unassign(playerId)
                try {
                    matchState.unregisterParticipant(outcome.participant.name)
                } catch (e: PersistenceFailure) {
                    failures.warn("Could not unregister ${outcome.participant.name} from players.yml; membership record may be stale")
                }
                matchState.saveStatus(step.match)
                presentation.updateSign(arenaId, step.match.state)
            }
            is QuitOutcome.MatchEnded -> {
                finishMatch(step.match, outcome.winner, outcome.loser, forfeit = true, death = false)
            }
            QuitOutcome.NotParticipant -> registry.unassign(playerId)
        }
    }

    /** PlayerJoinEvent 相当: 未参加なら未復元バックアップを復元する。 */
    fun restorePending(playerId: UUID, playerName: String) {
        if (registry.isJoined(playerId)) return
        val ticket = recovery.ticketFor(playerId, playerName) ?: return
        val handle = players.handle(playerId) ?: return
        recovery.restoreNow(handle, ticket, respawn = true, lobby = false)
    }

    // ---- 勝敗 --------------------------------------------------------------

    /** 死亡/落下の敗北通知。受理されれば true。 */
    fun defeat(playerId: UUID, cause: DefeatCause): Boolean {
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
    fun requestRespawn(playerId: UUID) {
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
        for (participant in left) {
            registry.unassign(participant.id)
            try {
                matchState.unregisterParticipant(participant.name)
            } catch (e: PersistenceFailure) {
                failures.warn("Could not unregister ${participant.name} from players.yml; pending restore retained in memory")
            }
        }
        for ((participant, ticket) in tickets) {
            val handle = players.handle(participant.id) ?: continue
            if (handle.dead) {
                if (ticket != null) {
                    scheduler.schedule(0) {
                        if (recovery.pending(participant.id) !== ticket) return@schedule
                        val h = players.handle(participant.id)?.takeIf { it.online } ?: return@schedule
                        if (h.dead) h.respawn()
                        recovery.restoreNow(h, ticket, respawn = false, lobby = false)
                    }
                } else {
                    scheduler.schedule(0) {
                        players.handle(participant.id)?.takeIf { it.online && it.dead }?.respawn()
                    }
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
            equipment.applyKit(arenaId, outcome.winner.id)
        }

        players.handle(outcome.loser.id)?.position()?.let { presentation.roundEndSound(it) }

        val ids = match.participants.map { it.id }
        presentation.roundWon(ids, outcome.round, outcome.winner.name)
        presentation.updateScoreboard(match)

        val loserHandle = players.handle(outcome.loser.id)
        if (death) {
            scheduler.schedule(0) {
                val current = registry.match(arenaId) ?: return@schedule
                if (current.token != gen) return@schedule
                if (registry.arenaOf(outcome.loser.id) != arenaId) return@schedule
                val h = players.handle(outcome.loser.id)?.takeIf { it.online } ?: return@schedule
                if (h.dead) h.respawn()
                h.prepareForMatch()
                equipment.applyKit(arenaId, outcome.loser.id)
                teleportToSlot(current, outcome.loser, h)
                registry.updateMatch(arenaId) { it.releaseResolution(gen) }
            }
        } else if (loserHandle != null) {
            loserHandle.prepareForMatch()
            equipment.applyKit(arenaId, outcome.loser.id)
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
        registry.unassign(winner.id)
        registry.unassign(loser.id)
        for (participant in listOf(winner, loser)) {
            try {
                matchState.unregisterParticipant(participant.name)
            } catch (e: PersistenceFailure) {
                failures.warn("Could not unregister ${participant.name} from players.yml; pending restore retained in memory")
            }
        }

        presentation.champion(arenaId, winner.name)

        val winnerHandle = players.handle(winner.id)
        if (winnerHandle != null && !winnerHandle.dead) {
            winnerHandle.resetVitals()
            recovery.restoreNow(winnerHandle, winnerTicket, respawn = false, lobby = true)
            if (!forfeit) presentation.championFirework(winner.id)
        } else if (winnerHandle != null) {
            scheduler.schedule(0) {
                if (winnerTicket != null) {
                    if (recovery.pending(winner.id) !== winnerTicket) return@schedule
                } else if (registry.match(arenaId)?.token != gen || registry.arenaOf(winner.id) != null) {
                    return@schedule
                }
                val h = players.handle(winner.id)?.takeIf { it.online } ?: return@schedule
                if (h.dead) h.respawn()
                h.resetVitals()
                recovery.restoreNow(h, winnerTicket, respawn = false, lobby = true)
                if (!forfeit) presentation.championFirework(winner.id)
            }
        }

        if (death) {
            scheduler.schedule(0) {
                if (loserTicket != null) {
                    if (recovery.pending(loser.id) !== loserTicket) return@schedule
                } else if (registry.match(arenaId)?.token != gen || registry.arenaOf(loser.id) != null) {
                    return@schedule
                }
                val h = players.handle(loser.id)?.takeIf { it.online } ?: return@schedule
                if (h.dead) h.respawn()
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
        for ((participant, win) in listOf(winner to true, loser to false)) {
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
        val gen = registry.match(arenaId)?.token ?: return
        var remaining = 5
        timers[arenaId] = scheduler.repeat(10, 20) { task ->
            val match = registry.match(arenaId)
            if (match == null || match.token != gen || !match.canBeginMatch) {
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
            if (remaining > 0) {
                presentation.countdownTick(match.participants.map { it.id }, remaining)
            } else {
                // 開始直前に両者の接続・生存を再確認(死亡中は開始を保留)
                if (p1.dead || p2.dead) return@repeat
                // 両者の持ち物を一括保存してから装備を交換する
                val refs = try {
                    equipment.backupBeforeMatch(MatchId.newId(), match.participants)
                } catch (e: PersistenceFailure) {
                    failures.report("Could not save inventories before starting arena ${arenaId.name}; match aborted", e)
                    task.cancel()
                    abort(arenaId)
                    return@repeat
                }
                recovery.register(refs)
                try {
                    equipment.applyKit(arenaId, first.id)
                    equipment.applyKit(arenaId, second.id)
                    p1.prepareForMatch()
                    p2.prepareForMatch()
                    teleportToSlot(match, first, p1)
                    teleportToSlot(match, second, p2)
                    presentation.matchStart(match.participants.map { it.id })
                    val began = registry.transact(arenaId) { it.beginMatch() }
                    if (began != null && began.outcome) {
                        presentation.updateScoreboard(began.match)
                        matchState.saveStatus(began.match)
                        presentation.updateSign(arenaId, ArenaState.INGAME)
                    }
                } catch (e: Exception) {
                    // 交換途中失敗: 取得済みバックアップで中断・復元する
                    failures.report("Could not apply equipment before starting arena ${arenaId.name}; match aborted", e)
                    task.cancel()
                    abort(arenaId)
                    return@repeat
                }
                task.cancel()
            }
            remaining--
        }
    }

    private fun startRoundCountdown(arenaId: ArenaId) {
        val gen = registry.match(arenaId)?.token ?: return
        var remaining = 7
        timers[arenaId] = scheduler.repeat(10, 20) { task ->
            val match = registry.match(arenaId)
            if (match == null || match.token != gen || !match.canResumeRound) {
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
            val ids = match.participants.map { it.id }
            when (remaining) {
                7 -> {
                    equipment.applyKit(arenaId, first.id)
                    equipment.applyKit(arenaId, second.id)
                    p1.prepareForMatch()
                    p2.prepareForMatch()
                }
                in 1..5 -> presentation.roundCountdownTick(ids, remaining)
                0 -> {
                    presentation.roundStart(ids)
                    val resumed = registry.transact(arenaId) { it.resumeRound() }
                    if (resumed != null && resumed.outcome) {
                        matchState.saveStatus(resumed.match)
                        presentation.updateSign(arenaId, ArenaState.INGAME)
                    }
                    task.cancel()
                }
            }
            remaining--
        }
    }

    // ---- 内部: 共通 ---------------------------------------------------------

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
