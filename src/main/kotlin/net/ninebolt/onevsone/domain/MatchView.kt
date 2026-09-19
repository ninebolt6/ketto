package net.ninebolt.onevsone.domain

import java.util.UUID

/**
 * ArenaMatch の読み取り専用スナップショット。
 * 内部の mutable 状態を外部へ公開しないために、コピーだけを返す。
 */
data class MatchView(
    val arenaId: ArenaId,
    val state: ArenaState,
    val participants: List<Participant>,
    val wins: Map<UUID, Int>
) {
    val joinable: Boolean get() = state.isJoinable()

    /** Y<=0 落下を敗北として解決するか(INGAME/ROUNDCOUNTDOWN かつ 2 人在籍)。 */
    val resolvesVoidFall: Boolean
        get() = (state == ArenaState.INGAME || state == ArenaState.ROUNDCOUNTDOWN) &&
            participants.size == ArenaMatch.MAX_PARTICIPANTS

    fun winsOf(id: UUID): Int = wins[id] ?: 0
}
