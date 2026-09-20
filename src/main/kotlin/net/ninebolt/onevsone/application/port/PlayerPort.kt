package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

/**
 * オンライン(または切断処理中)のプレイヤーへの限定操作。
 * 装備と表示は含めない。実体参照は返さず、UUID で引いて生存/位置を確認し、
 * 試合状態への変更・テレポート・リスポーンを要求する。
 */
interface PlayerPort {
    /**
     * プレイヤーの操作ハンドル。オンラインのプレイヤー、およびアダプターが
     * QuitEvent 処理中として登録した切断中プレイヤーを返す。それ以外は null。
     */
    fun handle(playerId: Uuid): PlayerHandle?
}

interface PlayerHandle {
    val id: Uuid
    val name: String
    val online: Boolean
    val dead: Boolean
    fun position(): WorldPosition?
    /** 死亡中なら即時リスポーン。 */
    fun respawn()
    /** 燃焼 0・体力全快・満腹度 20。死亡中は何もしない。 */
    fun resetVitals()
    /** SURVIVAL・飛行不可 + resetVitals。 */
    fun prepareForMatch()
    /** ワールド未ロード等で失敗した場合はアダプター側が警告する。 */
    fun teleport(position: WorldPosition)
}
