package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import java.util.UUID

/**
 * 試合進行の表示・演出。文字列整形や Adventure は Paper 側。
 * ユーザーへの直接応答(参加可否等)はユースケース結果として返し、ここには含めない。
 */
interface MatchPresentationPort {
    /** 初回カウントダウン: 「テレポートまで: N秒」+ 音(ピッチ1)。 */
    fun countdownTick(participantIds: List<UUID>, secondsLeft: Int)
    /** ラウンド間カウントダウン: 「開始まで: N秒」+ 音(ピッチ1)。 */
    fun roundCountdownTick(participantIds: List<UUID>, secondsLeft: Int)
    /** マッチ開始: 「ゲームスタート！」+ 音(ピッチ2)。 */
    fun matchStart(participantIds: List<UUID>)
    /** ラウンド再開: 「スタート！」+ 音(ピッチ2)。 */
    fun roundStart(participantIds: List<UUID>)
    /** ラウンド決着: 「ラウンド[N] 勝者: name」を両者へ。 */
    fun roundWon(participantIds: List<UUID>, round: Int, winnerName: String)
    /** ラウンド決着の爆発音(敗北地点)。 */
    fun roundEndSound(position: WorldPosition)
    /** 優勝ブロードキャスト。 */
    fun champion(arena: ArenaId, winnerName: String)
    /** 勝者位置の花火。 */
    fun championFirework(playerId: UUID)
    /** サイドバースコアボードを最新の試合状態で更新。 */
    fun updateScoreboard(match: ArenaMatch)
    /** スコアボードをクリア。 */
    fun clearScoreboard(playerId: UUID)
    /** 看板の表示更新(Join 可否 + 状態行)。 */
    fun updateSign(arena: ArenaId, state: ArenaState)
}
