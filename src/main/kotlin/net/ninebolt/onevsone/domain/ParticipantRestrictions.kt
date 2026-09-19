package net.ninebolt.onevsone.domain

/**
 * 参加者へ適用する制約をアリーナ状態から導出する純粋な規則。
 * リスナー側ではイベント変換だけを行い、状態ごとの可否判定はここに集約する。
 */
data class ParticipantRestrictions(
    val horizontalMoveFrozen: Boolean,
    val damageCancelled: Boolean,
    val blockBreakCancelled: Boolean,
    /** ONEMORE 待機中のみコマンドを許可する */
    val commandsBlocked: Boolean
) {
    companion object {
        fun forState(state: ArenaState): ParticipantRestrictions = when (state) {
            ArenaState.ROUNDCOUNTDOWN -> ParticipantRestrictions(
                horizontalMoveFrozen = true,
                damageCancelled = true,
                blockBreakCancelled = true,
                commandsBlocked = true
            )
            ArenaState.INGAME -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                blockBreakCancelled = true,
                commandsBlocked = true
            )
            ArenaState.COUNTDOWN -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                blockBreakCancelled = false,
                commandsBlocked = true
            )
            // WAITING / ONEMORE: 制約なし。WAITING で参加者が存在する経路は無いが、
            // 「ONEMORE 以外はコマンド禁止」のため ONEMORE のみ許可。
            ArenaState.ONEMORE -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                blockBreakCancelled = false,
                commandsBlocked = false
            )
            ArenaState.WAITING -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                blockBreakCancelled = false,
                commandsBlocked = true
            )
        }
    }
}
