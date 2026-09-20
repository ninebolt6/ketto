package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.warnOnFailure
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/**
 * コミット済み試合の外部反映。status 永続化・看板更新・players.yml 台帳解除の
 * イディオムをここに固定する。台帳解除の失敗は warn に潰し後続処理を止めない。
 */
class MatchStateSync(
    private val matchState: MatchStateRepository,
    private val presentation: MatchPresentationPort,
    private val failures: FailureReporter
) {
    /** 試合状態を status ファイルへ保存し、参加看板を最新状態にする。 */
    fun publish(match: ArenaMatch) {
        matchState.saveStatus(match)
        presentation.updateSign(match.arenaId, match.state)
    }

    /** players.yml の参加登録を解除する。 */
    fun unregister(participant: Participant) {
        failures.warnOnFailure("Could not unregister ${participant.name} from players.yml; membership record may be stale") {
            matchState.unregisterParticipant(participant.name)
        }
    }

    /** 未復元バックアップ保持者の参加登録を解除する(復元記録はインメモリに保持)。 */
    fun unregisterKeepingRestore(participant: Participant) {
        failures.warnOnFailure("Could not unregister ${participant.name} from players.yml; pending restore retained in memory") {
            matchState.unregisterParticipant(participant.name)
        }
    }
}
