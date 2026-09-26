package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

// Only entity-caused damage reaches here; environmental damage is filtered out upstream.
object DamageAdmission {

    // A player bound to the match they are in; players without a match are treated like non-players
    data class Side(val id: Uuid, val match: ArenaMatch)

    fun allows(victim: Side?, attacker: Side?): Boolean {
        fun limits(side: Side?) = side?.match?.let {
            ParticipantRestrictions.forState(it.state).let { r -> r.damageCancelled || r.opponentDamageOnly }
        } == true

        if (!limits(victim) && !limits(attacker)) return true
        if (victim == null || attacker == null) return false
        if (!ParticipantRestrictions.forState(victim.match.state).opponentDamageOnly) return false
        return attacker.id == victim.id ||
            victim.match.participants.any { it.id == attacker.id && it.id != victim.id }
    }
}
