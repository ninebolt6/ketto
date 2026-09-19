package net.ninebolt.onevsone.application

/**
 * ユースケースの結果。文言への変換は呼び出し側(infrastructure)が行う。
 */
sealed interface JoinReply {
    /** 1 人目として登録。ONEMORE 待機へ。 */
    data object JoinedWaiting : JoinReply
    /** 2 人目として登録。初回カウントダウンを開始済み。 */
    data object JoinedStarting : JoinReply
    data object AlreadyJoined : JoinReply
    data object NotEnabled : JoinReply
    /** 試合中/満員/未復元バックアップ保持者の死亡中等、参加不能。 */
    data object InMatch : JoinReply
    /** アリーナ自体が存在しない。 */
    data object NotFound : JoinReply
}

sealed interface LeaveReply {
    data object Left : LeaveReply
    /** ONEMORE 以外では退出不可。 */
    data object NotWaiting : LeaveReply
    data object NotJoined : LeaveReply
}

sealed interface ToggleReply {
    data object Changed : ToggleReply
    data object AlreadyEnabled : ToggleReply
    data object AlreadyDisabled : ToggleReply
    data object NotFound : ToggleReply
}
