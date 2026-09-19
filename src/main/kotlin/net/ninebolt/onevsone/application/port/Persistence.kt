package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.MatchView
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.domain.WorldPosition
import java.util.UUID

/**
 * 永続化・外部参照処理の失敗。破損ファイルや I/O エラーを示す。
 * 通常のユーザー向け拒否(参加不可等)はこれではなくユースケース結果で表す。
 */
class PersistenceFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * アリーナ定義とロビー/看板座標の永続化。
 * 装備(キット)の中身は扱わず、アリーナ ID 経由で PlayerEquipmentPort が触れる。
 */
interface ArenaRepository {
    /** arenalist 上の名前一覧(無効名を含み得る。呼び出し側が検証する)。 */
    fun arenaNames(): List<String>
    fun saveArenaNames(names: List<String>)
    /** 無効名は null。ファイル欠損は PersistenceFailure。未作成ファイルは既定値の定義を返す。 */
    fun find(name: String): ArenaDefinition?
    fun save(arena: ArenaDefinition)
    fun delete(name: String)

    fun lobby(): WorldPosition?
    fun setLobby(position: WorldPosition)

    fun signLocation(arenaName: String): WorldPosition?
    fun setSign(arenaName: String, position: WorldPosition)
    fun clearSign(arenaName: String)
    fun signOwner(world: String, x: Double, y: Double, z: Double): String?
}

/**
 * 外部参照用の試合状態・参加者台帳。集約ではなく純粋 DTO を保存する。
 */
interface MatchStateRepository {
    /** status/<arena>.yml へ状態・参加者名・勝数を書き出す。 */
    fun saveStatus(view: MatchView)
    /** players.yml へ参加登録(メンバーシップのみ。持ち物は含めない)。 */
    fun registerParticipant(participant: Participant, arena: ArenaId)
    /** players.yml から参加登録を解除。バックアップ(inv.*)は消さない。 */
    fun unregisterParticipant(playerName: String)
    /** 起動時に前回の中途登録をリセット(バックアップ inv.* は保持)。 */
    fun clearRegistrations()
}

/**
 * 戦績の永続化。ファイル不存在は null、破損・I/O は PersistenceFailure。
 */
interface PlayerStatsRepository {
    fun find(playerId: UUID): PlayerStats?
    fun recordWin(playerId: UUID)
    fun recordLoss(playerId: UUID)
}
