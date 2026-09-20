package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Transition
import kotlin.uuid.Uuid

/**
 * アリーナ・試合集約・UUID→アリーナ索引の共有レジストリ。
 * ArenaApplicationService と ArenaAdministrationService が共有し、
 * アリーナをまたぐ二重参加禁止は playerArena 索引が担う。
 *
 * Arena と ArenaMatch は必ず 1:1 で存在し、match.arenaId == arena.id は
 * installArena での構築により保証される(Slot)。map は公開しない。
 * playerArena 索引は match 書き戻し(putMatch/transact/updateMatch)のたびに
 * 参加者差分から追従させ、playerArena[uuid]=id ⟺ uuid ∈ matches[id].participants
 * の不変条件を構造で維持する。ArenaMatch は immutable なので変更は必ず
 * これらのメソッドで置き換える。
 */
class ArenaRegistry(private val requiredWins: Int) {

    /** アリーナとその現在の試合集約のペア。 */
    private data class Slot(val arena: Arena, val match: ArenaMatch)

    private val slots = linkedMapOf<Arena.Id, Slot>()
    private val playerArena = mutableMapOf<Uuid, Arena.Id>()

    // ---- アリーナ ---------------------------------------------------------

    fun arena(id: Arena.Id): Arena? = slots[id]?.arena

    /** 登録順のアリーナ ID 一覧。 */
    fun arenaIds(): List<Arena.Id> = slots.keys.toList()

    /** アリーナを登録し、新規の試合集約を紐付ける。 */
    fun installArena(arena: Arena) {
        val match = ArenaMatch.new(arena.id, requiredWins)
        val previous = slots.put(arena.id, Slot(arena, match))
        reconcileIndex(arena.id, previous?.match, match)
    }

    fun removeArena(id: Arena.Id) {
        val previous = slots.remove(id)
        reconcileIndex(id, previous?.match, null)
    }

    /**
     * アリーナ定義を変換して書き戻す。未登録なら何もせず null。
     */
    fun updateArena(id: Arena.Id, transform: (Arena) -> Arena): Arena? {
        val slot = slots[id] ?: return null
        val next = transform(slot.arena)
        slots[id] = slot.copy(arena = next)
        return next
    }

    // ---- 試合集約 ---------------------------------------------------------

    fun match(id: Arena.Id): ArenaMatch? = slots[id]?.match

    /** 登録順の全試合(シャットダウン処理用)。 */
    fun matches(): List<ArenaMatch> = slots.values.map { it.match }

    /**
     * 事前に計算した遷移結果を書き戻す。join のように
     * 「計算→副作用→コミット」の順序が必要な経路用。未登録なら何もしない。
     */
    fun putMatch(match: ArenaMatch) {
        val slot = slots[match.arenaId] ?: return
        slots[match.arenaId] = slot.copy(match = match)
        reconcileIndex(match.arenaId, slot.match, match)
    }

    /**
     * match を変換して書き戻す。アリーナが無ければ何もせず null。
     */
    fun updateMatch(id: Arena.Id, transform: (ArenaMatch) -> ArenaMatch): ArenaMatch? {
        val slot = slots[id] ?: return null
        val next = transform(slot.match)
        slots[id] = slot.copy(match = next)
        reconcileIndex(id, slot.match, next)
        return next
    }

    /**
     * match の操作を適用して書き戻し、outcome を返す。アリーナが無ければ null。
     * 「計算→コミット→結果に応じたオーケストレーション」のコミットを一元化する。
     */
    fun <O> transact(id: Arena.Id, operation: (ArenaMatch) -> Transition<O>): Transition<O>? {
        val slot = slots[id] ?: return null
        val transition = operation(slot.match)
        slots[id] = slot.copy(match = transition.match)
        reconcileIndex(id, slot.match, transition.match)
        return transition
    }

    // ---- 参加索引 ----------------------------------------------------------

    fun arenaOf(playerId: Uuid): Arena.Id? = playerArena[playerId]

    fun isJoined(playerId: Uuid): Boolean = playerId in playerArena

    /**
     * 書き戻し前後の参加者差分を索引へ反映する。
     * 参加側の上書きは試合在籍をそのまま写すだけでよく、退出側は
     * このアリーナを指しているエントリのみ外す(他アリーナ参加と混ざらないよう)。
     */
    private fun reconcileIndex(id: Arena.Id, before: ArenaMatch?, after: ArenaMatch?) {
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
