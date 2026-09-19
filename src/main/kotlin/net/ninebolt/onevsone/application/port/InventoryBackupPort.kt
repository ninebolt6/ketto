package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant

/**
 * マッチ開始時に取得するインベントリバックアップの永続化・復元。
 * ItemStack 実データは infrastructure 内に閉じ込め、内部層には BackupRef のみを返す。
 */
interface InventoryBackupPort {
    /**
     * 開始直前に両者の持ち物を複製して一括永続化する。
     * 失敗時は誰の持ち物も変更せず PersistenceFailure を投げる。
     */
    fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef>

    /** バックアップへ復元。復元失敗時はバックアップを残したまま例外を投げる。 */
    fun restore(backup: BackupRef)

    /** 復元完了後に永続レコードを削除。backupId が一致する記録だけを消す。 */
    fun acknowledge(backup: BackupRef)

    /** 前回プロセス等で残った未復元バックアップの識別子一覧。 */
    fun pendingBackups(): List<BackupRef>
}
