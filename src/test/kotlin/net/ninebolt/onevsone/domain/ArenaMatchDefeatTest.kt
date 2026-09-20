package net.ninebolt.onevsone.domain

import net.ninebolt.onevsone.domain.fixtures.alice
import net.ninebolt.onevsone.domain.fixtures.bob
import net.ninebolt.onevsone.domain.fixtures.carol
import net.ninebolt.onevsone.domain.fixtures.match
import net.ninebolt.onevsone.domain.fixtures.startedMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 敗北通知・ラウンド遷移・解決ガード(世代トークン)の検証。 */
class ArenaMatchDefeatTest {

    @Test
    fun `nonfinal defeat awards round and enters ROUNDCOUNTDOWN`() {
        val m = startedMatch()
        val step = m.recordDefeat(bob.id, DefeatCause.FALL)
        assertTrue(step.outcome is DefeatOutcome.RoundWon)
        val outcome = step.outcome as DefeatOutcome.RoundWon
        assertEquals(1, outcome.round)
        assertEquals(alice, outcome.winner)
        assertEquals(bob, outcome.loser)
        assertEquals(step.match.token, outcome.resolution)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, step.match.state)
        assertTrue(step.match.resolving)
        assertEquals(1, step.match.winsOf(alice.id))
    }

    @Test
    fun `requiredWins 3 ends on third defeat without counting final kill`() {
        var m = startedMatch()
        m = m.recordDefeat(bob.id, DefeatCause.FALL).match
        m = m.releaseResolution(m.token)
        m = m.resumeRound().match
        m = m.recordDefeat(bob.id, DefeatCause.FALL).match
        m = m.releaseResolution(m.token)
        m = m.resumeRound().match
        val step = m.recordDefeat(bob.id, DefeatCause.FALL)
        assertTrue(step.outcome is DefeatOutcome.MatchFinished)
        assertEquals(ArenaState.WAITING, step.match.state)
        assertTrue(step.match.wins.isEmpty())
        assertTrue(step.match.participants.isEmpty())
    }

    @Test
    fun `requiredWins 1 ends on first defeat`() {
        val m = startedMatch(requiredWins = 1)
        val step = m.recordDefeat(bob.id, DefeatCause.DEATH)
        assertTrue(step.outcome is DefeatOutcome.MatchFinished)
        assertEquals(alice, (step.outcome as DefeatOutcome.MatchFinished).winner)
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
                m = step.match.releaseResolution(step.match.token)
                val resumed = m.resumeRound()
                assertTrue(resumed.outcome)
                m = resumed.match
            } else {
                m = step.match
            }
        }
        assertTrue(outcome is DefeatOutcome.MatchFinished)
        assertEquals(alice, (outcome as DefeatOutcome.MatchFinished).winner)
    }

    @Test
    fun `environmental death awards opponent`() {
        val m = startedMatch()
        val step = m.recordDefeat(bob.id, DefeatCause.DEATH)
        assertTrue(step.outcome is DefeatOutcome.RoundWon)
        assertEquals(alice, (step.outcome as DefeatOutcome.RoundWon).winner)
    }

    @Test
    fun `fall allowed in ROUNDCOUNTDOWN but death is not`() {
        var m = startedMatch()
        var step = m.recordDefeat(bob.id, DefeatCause.FALL)
        m = step.match.releaseResolution(step.match.token)
        // ROUNDCOUNTDOWN 中: 落下は受理、死亡は拒否
        step = m.recordDefeat(bob.id, DefeatCause.FALL)
        assertTrue(step.outcome is DefeatOutcome.RoundWon)
        m = step.match.releaseResolution(step.match.token)
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(alice.id, DefeatCause.DEATH).outcome)
    }

    @Test
    fun `duplicate defeat notification while resolving is rejected`() {
        var m = startedMatch()
        val step = m.recordDefeat(bob.id, DefeatCause.DEATH)
        assertTrue(step.outcome is DefeatOutcome.RoundWon)
        m = step.match
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(bob.id, DefeatCause.DEATH).outcome)
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(bob.id, DefeatCause.FALL).outcome)
        assertEquals(1, m.winsOf(alice.id))
    }

    @Test
    fun `releaseResolution with stale token is a no-op`() {
        var m = startedMatch()
        val step = m.recordDefeat(bob.id, DefeatCause.FALL)
        assertTrue(step.outcome is DefeatOutcome.RoundWon)
        m = step.match
        assertTrue(m.resolving)
        // 世代が進んだ後の解放要求は無効(古いトークン)
        val after = ArenaMatch.restored(
            m.arenaId, m.requiredWins, m.state, m.participants, m.wins,
            resolving = m.resolving, epoch = m.epoch + 1
        )
        assertSame(after, after.releaseResolution(m.token))
        assertTrue(after.resolving)
        // 正しいトークンなら解放される
        assertFalse(m.releaseResolution(m.token).resolving)
    }

    @Test
    fun `resumeRound returns to INGAME and releases resolution`() {
        var m = startedMatch()
        m = m.recordDefeat(bob.id, DefeatCause.FALL).match
        val resumed = m.resumeRound()
        assertTrue(resumed.outcome)
        m = resumed.match
        assertEquals(ArenaState.INGAME, m.state)
        // 解決ガードが解放されているので次の敗北も受理される
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
