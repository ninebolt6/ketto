package net.ninebolt.onevsone.domain

import java.util.UUID

/**
 * 1 アリーナの参加者と進行状態を所有する集約。immutable: 各操作は
 * 新しい状態を持つ Transition を返し、このインスタンス自身は変化しない。
 * Bukkit・スケジューラ・永続化は持たず、タイミング制御のために世代トークン
 * (MatchToken)を発行する。participants の並び順 = 参加順 = スポーンスロット番号。
 */
data class ArenaMatch(
    val arenaId: ArenaId,
    val requiredWins: Int,
    val state: ArenaState = ArenaState.WAITING,
    val participants: List<Participant> = emptyList(),
    val wins: Map<UUID, Int> = emptyMap(),
    /**
     * 敗北の解決(リスポーン・再装備)が完了するまでの重複決着ガード。
     * 同じ解決区間での二重加点を防ぐ。
     */
    val resolving: Boolean = false,
    val epoch: Long = 0L
) {
    companion object {
        const val MAX_PARTICIPANTS = 2
    }

    val token: MatchToken get() = MatchToken(epoch)

    val joinable: Boolean get() = state.isJoinable()

    val full: Boolean get() = participants.size == MAX_PARTICIPANTS

    /** Y<=0 落下を敗北として解決するか(落下を受理する状態かつ 2 人在籍)。 */
    val resolvesVoidFall: Boolean
        get() = state.acceptsDefeat(DefeatCause.FALL) && full

    val canBeginMatch: Boolean get() = state == ArenaState.COUNTDOWN && full

    val canResumeRound: Boolean get() = state == ArenaState.ROUNDCOUNTDOWN && full

    fun participant(id: UUID): Participant? = participants.firstOrNull { it.id == id }

    /** 参加者のスポーンスロット(0 始まり = spawn1/spawn2)。非参加なら null。 */
    fun slotOf(id: UUID): Int? =
        participants.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    fun participantAt(slot: Int): Participant? = participants.getOrNull(slot)

    fun winsOf(id: UUID): Int = wins[id] ?: 0

    fun join(participant: Participant): Transition<JoinOutcome> {
        if (!state.isJoinable() || full || participants.any { it.id == participant.id }) {
            return Transition(this, JoinOutcome.Rejected)
        }
        val joined = participants + participant
        return if (joined.size == 1) {
            Transition(copy(participants = joined, state = ArenaState.ONEMORE), JoinOutcome.FirstJoined)
        } else {
            Transition(copy(participants = joined, state = ArenaState.COUNTDOWN), JoinOutcome.MatchReady)
        }
    }

    /** 退出しても持ち物には関知しない(未開始のため)。 */
    fun leaveWaiting(id: UUID): Transition<LeaveOutcome> {
        if (state != ArenaState.ONEMORE) return Transition(this, LeaveOutcome.NotWaiting)
        val participant = participant(id) ?: return Transition(this, LeaveOutcome.NotWaiting)
        return Transition(
            copy(
                participants = participants.filterNot { it.id == id },
                state = ArenaState.WAITING,
                epoch = epoch + 1
            ),
            LeaveOutcome.Left(participant)
        )
    }

    /**
     * 未開始なら登録解除のみ、進行中なら相手を勝者とする不戦敗でマッチ終了。
     */
    fun forfeit(id: UUID): Transition<QuitOutcome> {
        val participant = participant(id) ?: return Transition(this, QuitOutcome.NotParticipant)
        if (state == ArenaState.ONEMORE || state == ArenaState.WAITING || !full) {
            return Transition(
                copy(
                    participants = participants - participant,
                    state = ArenaState.WAITING,
                    epoch = epoch + 1
                ),
                QuitOutcome.WaitingExit(participant)
            )
        }
        val winner = participants.first { it.id != id }
        return Transition(finished(), QuitOutcome.MatchEnded(winner, participant))
    }

    /**
     * 死亡/落下の敗北通知。受理されれば RoundWon か MatchFinished。
     * 死亡は INGAME のみ、落下は INGAME/ROUNDCOUNTDOWN で受理する現挙動を維持。
     */
    fun recordDefeat(id: UUID, cause: DefeatCause): Transition<DefeatOutcome> {
        if (!state.acceptsDefeat(cause)) return Transition(this, DefeatOutcome.Rejected)
        if (!full || resolving) return Transition(this, DefeatOutcome.Rejected)
        val loser = participant(id) ?: return Transition(this, DefeatOutcome.Rejected)
        val winner = participants.first { it.id != id }
        // 加算前の累計勝数で終了判定。最終キルは勝数に加算しない。
        if (winsOf(winner.id) >= requiredWins - 1) {
            return Transition(finished(), DefeatOutcome.MatchFinished(winner, loser))
        }
        val next = copy(
            state = ArenaState.ROUNDCOUNTDOWN,
            resolving = true,
            epoch = epoch + 1,
            wins = wins + (winner.id to winsOf(winner.id) + 1)
        )
        return Transition(
            next,
            DefeatOutcome.RoundWon(
                round = next.wins.values.sum(),
                winner = winner,
                loser = loser,
                resolution = next.token
            )
        )
    }

    /** 受理されたら outcome=true。 */
    fun beginMatch(): Transition<Boolean> =
        if (canBeginMatch) {
            Transition(copy(state = ArenaState.INGAME), true)
        } else {
            Transition(this, false)
        }

    /** ROUNDCOUNTDOWN 完了で INGAME へ復帰。解決ガードもここで解放する。 */
    fun resumeRound(): Transition<Boolean> =
        if (state == ArenaState.ROUNDCOUNTDOWN) {
            Transition(copy(state = ArenaState.INGAME, resolving = false), true)
        } else {
            Transition(this, false)
        }

    /**
     * 敗北解決区間の終了(リスポーン後の再装備完了、または非死亡ラウンドの次 tick)。
     * RoundWon が発行した世代トークンと一致する場合のみガードを解放する。
     * トークン不一致(中断・次ラウンド進行済み等)は no-op。
     */
    fun releaseResolution(token: MatchToken): ArenaMatch =
        if (this.token == token) copy(resolving = false) else this

    /** 進行中のカウントダウンや解決待ちコールバックは世代進行で無効化される。 */
    fun abort(): Transition<List<Participant>> =
        Transition(
            copy(
                state = ArenaState.WAITING,
                participants = emptyList(),
                wins = emptyMap(),
                resolving = false,
                epoch = epoch + 1
            ),
            participants
        )

    private fun finished(): ArenaMatch =
        copy(
            state = ArenaState.WAITING,
            participants = emptyList(),
            wins = emptyMap(),
            resolving = false,
            epoch = epoch + 1
        )
}
