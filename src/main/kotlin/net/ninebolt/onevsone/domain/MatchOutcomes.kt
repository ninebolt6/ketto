package net.ninebolt.onevsone.domain

/**
 * 試合/カウントダウンの世代トークン。中断・再参加で古い scheduler コールバックを
 * 無効化するために使う。バックアップ/復元のトークン(BackupRef 相当)とは別系統。
 */
data class MatchToken(val epoch: Long)

/** 敗北の通知経路。落下(非死亡)は ROUNDCOUNTDOWN 中も受理される。 */
enum class DefeatCause { DEATH, FALL }

/**
 * ArenaMatch の操作結果。match は遷移後の新しい状態(拒否時は変化なしの同一インスタンス)。
 * 呼び出し側は outcome を見てから match をレジストリへ書き戻す。
 */
data class Transition<out O>(val match: ArenaMatch, val outcome: O)

sealed interface JoinOutcome {
    /** 1 人目: ONEMORE へ遷移し待機 */
    data object FirstJoined : JoinOutcome
    /** 2 人目: COUNTDOWN へ遷移し初回カウントダウンが必要 */
    data object MatchReady : JoinOutcome
    /** 参加拒否(試合中/満員/重複)。enabled 判定は application 側。 */
    data object Rejected : JoinOutcome
}

sealed interface LeaveOutcome {
    /** participant は退出者(参加者不一致の到達不能ケースでは null)。 */
    data class Left(val participant: Participant?) : LeaveOutcome
    /** ONEMORE 以外では退出できない */
    data object NotWaiting : LeaveOutcome
}

sealed interface QuitOutcome {
    data object NotParticipant : QuitOutcome
    /** 未開始(ONEMORE/WAITING/人数不足)の退出: 登録解除のみ */
    data class WaitingExit(val participant: Participant) : QuitOutcome
    /** 試合進行中の切断: 不戦敗としてマッチ終了 */
    data class MatchEnded(val winner: Participant, val loser: Participant) : QuitOutcome
}

sealed interface DefeatOutcome {
    /** 通常の拒否(状態不適・人数不足・解決中の重複通知) */
    data object Rejected : DefeatOutcome
    /** ラウンドのみ決着。round は終了したラウンド番号(合計勝数)。
     *  resolution は解決ガード解放用の世代トークン。 */
    data class RoundWon(
        val round: Int,
        val winner: Participant,
        val loser: Participant,
        val resolution: MatchToken
    ) : DefeatOutcome
    /** 規定勝数に到達してマッチ終了。最終キルは勝数に加算しない現挙動を維持。 */
    data class MatchFinished(val winner: Participant, val loser: Participant) : DefeatOutcome
}
