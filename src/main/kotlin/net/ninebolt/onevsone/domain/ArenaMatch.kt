package net.ninebolt.onevsone.domain

import java.util.UUID

/**
 * 1 アリーナの参加者と進行状態を所有する集約。
 * 変更はメソッド経由のみ。Bukkit・スケジューラ・永続化は持たず、
 * タイミング制御のために世代トークン(MatchToken)を発行する。
 */
class ArenaMatch(
    val arenaId: ArenaId,
    private val requiredWins: Int
) {
    companion object {
        const val MAX_PARTICIPANTS = 2
    }

    private var state: ArenaState = ArenaState.WAITING
    private val participants = mutableListOf<Participant>()
    private val wins = mutableMapOf<UUID, Int>()

    /**
     * 敗北の解決(リスポーン・再装備)が完了するまでの重複決着ガード。
     * 同じ解決区間での二重加点を防ぐ。
     */
    private var resolving: Boolean = false

    private var epoch: Long = 0L

    fun view(): MatchView = MatchView(arenaId, state, participants.toList(), wins.toMap())

    fun state(): ArenaState = state

    fun participantCount(): Int = participants.size

    fun participant(id: UUID): Participant? = participants.firstOrNull { it.id == id }

    fun token(): MatchToken = MatchToken(epoch)

    /** 事前検証(永続化前に失敗を確定させるための非変更チェック)。 */
    fun joinRejection(participant: Participant): Boolean =
        !state.isJoinable() ||
            participants.size >= MAX_PARTICIPANTS ||
            participants.any { it.id == participant.id }

    fun join(participant: Participant): JoinOutcome {
        if (joinRejection(participant)) return JoinOutcome.Rejected
        participants += participant
        return if (participants.size == 1) {
            state = ArenaState.ONEMORE
            JoinOutcome.FirstJoined
        } else {
            state = ArenaState.COUNTDOWN
            JoinOutcome.MatchReady
        }
    }

    /** ONEMORE 待機中の任意退出。退出しても持ち物には関知しない(未開始のため)。 */
    fun leaveWaiting(id: UUID): LeaveOutcome {
        if (state != ArenaState.ONEMORE) return LeaveOutcome.NotWaiting
        val participant = participant(id)
        participants.removeIf { it.id == id }
        epoch++
        state = ArenaState.WAITING
        return LeaveOutcome.Left(participant)
    }

    /**
     * 切断。未開始なら登録解除のみ、進行中なら相手を勝者とする不戦敗でマッチ終了。
     */
    fun forfeit(id: UUID): QuitOutcome {
        val participant = participant(id) ?: return QuitOutcome.NotParticipant
        if (state == ArenaState.ONEMORE || state == ArenaState.WAITING || participants.size < MAX_PARTICIPANTS) {
            participants.remove(participant)
            epoch++
            state = ArenaState.WAITING
            return QuitOutcome.WaitingExit(participant)
        }
        val winner = participants.first { it.id != id }
        finishMatch()
        return QuitOutcome.MatchEnded(winner, participant)
    }

    /**
     * 死亡/落下の敗北通知。受理されれば RoundWon か MatchFinished。
     * 死亡は INGAME のみ、落下は INGAME/ROUNDCOUNTDOWN で受理する現挙動を維持。
     */
    fun recordDefeat(id: UUID, cause: DefeatCause): DefeatOutcome {
        if (state != ArenaState.INGAME &&
            !(state == ArenaState.ROUNDCOUNTDOWN && cause == DefeatCause.FALL)
        ) {
            return DefeatOutcome.Rejected
        }
        if (participants.size != MAX_PARTICIPANTS || resolving) return DefeatOutcome.Rejected
        val loser = participant(id) ?: return DefeatOutcome.Rejected
        val winner = participants.first { it.id != id }
        resolving = true
        // 加算前の累計勝数で終了判定。最終キルは勝数に加算しない。
        if ((wins[winner.id] ?: 0) >= requiredWins - 1) {
            finishMatch()
            return DefeatOutcome.MatchFinished(winner, loser)
        }
        epoch++
        wins[winner.id] = (wins[winner.id] ?: 0) + 1
        state = ArenaState.ROUNDCOUNTDOWN
        return DefeatOutcome.RoundWon(
            round = wins.values.sum(),
            winner = winner,
            loser = loser
        )
    }

    /** COUNTDOWN から INGAME への遷移(初回開始)。 */
    fun beginMatch(): Boolean {
        if (state != ArenaState.COUNTDOWN || participants.size != MAX_PARTICIPANTS) return false
        state = ArenaState.INGAME
        return true
    }

    /** ROUNDCOUNTDOWN 完了で INGAME へ復帰。解決ガードもここで解放する。 */
    fun resumeRound(): Boolean {
        if (state != ArenaState.ROUNDCOUNTDOWN) return false
        state = ArenaState.INGAME
        resolving = false
        return true
    }

    /**
     * 敗北解決区間の終了(リスポーン後の再装備完了、または非死亡ラウンドの次 tick)。
     * 呼び出し側は token() で世代を照合してから呼ぶこと。
     */
    fun releaseResolution() {
        resolving = false
    }

    /** 中断。進行中のカウントダウンや解決待ちコールバックは世代進行で無効化される。 */
    fun abort(): List<Participant> {
        epoch++
        resolving = false
        state = ArenaState.WAITING
        val left = participants.toList()
        participants.clear()
        wins.clear()
        return left
    }

    private fun finishMatch() {
        epoch++
        resolving = false
        state = ArenaState.WAITING
        participants.clear()
        wins.clear()
    }
}
