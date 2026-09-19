package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Transition
import java.util.UUID

/**
 * アリーナ定義・試合集約・UUID→アリーナ索引の共有レジストリ。
 * ArenaApplicationService と ArenaAdministrationService が共有し、
 * アリーナをまたぐ二重参加禁止は playerArena 索引が担う。
 *
 * map は公開しない: playerArena[uuid]=id ⟺ uuid ∈ matches[id].participants の
 * 不変条件を守るため、索引の更新は assign/unassign 経由に限定する。
 * ArenaMatch は immutable なので変更は必ず installMatch/transact/updateMatch で
 * 置き換える。
 */
class ArenaRegistry {
    private val definitions = LinkedHashMap<ArenaId, ArenaDefinition>()
    private val matches = LinkedHashMap<ArenaId, ArenaMatch>()
    private val playerArena = mutableMapOf<UUID, ArenaId>()

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
        matches[match.arenaId] = match
    }

    fun removeMatch(id: ArenaId) {
        matches.remove(id)
    }

    /**
     * match を変換して書き戻す。アリーナが無ければ何もせず null。
     */
    fun updateMatch(id: ArenaId, transform: (ArenaMatch) -> ArenaMatch): ArenaMatch? {
        val current = matches[id] ?: return null
        return transform(current).also { matches[id] = it }
    }

    /**
     * match の操作を適用して書き戻し、outcome を返す。アリーナが無ければ null。
     * 「計算→コミット→結果に応じたオーケストレーション」のコミットを一元化する。
     */
    fun <O> transact(id: ArenaId, operation: (ArenaMatch) -> Transition<O>): Transition<O>? {
        val current = matches[id] ?: return null
        return operation(current).also { matches[id] = it.match }
    }

    // ---- 参加索引 ----------------------------------------------------------

    fun arenaOf(playerId: UUID): ArenaId? = playerArena[playerId]

    fun isJoined(playerId: UUID): Boolean = playerArena.containsKey(playerId)

    fun assign(playerId: UUID, arena: ArenaId) {
        playerArena[playerId] = arena
    }

    fun unassign(playerId: UUID) {
        playerArena.remove(playerId)
    }
}
