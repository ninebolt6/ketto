package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/**
 * Admission check for entity-caused damage. When either the victim's or the
 * attacker's participation state restricts damage, the damage is allowed only
 * if "the victim's match is INGAME-equivalent (opponentDamageOnly) and the
 * attacker is the same-match opponent or the victim themself".
 * Environmental damage is out of scope (it is not entity-caused, so it never
 * reaches here).
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
