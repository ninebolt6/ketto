package net.ninebolt.onevsone.domain

/**
 * 参加者へ適用する制約をアリーナ状態から導出する純粋な規則。
 * リスナー側ではイベント変換だけを行い、状態ごとの可否判定はここに集約する。
 */
data class ParticipantRestrictions(
    val horizontalMoveFrozen: Boolean,
    val damageCancelled: Boolean,
    /** エンティティ起因ダメージを「同一マッチの対戦相手または本人」由来に限定する */
    val opponentDamageOnly: Boolean,
    /** テレポートの可否。エンダーパール以外の逃走経路を塞ぐ */
    val teleportRestriction: TeleportRestriction,
    val blockBreakCancelled: Boolean,
    /** 破壊禁止中は設置物を撤去できないため、アリーナの汚染と籠城を防ぐ */
    val blockPlaceCancelled: Boolean,
    /** 装備交換後はインベントリが開始時バックアップで上書きされるため、持ち出しを防ぐ */
    val itemDropCancelled: Boolean,
    /** コンテナ・額縁・防具立て・取引など、インベントリ⇄外界の移動を遮断する */
    val inventoryTransferCancelled: Boolean,
    /** 拾得・収穫・矢回収・ディスペンサー装備などの直接獲得を遮断する */
    val itemPickupCancelled: Boolean,
    /** ONEMORE 待機中のみコマンドを許可する */
    val commandsBlocked: Boolean
) {
    companion object {
        fun forState(state: ArenaState): ParticipantRestrictions = when (state) {
            ArenaState.ROUNDCOUNTDOWN -> ParticipantRestrictions(
                horizontalMoveFrozen = true,
                damageCancelled = true,
                opponentDamageOnly = false,
                teleportRestriction = TeleportRestriction.PLUGIN_ONLY,
                blockBreakCancelled = true,
                blockPlaceCancelled = true,
                itemDropCancelled = true,
                inventoryTransferCancelled = true,
                itemPickupCancelled = true,
                commandsBlocked = true
            )
            ArenaState.INGAME -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                opponentDamageOnly = true,
                teleportRestriction = TeleportRestriction.ENDER_PEARL_ONLY,
                blockBreakCancelled = true,
                blockPlaceCancelled = true,
                itemDropCancelled = true,
                inventoryTransferCancelled = true,
                itemPickupCancelled = true,
                commandsBlocked = true
            )
            ArenaState.COUNTDOWN -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                opponentDamageOnly = false,
                teleportRestriction = TeleportRestriction.ENDER_PEARL_ONLY,
                blockBreakCancelled = false,
                blockPlaceCancelled = false,
                itemDropCancelled = false,
                inventoryTransferCancelled = false,
                itemPickupCancelled = false,
                commandsBlocked = true
            )
            // WAITING / ONEMORE: 制約なし。WAITING で参加者が存在する経路は無いが、
            // 「ONEMORE 以外はコマンド禁止」のため ONEMORE のみ許可。
            ArenaState.ONEMORE -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                opponentDamageOnly = false,
                teleportRestriction = TeleportRestriction.UNRESTRICTED,
                blockBreakCancelled = false,
                blockPlaceCancelled = false,
                itemDropCancelled = false,
                inventoryTransferCancelled = false,
                itemPickupCancelled = false,
                commandsBlocked = false
            )
            ArenaState.WAITING -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                opponentDamageOnly = false,
                teleportRestriction = TeleportRestriction.UNRESTRICTED,
                blockBreakCancelled = false,
                blockPlaceCancelled = false,
                itemDropCancelled = false,
                inventoryTransferCancelled = false,
                itemPickupCancelled = false,
                commandsBlocked = true
            )
        }
    }
}
