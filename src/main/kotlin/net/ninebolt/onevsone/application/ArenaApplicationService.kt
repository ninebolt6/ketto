package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.application.port.warnOnFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.DefeatOutcome
import net.ninebolt.onevsone.domain.JoinOutcome
import net.ninebolt.onevsone.domain.LeaveOutcome
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.domain.QuitOutcome
import kotlin.uuid.Uuid

/**
 * 参加・退出・切断・勝敗入口・ライフサイクルのオーケストレーション。
 * 開始カウントダウン・ラウンド遷移・決着・中断の進行機構は
 * MatchProgressionService へ委譲する(依存は一方向)。
 * 入力は UUID 等、出力は結果または集約スナップショット。JavaPlugin や Messages は受け取らない。
 * すべての操作はメインスレッドで直列化されている前提。
 */
class ArenaApplicationService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val matchState: MatchStateRepository,
    private val stats: PlayerStatsRepository,
    private val players: PlayerPort,
    private val presentation: MatchPresentationPort,
    private val recovery: PlayerRecoveryService,
    private val failures: FailureReporter,
    private val progression: MatchProgressionService,
    private val sync: MatchStateSync
) {

    // ---- 起動・停止 -------------------------------------------------------

    fun load() {
        val loaded = try {
            arenas.loadAll()
        } catch (e: PersistenceFailure) {
            failures.warn("arenalist.yml is unreadable; no arenas loaded this session")
            emptyList()
        }
        loaded.forEach { arena ->
            registry.installArena(arena)
            failures.warnOnFailure("Could not persist status for arena ${arena.id.name}; continuing startup") {
                registry.match(arena.id)?.let { matchState.saveStatus(it) }
            }
            failures.warnOnFailure("Could not update sign for arena ${arena.id.name}; continuing startup") {
                presentation.updateSign(arena.id, ArenaState.WAITING)
            }
        }
        failures.warnOnFailure("players.yml is unreadable; pending restores unavailable this session") {
            recovery.loadPersisted()
        }
        failures.warnOnFailure("Could not clear stale players.yml registrations") {
            matchState.clearRegistrations()
        }
    }

    fun shutdown() {
        registry.matches().forEach { match ->
            val arenaId = match.arenaId
            progression.cancelCountdown(arenaId)
            val left = registry.transact(arenaId) { it.abort() }?.outcome ?: emptyList()
            left.forEach { unregister(it) }
            failures.warnOnFailure("Could not persist shutdown state for arena $arenaId; continuing shutdown") {
                registry.match(arenaId)?.let { matchState.saveStatus(it) }
            }
        }
        recovery.restoreAllOnline()
    }

    // ---- 問い合わせ -------------------------------------------------------

    fun arenaIdOf(playerId: Uuid): Arena.Id? = registry.arenaOf(playerId)

    fun matchOf(playerId: Uuid): ArenaMatch? =
        registry.arenaOf(playerId)?.let { registry.match(it) }

    fun arena(name: String): Arena? = Arena.Id.of(name)?.let { registry.arena(it) }

    fun matchOf(name: String): ArenaMatch? = Arena.Id.of(name)?.let { registry.match(it) }

    /** 破損時は PersistenceFailure を投げる(呼び出し側で扱う)。 */
    fun statsFor(playerId: Uuid): PlayerStats? = stats.find(playerId)

    fun pendingRestore(playerId: Uuid) = recovery.pending(playerId)

    // ---- 参加・退出・切断 ---------------------------------------------------

    fun join(playerId: Uuid, playerName: String, arenaId: Arena.Id): JoinReply {
        if (registry.isJoined(playerId)) return JoinReply.AlreadyJoined
        val arena = registry.arena(arenaId) ?: return JoinReply.NotFound
        val match = registry.match(arenaId) ?: return JoinReply.NotFound
        if (!arena.enabled) return JoinReply.NotEnabled
        val participant = Participant.new(playerId, playerName)

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
                // 未開始の退出では持ち物を変更しない(バックアップ無し・復元無し)
                sync.publish(step.match)
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
                unregister(outcome.participant)
                sync.publish(step.match)
            }
            is QuitOutcome.MatchEnded -> {
                progression.finishMatch(step.match, outcome.winner, outcome.loser, forfeit = true, death = false)
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
                progression.endRound(step.match, outcome, death = cause == DefeatCause.DEATH)
                true
            }
            is DefeatOutcome.MatchFinished -> {
                progression.finishMatch(step.match, outcome.winner, outcome.loser, forfeit = false, death = cause == DefeatCause.DEATH)
                true
            }
        }
    }

    /** 死亡したが敗北として受理されなかった場合のリスポーン予約。 */
    fun requestRespawn(playerId: Uuid) = progression.requestRespawn(playerId)

    // ---- 中断 ---------------------------------------------------------------

    fun abort(arenaId: Arena.Id) = progression.abort(arenaId)

    /** 台帳解除の失敗は warn に潰し後続処理を止めない。 */
    private fun unregister(participant: Participant) =
        failures.warnOnFailure("Could not unregister ${participant.name} from players.yml; membership record may be stale") {
            sync.unregister(participant)
        }
}
