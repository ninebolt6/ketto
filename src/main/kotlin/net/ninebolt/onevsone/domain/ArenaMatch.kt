package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/**
 * 1 アリーナの参加者と進行状態を所有する集約。immutable: 各操作は
 * 新しい状態を持つ Transition を返し、このインスタンス自身は変化しない。
 * Bukkit・スケジューラ・永続化は持たず、タイミング制御のために世代(epoch)を
 * 進める。participants の並び順 = 参加順 = スポーンスロット番号。
 */
data class ArenaMatch private constructor(
    val arenaId: Arena.Id,
    val requiredWins: Int,
    val state: ArenaState = ArenaState.WAITING,
    val participants: List<Participant> = emptyList(),
    val wins: Map<Uuid, Int> = emptyMap(),
    /**
     * 敗北の解決(リスポーン・再装備)が完了するまでの重複決着ガード。
     * 同じ解決区間での二重加点を防ぐ。
     */
    val resolving: Boolean = false,
    val epoch: Long = 0L
) {
    companion object {
        const val MAX_PARTICIPANTS = 2

        /** 新規(WAITING・0 人)の集約。 */
        fun new(arenaId: Arena.Id, requiredWins: Int): ArenaMatch {
            require(requiredWins >= 1) { "requiredWins must be >= 1 (was $requiredWins)" }
            return ArenaMatch(arenaId, requiredWins)
        }

        /**
         * スナップショット等からの全状態再構築。遷移関数が維持する不変条件
         * (状態↔参加人数・参加者一意・wins は参加者のみ・resolving は
         * ROUNDCOUNTDOWN のみ)をここで検証する。
         */
        fun restored(
            arenaId: Arena.Id,
            requiredWins: Int,
            state: ArenaState,
            participants: List<Participant>,
            wins: Map<Uuid, Int>,
            resolving: Boolean = false,
            epoch: Long = 0L
        ): ArenaMatch {
            require(requiredWins >= 1) { "requiredWins must be >= 1 (was $requiredWins)" }
            require(participants.size == expectedParticipants(state)) {
                "state $state expects ${expectedParticipants(state)} participants (was ${participants.size})"
            }
            require(participants.distinctBy { it.id }.size == participants.size) {
                "duplicate participant ids"
            }
            val ids = participants.mapTo(HashSet()) { it.id }
            require(wins.keys.all { it in ids }) { "wins recorded for non-participant" }
            require(!resolving || state == ArenaState.ROUNDCOUNTDOWN) {
                "resolving is only valid in ROUNDCOUNTDOWN (was $state)"
            }
            require(epoch >= 0) { "epoch must be >= 0 (was $epoch)" }
            return ArenaMatch(arenaId, requiredWins, state, participants, wins, resolving, epoch)
        }

        private fun expectedParticipants(state: ArenaState): Int = when (state) {
            ArenaState.WAITING -> 0
            ArenaState.ONEMORE -> 1
            ArenaState.COUNTDOWN, ArenaState.ROUNDCOUNTDOWN, ArenaState.INGAME -> MAX_PARTICIPANTS
        }
    }

    val joinable: Boolean get() = state.isJoinable()

    val full: Boolean get() = participants.size == MAX_PARTICIPANTS

    /** Y<=0 落下を敗北として解決するか(落下を受理する状態かつ 2 人在籍)。 */
    val resolvesVoidFall: Boolean
        get() = state.acceptsDefeat(DefeatCause.FALL) && full

    val canBeginMatch: Boolean get() = state == ArenaState.COUNTDOWN && full

    val canResumeRound: Boolean get() = state == ArenaState.ROUNDCOUNTDOWN && full

    /** 対戦が進行中(ROUNDCOUNTDOWN/INGAME で両者在籍)。対戦カード表示等の判定用。 */
    val inProgress: Boolean get() =
        (state == ArenaState.INGAME || state == ArenaState.ROUNDCOUNTDOWN) && full

    fun participant(id: Uuid): Participant? = participants.firstOrNull { it.id == id }

    /** 参加者のスポーンスロット(0 始まり = spawn1/spawn2)。非参加なら null。 */
    fun slotOf(id: Uuid): Int? =
        participants.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    fun participantAt(slot: Int): Participant? = participants.getOrNull(slot)

    fun winsOf(id: Uuid): Int = wins[id] ?: 0

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
    fun leaveWaiting(id: Uuid): Transition<LeaveOutcome> {
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
    fun forfeit(id: Uuid): Transition<QuitOutcome> {
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
    fun recordDefeat(id: Uuid, cause: DefeatCause): Transition<DefeatOutcome> {
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
                loser = loser
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
     * RoundWon 発行時の世代と一致する場合のみガードを解放する。
     * 世代不一致(中断・次ラウンド進行済み等)は no-op。
     */
    fun releaseResolution(epoch: Long): ArenaMatch =
        if (this.epoch == epoch) copy(resolving = false) else this

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
