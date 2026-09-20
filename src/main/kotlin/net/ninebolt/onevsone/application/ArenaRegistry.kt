package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Transition
import kotlin.uuid.Uuid

/**
 * アリーナ定義・試合集約・UUID→アリーナ索引の共有レジストリ。
 * ArenaApplicationService と ArenaAdministrationService が共有し、
 * アリーナをまたぐ二重参加禁止は playerArena 索引が担う。
 *
 * map は公開しない。playerArena 索引は match 書き戻し
 * (installMatch/transact/updateMatch/removeMatch)のたびに参加者差分から
 * 追従させ、playerArena[uuid]=id ⟺ uuid ∈ matches[id].participants の
 * 不変条件を構造で維持する。ArenaMatch は immutable なので変更は必ず
 * これらのメソッドで置き換える。
 */
class ArenaRegistry {
    private val definitions = linkedMapOf<ArenaId, ArenaDefinition>()
    private val matches = linkedMapOf<ArenaId, ArenaMatch>()
    private val playerArena = mutableMapOf<Uuid, ArenaId>()

    // ---- アリーナ定義 -----------------------------------------------------

    fun definition(id: ArenaId): ArenaDefinition? = definitions[id]

    /** 登録順のアリーナ ID 一覧。 */
    fun definitionIds(): List<ArenaId> = definitions.keys.toList()

    fun putDefinition(definition: ArenaDefinition) {
        definitions[definition.id] = definition
    }

    fun removeDefinition(id: ArenaId) {
        definitions.remove(id)
    }

    // ---- 試合集約 ---------------------------------------------------------

    fun match(id: ArenaId): ArenaMatch? = matches[id]

    /** 登録順の全試合(シャットダウン処理用)。 */
    fun matches(): List<ArenaMatch> = matches.values.toList()

    /** 新しい試合状態で置き換える(新規登録・ immutable 集約の書き戻し)。 */
    fun installMatch(match: ArenaMatch) {
        val previous = matches.put(match.arenaId, match)
        reconcileIndex(match.arenaId, previous, match)
    }

    fun removeMatch(id: ArenaId) {
        val previous = matches.remove(id)
        reconcileIndex(id, previous, null)
    }

    /**
     * match を変換して書き戻す。アリーナが無ければ何もせず null。
     */
    fun updateMatch(id: ArenaId, transform: (ArenaMatch) -> ArenaMatch): ArenaMatch? {
        val current = matches[id] ?: return null
        val next = transform(current)
        matches[id] = next
        reconcileIndex(id, current, next)
        return next
    }

    /**
     * match の操作を適用して書き戻し、outcome を返す。アリーナが無ければ null。
     * 「計算→コミット→結果に応じたオーケストレーション」のコミットを一元化する。
     */
    fun <O> transact(id: ArenaId, operation: (ArenaMatch) -> Transition<O>): Transition<O>? {
        val current = matches[id] ?: return null
        val transition = operation(current)
        matches[id] = transition.match
        reconcileIndex(id, current, transition.match)
        return transition
    }

    // ---- 参加索引 ----------------------------------------------------------

    fun arenaOf(playerId: Uuid): ArenaId? = playerArena[playerId]

    fun isJoined(playerId: Uuid): Boolean = playerId in playerArena

    /**
     * 書き戻し前後の参加者差分を索引へ反映する。
     * 参加側の上書きは試合在籍をそのまま写すだけでよく、退出側は
     * このアリーナを指しているエントリのみ外す(他アリーナ参加と混ざらないよう)。
     */
    private fun reconcileIndex(id: ArenaId, before: ArenaMatch?, after: ArenaMatch?) {
        val beforeIds = before?.participants?.map { it.id }?.toSet() ?: emptySet()
        val afterIds = after?.participants?.map { it.id }?.toSet() ?: emptySet()
        for (playerId in beforeIds - afterIds) {
            if (playerArena[playerId] == id) playerArena.remove(playerId)
        }
        for (playerId in afterIds - beforeIds) {
            playerArena[playerId] = id
        }
    }
}
