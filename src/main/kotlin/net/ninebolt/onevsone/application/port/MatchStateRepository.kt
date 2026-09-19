package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/**
 * 外部参照用の試合状態・参加者台帳。immutable な集約スナップショットを保存する。
 */
interface MatchStateRepository {
    /** status/<arena>.yml へ状態・参加者名・勝数を書き出す。 */
    fun saveStatus(match: ArenaMatch)
    /** players.yml へ参加登録(メンバーシップのみ。持ち物は含めない)。 */
    fun registerParticipant(participant: Participant, arena: ArenaId)
    /** players.yml から参加登録を解除。バックアップ(inv.*)は消さない。 */
    fun unregisterParticipant(playerName: String)
    /** 起動時に前回の中途登録をリセット(バックアップ inv.* は保持)。 */
    fun clearRegistrations()
}
