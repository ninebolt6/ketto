package net.ninebolt.ketto.domain

import kotlin.uuid.Uuid

// Only entity-caused damage reaches here; environmental damage is filtered out upstream.
object DamageAdmission {

    // A player bound to the match they are in; players without a match are treated like non-players
    data class Side(val id: Uuid, val match: ArenaMatch)

    fun allows(victim: Side?, attacker: Side?): Boolean {
        val victimPolicy = victim?.policy() ?: DamagePolicy.UNRESTRICTED
        val attackerPolicy = attacker?.policy() ?: DamagePolicy.UNRESTRICTED

        if (victimPolicy == DamagePolicy.UNRESTRICTED && attackerPolicy == DamagePolicy.UNRESTRICTED) return true
        if (victim == null || attacker == null) return false
        if (victimPolicy != DamagePolicy.OPPONENT_ONLY) return false
        return attacker.id == victim.id || victim.match.participants.any { it.id == attacker.id }
    }

    private fun Side.policy(): DamagePolicy = ParticipantRestrictions.forState(match.state.kind).damagePolicy
}
