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
    data object NotFound : JoinReply
}
