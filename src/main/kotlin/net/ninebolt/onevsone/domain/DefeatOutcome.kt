package net.ninebolt.onevsone.domain

sealed interface DefeatOutcome {
    /** 通常の拒否(状態不適・人数不足・解決中の重複通知) */
    data object Rejected : DefeatOutcome
    /** ラウンドのみ決着。round は終了したラウンド番号(合計勝数)。
     *  resolution は解決ガード解放用の世代トークン。 */
    data class RoundWon(
        val round: Int,
        val winner: Participant,
        val loser: Participant,
        val resolution: ArenaMatch.Token
    ) : DefeatOutcome
    /** 規定勝数に到達してマッチ終了。最終キルは勝数に加算しない現挙動を維持。 */
    data class MatchFinished(val winner: Participant, val loser: Participant) : DefeatOutcome
}
