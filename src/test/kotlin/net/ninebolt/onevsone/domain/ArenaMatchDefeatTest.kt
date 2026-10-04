package net.ninebolt.onevsone.domain

import net.ninebolt.onevsone.domain.fixtures.alice
import net.ninebolt.onevsone.domain.fixtures.bob
import net.ninebolt.onevsone.domain.fixtures.carol
import net.ninebolt.onevsone.domain.fixtures.match
import net.ninebolt.onevsone.domain.fixtures.startedMatch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArenaMatchDefeatTest {

    @Test
    fun `nonfinal defeat awards round and enters ROUNDCOUNTDOWN`() {
        val m = startedMatch()
        val step = m.recordDefeat(bob.id, DefeatCause.FALL)
        assertTrue(step.outcome is DefeatOutcome.RoundWon)
        val outcome = step.outcome
        assertEquals(1, outcome.round)
        assertEquals(alice, outcome.winner.participant)
        assertEquals(bob, outcome.loser.participant)
        assertEquals(m.epoch + 1, step.match.epoch)
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, step.match.state.kind)
        assertEquals(1, step.match.winsOf(alice.id))
    }

    @Test
    fun `round won carries spawn slots for either winner`() {
        val firstWins = startedMatch().recordDefeat(bob.id, DefeatCause.FALL).outcome as DefeatOutcome.RoundWon
        val secondWins = startedMatch().recordDefeat(alice.id, DefeatCause.FALL).outcome as DefeatOutcome.RoundWon

        assertEquals(SpawnSlot.FIRST, firstWins.winner.slot)
        assertEquals(SpawnSlot.SECOND, firstWins.loser.slot)
        assertEquals(SpawnSlot.SECOND, secondWins.winner.slot)
        assertEquals(SpawnSlot.FIRST, secondWins.loser.slot)
    }

    @Test
    fun `requiredWins 3 ends on third defeat without counting final kill`() {
        var m = startedMatch()
        m = m.recordDefeat(bob.id, DefeatCause.FALL).match
        m = m.resumeRound().match
        m = m.recordDefeat(bob.id, DefeatCause.FALL).match
        m = m.resumeRound().match
        val step = m.recordDefeat(bob.id, DefeatCause.FALL)
        assertTrue(step.outcome is DefeatOutcome.MatchEnded)
        assertEquals(ArenaState.Kind.WAITING, step.match.state.kind)
        assertTrue(step.match.participants.isEmpty())
    }

    @Test
    fun `requiredWins 1 ends on first defeat`() {
        val m = startedMatch(requiredWins = 1)
        val step = m.recordDefeat(bob.id, DefeatCause.DEATH)
        assertTrue(step.outcome is DefeatOutcome.MatchEnded)
        assertEquals(alice, step.outcome.winner)
    }

    @Test
    fun `alternating winners reach match end at fifth round`() {
        var m = startedMatch()
        val sequence = listOf(bob.id, bob.id, alice.id, alice.id, bob.id)
        var outcome: DefeatOutcome = DefeatOutcome.Rejected
        sequence.withIndex().forEach { (i, loser) ->
            val step = m.recordDefeat(loser, DefeatCause.FALL)
            outcome = step.outcome
            if (i < 4) {
                assertTrue(outcome is DefeatOutcome.RoundWon)
                val resumed = step.match.resumeRound()
                assertTrue(resumed.outcome)
                m = resumed.match
            } else {
                m = step.match
            }
        }
        assertTrue(outcome is DefeatOutcome.MatchEnded)
        assertEquals(alice, outcome.winner)
    }

    @Test
    fun `environmental death awards opponent`() {
        val m = startedMatch()
        val step = m.recordDefeat(bob.id, DefeatCause.DEATH)
        assertTrue(step.outcome is DefeatOutcome.RoundWon)
        assertEquals(alice, step.outcome.winner.participant)
    }

    @Test
    fun `each fall in ROUNDCOUNTDOWN scores but death is not`() {
        var m = startedMatch()
        var step = m.recordDefeat(bob.id, DefeatCause.FALL)
        m = step.match
        step = m.recordDefeat(bob.id, DefeatCause.FALL)
        assertTrue(step.outcome is DefeatOutcome.RoundWon)
        m = step.match
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(alice.id, DefeatCause.DEATH).outcome)
    }

    @Test
    fun `death in ROUNDCOUNTDOWN is rejected`() {
        val m = startedMatch()
        val step = m.recordDefeat(bob.id, DefeatCause.DEATH)
        assertTrue(step.outcome is DefeatOutcome.RoundWon)
        val rejected = step.match.recordDefeat(bob.id, DefeatCause.DEATH)
        assertEquals(DefeatOutcome.Rejected, rejected.outcome)
        assertSame(step.match, rejected.match)
        assertEquals(1, step.match.winsOf(alice.id))
    }

    @Test
    fun `resumeRound returns to INGAME`() {
        var m = startedMatch()
        m = m.recordDefeat(bob.id, DefeatCause.FALL).match
        val resumed = m.resumeRound()
        assertTrue(resumed.outcome)
        m = resumed.match
        assertEquals(ArenaState.Kind.INGAME, m.state.kind)
        assertTrue(m.recordDefeat(bob.id, DefeatCause.DEATH).outcome is DefeatOutcome.RoundWon)
    }

    @Test
    fun `resumeRound only from ROUNDCOUNTDOWN`() {
        val m = startedMatch()
        assertFalse(m.resumeRound().outcome)
    }

    @Test
    fun `defeat rejected when not ingame or alone`() {
        var m = match()
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(alice.id, DefeatCause.DEATH).outcome)
        m = m.join(alice).match
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(alice.id, DefeatCause.DEATH).outcome)
        m = m.join(bob).match
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(alice.id, DefeatCause.DEATH).outcome)
    }

    @Test
    fun `defeat by non participant rejected`() {
        val m = startedMatch()
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(carol.id, DefeatCause.DEATH).outcome)
    }
}
