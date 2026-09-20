package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.MatchId
import kotlin.uuid.Uuid

/**
 * 開始直前に取得したインベントリバックアップの識別子。
 * 実データ(ItemStack 由来)は port の外側(infrastructure)に閉じ込め、
 * 内部層はこのメタデータだけを受け渡す。
 *
 * playerId は uuid 未記録のバックアップでは null になり得る。
 */
data class BackupRef(
    val backupId: Uuid,
    val matchId: MatchId,
    val playerId: Uuid?,
    val playerName: String
)
