package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/**
 * エンティティ起因ダメージの受理判定。被害者・加害者どちらかの参加状態が
 * ダメージを制限する場合、「被害者のマッチが INGAME 相当(opponentDamageOnly)で
 * 加害者が同一マッチの対戦相手または本人」のときのみ許可する。
 * 環境ダメージは対象外(エンティティ起因でないためここに来ない)。
 */
object DamageAdmission {

    fun allows(
        victimId: Uuid?,
        attackerId: Uuid?,
        victimMatch: ArenaMatch?,
        attackerMatch: ArenaMatch?
    ): Boolean {
        fun limits(match: ArenaMatch?) = match?.let {
            ParticipantRestrictions.forState(it.state).let { r -> r.damageCancelled || r.opponentDamageOnly }
        } == true

        if (!limits(victimMatch) && !limits(attackerMatch)) return true
        if (victimId == null || attackerId == null || victimMatch == null) return false
        if (!ParticipantRestrictions.forState(victimMatch.state).opponentDamageOnly) return false
        return attackerId == victimId ||
            victimMatch.participants.any { it.id == attackerId && it.id != victimId }
    }
}
