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
data class BackupRef private constructor(
    val backupId: Uuid,
    val matchId: MatchId,
    val playerId: Uuid?,
    val playerName: String
) {
    companion object {
        /** 新規バックアップ。backupId は内部で発番する。 */
        fun new(matchId: MatchId, playerId: Uuid?, playerName: String): BackupRef =
            BackupRef(Uuid.random(), matchId, playerId, playerName)

        /** players.yml 等からの再構築。 */
        fun restored(backupId: Uuid, matchId: MatchId, playerId: Uuid?, playerName: String): BackupRef =
            BackupRef(backupId, matchId, playerId, playerName)
    }
}
