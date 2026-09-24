package net.ninebolt.onevsone.domain

import net.ninebolt.onevsone.domain.fixtures.alice
import net.ninebolt.onevsone.domain.fixtures.bob
import net.ninebolt.onevsone.domain.fixtures.carol
import net.ninebolt.onevsone.domain.fixtures.dave
import net.ninebolt.onevsone.domain.fixtures.match
import net.ninebolt.onevsone.domain.fixtures.startedMatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DamageAdmissionTest {

    private val ingame = startedMatch()
    private val waiting = match().join(alice).match

    @Test
    fun `both unrestricted passes through`() {
        assertTrue(DamageAdmission.allows(alice.id, bob.id, waiting, null))
        assertTrue(DamageAdmission.allows(carol.id, carol.id, null, null))
    }

    @Test
    fun `opponent or self allowed while ingame`() {
        assertTrue(DamageAdmission.allows(alice.id, bob.id, ingame, ingame))
        assertTrue(DamageAdmission.allows(alice.id, alice.id, ingame, ingame))
    }

    @Test
    fun `third party and mob damage denied while ingame`() {
        assertFalse(DamageAdmission.allows(alice.id, carol.id, ingame, null))
        assertFalse(DamageAdmission.allows(alice.id, null, ingame, null))
    }

    @Test
    fun `participant cannot damage outsiders or mobs`() {
        assertFalse(DamageAdmission.allows(carol.id, alice.id, null, ingame))
        assertFalse(DamageAdmission.allows(null, alice.id, null, ingame))
    }

    @Test
    fun `everything denied while damage cancelled`() {
        val roundCountdown = ingame.recordDefeat(bob.id, DefeatCause.FALL).match
        assertEquals(ArenaState.ROUNDCOUNTDOWN, roundCountdown.state)
        assertFalse(DamageAdmission.allows(alice.id, bob.id, roundCountdown, roundCountdown))
        assertFalse(DamageAdmission.allows(alice.id, alice.id, roundCountdown, roundCountdown))
    }

    @Test
    fun `non player victim does not bypass attacker restriction`() {
        assertFalse(DamageAdmission.allows(null, alice.id, null, ingame))
    }

    @Test
    fun `participants of different matches cannot hurt each other`() {
        val joined = ArenaMatch.new(Arena.Id.new("arena2"), 3).join(carol).match.join(dave).match
        val began = joined.beginMatch()
        check(began.outcome)
        val other = began.match
        assertEquals(ArenaState.INGAME, other.state)
        assertTrue(DamageAdmission.allows(carol.id, dave.id, other, other))
        assertFalse(DamageAdmission.allows(alice.id, carol.id, ingame, other))
        assertFalse(DamageAdmission.allows(carol.id, alice.id, other, ingame))
    }
}
