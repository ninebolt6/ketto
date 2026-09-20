package net.ninebolt.onevsone.domain

sealed interface QuitOutcome {
    data object NotParticipant : QuitOutcome
    /** 未開始(ONEMORE/WAITING/COUNTDOWN/人数不足)の退出: 登録解除のみ */
    data class WaitingExit(val participant: Participant) : QuitOutcome
    /** 試合進行中の切断: 不戦敗としてマッチ終了 */
    data class MatchEnded(val winner: Participant, val loser: Participant) : QuitOutcome
}
