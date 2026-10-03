package net.ninebolt.onevsone.domain

import net.ninebolt.onevsone.domain.fixtures.alice
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.domain.fixtures.bob
import net.ninebolt.onevsone.domain.fixtures.carol
import net.ninebolt.onevsone.domain.fixtures.dave
import net.ninebolt.onevsone.domain.fixtures.match
import net.ninebolt.onevsone.domain.fixtures.startedMatch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DamageAdmissionTest {

    private val ingame = startedMatch()
    private val waiting = match().join(alice).match

    @Test
    fun `both unrestricted passes through`() {
        assertTrue(DamageAdmission.allows(side(alice, waiting), null))
        assertTrue(DamageAdmission.allows(null, null))
    }

    @Test
    fun `opponent or self allowed while ingame`() {
        assertTrue(DamageAdmission.allows(side(alice, ingame), side(bob, ingame)))
        assertTrue(DamageAdmission.allows(side(alice, ingame), side(alice, ingame)))
    }

    @Test
    fun `third party and mob damage denied while ingame`() {
        assertFalse(DamageAdmission.allows(side(alice, ingame), null))
    }

    @Test
    fun `participant cannot damage outsiders or mobs`() {
        assertFalse(DamageAdmission.allows(null, side(alice, ingame)))
    }

    @Test
    fun `everything denied while damage cancelled`() {
        val roundCountdown = ingame.recordDefeat(bob.id, DefeatCause.FALL).match
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, roundCountdown.state.kind)
        assertFalse(DamageAdmission.allows(side(alice, roundCountdown), side(bob, roundCountdown)))
        assertFalse(DamageAdmission.allows(side(alice, roundCountdown), side(alice, roundCountdown)))
    }

    @Test
    fun `non player victim does not bypass attacker restriction`() {
        assertFalse(DamageAdmission.allows(null, side(alice, ingame)))
    }

    @Test
    fun `waiting and countdown participants do not restrict damage`() {
        assertTrue(DamageAdmission.allows(side(alice, match()), side(bob, match())))
        val countdown = waiting.join(bob).match
        assertEquals(ArenaState.Kind.COUNTDOWN, countdown.state.kind)
        assertTrue(DamageAdmission.allows(side(alice, countdown), side(bob, countdown)))
    }

    @Test
    fun `participants of different matches cannot hurt each other`() {
        val joined = ArenaMatch.new(arenaId("arena2"), 3).join(carol).match.join(dave).match
        val began = joined.beginMatch()
        check(began.outcome)
        val other = began.match
        assertEquals(ArenaState.Kind.INGAME, other.state.kind)
        assertTrue(DamageAdmission.allows(side(carol, other), side(dave, other)))
        assertFalse(DamageAdmission.allows(side(alice, ingame), side(carol, other)))
        assertFalse(DamageAdmission.allows(side(carol, other), side(alice, ingame)))
    }

    private fun side(participant: Participant, match: ArenaMatch) = DamageAdmission.Side(participant.id, match)
}
