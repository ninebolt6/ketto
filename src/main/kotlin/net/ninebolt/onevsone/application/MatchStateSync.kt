package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/**
 * コミット済み試合の外部反映。status 永続化・看板更新・players.yml 台帳解除をまとめる。
 * 失敗は PersistenceFailure として呼び出し側へ伝播する(潰すかどうかは文脈依存)。
 */
class MatchStateSync(
    private val matchState: MatchStateRepository,
    private val presentation: MatchPresentationPort
) {
    /** 試合状態を status ファイルへ保存し、参加看板を最新状態にする。 */
    fun publish(match: ArenaMatch) {
        matchState.saveStatus(match)
        presentation.updateSign(match.arenaId, match.state)
    }

    /** players.yml の参加登録を解除する。 */
    fun unregister(participant: Participant) {
        matchState.unregisterParticipant(participant.name)
    }
}
