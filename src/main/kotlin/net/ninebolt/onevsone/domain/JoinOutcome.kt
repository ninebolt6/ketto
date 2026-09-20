package net.ninebolt.onevsone.domain

sealed interface JoinOutcome {
    /** 1 人目: ONEMORE へ遷移し待機 */
    data object FirstJoined : JoinOutcome
    /** 2 人目: COUNTDOWN へ遷移し初回カウントダウンが必要 */
    data object MatchReady : JoinOutcome
    /** 参加拒否(試合中/満員/重複)。enabled 判定は application 側。 */
    data object Rejected : JoinOutcome
}
