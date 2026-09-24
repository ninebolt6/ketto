package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

// Only entity-caused damage reaches here; environmental damage is filtered out upstream.
object DamageAdmission {

    fun allows(
        victimId: Uuid?,
        attackerId: Uuid?,
        victimMatch: ArenaMatch?,
        attackerMatch: ArenaMatch?,
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
