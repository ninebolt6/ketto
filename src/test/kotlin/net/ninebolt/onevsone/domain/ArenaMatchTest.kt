package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ArenaMatchTest {

    private val arenaId = ArenaId("arena1")
    private val alice = Participant(UUID.randomUUID(), "Alice")
    private val bob = Participant(UUID.randomUUID(), "Bob")
    private val carol = Participant(UUID.randomUUID(), "Carol")

    private fun match(requiredWins: Int = 3) = ArenaMatch(arenaId, requiredWins)

    private fun startedMatch(requiredWins: Int = 3): ArenaMatch {
        val m = match(requiredWins)
        m.join(alice)
        m.join(bob)
        check(m.beginMatch())
        return m
    }

    @Test
    fun `join transitions WAITING to ONEMORE to COUNTDOWN`() {
        val m = match()
        assertEquals(ArenaState.WAITING, m.state())
        assertEquals(JoinOutcome.FirstJoined, m.join(alice))
        assertEquals(ArenaState.ONEMORE, m.state())
        assertEquals(JoinOutcome.MatchReady, m.join(bob))
        assertEquals(ArenaState.COUNTDOWN, m.state())
    }

    @Test
    fun `join rejects third player and duplicate`() {
        val m = match()
        m.join(alice)
        m.join(bob)
        assertEquals(JoinOutcome.Rejected, m.join(carol))
        assertEquals(JoinOutcome.Rejected, m.join(alice))
        assertEquals(2, m.participantCount())
    }

    @Test
    fun `join rejected while ingame`() {
        val m = startedMatch()
        assertEquals(JoinOutcome.Rejected, m.join(carol))
    }

    @Test
    fun `leaveWaiting only allowed while ONEMORE`() {
        val m = match()
        m.join(alice)
        m.join(bob)
        // COUNTDOWN では退出不可
        assertEquals(LeaveOutcome.NotWaiting, m.leaveWaiting(alice.id))
        assertEquals(2, m.participantCount())

        val waiting = match()
        waiting.join(alice)
        val outcome = waiting.leaveWaiting(alice.id)
        assertTrue(outcome is LeaveOutcome.Left)
        assertEquals(alice, (outcome as LeaveOutcome.Left).participant)
        assertEquals(ArenaState.WAITING, waiting.state())
        assertEquals(0, waiting.participantCount())
    }

    @Test
    fun `beginMatch requires COUNTDOWN with two participants`() {
        val m = match()
        assertFalse(m.beginMatch())
        m.join(alice)
        assertFalse(m.beginMatch())
        m.join(bob)
        assertTrue(m.beginMatch())
        assertEquals(ArenaState.INGAME, m.state())
        assertFalse(m.beginMatch())
    }

    @Test
    fun `nonfinal defeat awards round and enters ROUNDCOUNTDOWN`() {
        val m = startedMatch()
        val outcome = m.recordDefeat(bob.id, DefeatCause.FALL)
        assertTrue(outcome is DefeatOutcome.RoundWon)
        outcome as DefeatOutcome.RoundWon
        assertEquals(1, outcome.round)
        assertEquals(alice, outcome.winner)
        assertEquals(bob, outcome.loser)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, m.state())
        assertEquals(1, m.view().winsOf(alice.id))
    }

    @Test
    fun `requiredWins 3 ends on third defeat without counting final kill`() {
        val m = startedMatch()
        m.recordDefeat(bob.id, DefeatCause.FALL)
        m.releaseResolution()
        m.resumeRound()
        m.recordDefeat(bob.id, DefeatCause.FALL)
        m.releaseResolution()
        m.resumeRound()
        val outcome = m.recordDefeat(bob.id, DefeatCause.FALL)
        assertTrue(outcome is DefeatOutcome.MatchFinished)
        assertEquals(ArenaState.WAITING, m.state())
        assertTrue(m.view().wins.isEmpty())
        assertTrue(m.view().participants.isEmpty())
    }

    @Test
    fun `requiredWins 1 ends on first defeat`() {
        val m = startedMatch(requiredWins = 1)
        val outcome = m.recordDefeat(bob.id, DefeatCause.DEATH)
        assertTrue(outcome is DefeatOutcome.MatchFinished)
        assertEquals(alice, (outcome as DefeatOutcome.MatchFinished).winner)
    }

    @Test
    fun `alternating winners reach match end at fifth round`() {
        val m = startedMatch()
        val sequence = listOf(bob.id, bob.id, alice.id, alice.id, bob.id)
        var outcome: DefeatOutcome = DefeatOutcome.Rejected
        for ((i, loser) in sequence.withIndex()) {
            outcome = m.recordDefeat(loser, DefeatCause.FALL)
            if (i < 4) {
                assertTrue(outcome is DefeatOutcome.RoundWon)
                m.releaseResolution()
                assertTrue(m.resumeRound())
            }
        }
        assertTrue(outcome is DefeatOutcome.MatchFinished)
        assertEquals(alice, (outcome as DefeatOutcome.MatchFinished).winner)
    }

    @Test
    fun `environmental death awards opponent`() {
        val m = startedMatch()
        val outcome = m.recordDefeat(bob.id, DefeatCause.DEATH)
        assertTrue(outcome is DefeatOutcome.RoundWon)
        assertEquals(alice, (outcome as DefeatOutcome.RoundWon).winner)
    }

    @Test
    fun `fall allowed in ROUNDCOUNTDOWN but death is not`() {
        val m = startedMatch()
        m.recordDefeat(bob.id, DefeatCause.FALL)
        m.releaseResolution()
        // ROUNDCOUNTDOWN 中: 落下は受理、死亡は拒否
        assertTrue(m.recordDefeat(bob.id, DefeatCause.FALL) is DefeatOutcome.RoundWon)
        m.releaseResolution()
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(alice.id, DefeatCause.DEATH))
    }

    @Test
    fun `duplicate defeat notification while resolving is rejected`() {
        val m = startedMatch()
        assertTrue(m.recordDefeat(bob.id, DefeatCause.DEATH) is DefeatOutcome.RoundWon)
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(bob.id, DefeatCause.DEATH))
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(bob.id, DefeatCause.FALL))
        assertEquals(1, m.view().winsOf(alice.id))
    }

    @Test
    fun `resumeRound returns to INGAME and releases resolution`() {
        val m = startedMatch()
        m.recordDefeat(bob.id, DefeatCause.FALL)
        assertFalse(m.resumeRound().not())
        assertEquals(ArenaState.INGAME, m.state())
        // 解決ガードが解放されているので次の敗北も受理される
        assertTrue(m.recordDefeat(bob.id, DefeatCause.DEATH) is DefeatOutcome.RoundWon)
    }

    @Test
    fun `resumeRound only from ROUNDCOUNTDOWN`() {
        val m = startedMatch()
        assertFalse(m.resumeRound())
    }

    @Test
    fun `defeat rejected when not ingame or alone`() {
        val m = match()
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(alice.id, DefeatCause.DEATH))
        m.join(alice)
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(alice.id, DefeatCause.DEATH))
        m.join(bob)
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(alice.id, DefeatCause.DEATH))
    }

    @Test
    fun `defeat by non participant rejected`() {
        val m = startedMatch()
        assertEquals(DefeatOutcome.Rejected, m.recordDefeat(carol.id, DefeatCause.DEATH))
    }

    @Test
    fun `forfeit while waiting exits without match end`() {
        val m = match()
        m.join(alice)
        val outcome = m.forfeit(alice.id)
        assertTrue(outcome is QuitOutcome.WaitingExit)
        assertEquals(ArenaState.WAITING, m.state())
        assertEquals(0, m.participantCount())
        assertEquals(QuitOutcome.NotParticipant, m.forfeit(alice.id))
    }

    @Test
    fun `forfeit during countdown or ingame ends match for opponent`() {
        val countdown = match()
        countdown.join(alice)
        countdown.join(bob)
        val outcome = countdown.forfeit(alice.id)
        assertTrue(outcome is QuitOutcome.MatchEnded)
        assertEquals(bob, (outcome as QuitOutcome.MatchEnded).winner)
        assertEquals(alice, outcome.loser)
        assertEquals(ArenaState.WAITING, countdown.state())

        val ingame = startedMatch()
        assertTrue(ingame.forfeit(alice.id) is QuitOutcome.MatchEnded)
        assertEquals(0, ingame.participantCount())
    }

    @Test
    fun `abort clears state and returns participants`() {
        val m = startedMatch()
        val left = m.abort()
        assertEquals(listOf(alice, bob), left)
        assertEquals(ArenaState.WAITING, m.state())
        assertEquals(0, m.participantCount())
        assertTrue(m.view().wins.isEmpty())
    }

    @Test
    fun `token advances on transitions and abort`() {
        val m = match()
        val t0 = m.token()
        m.join(alice)
        m.join(bob)
        m.beginMatch()
        assertEquals(t0, m.token())
        m.recordDefeat(bob.id, DefeatCause.FALL)
        val t1 = m.token()
        assertNotEquals(t0, t1)
        m.abort()
        assertNotEquals(t1, m.token())
    }

    @Test
    fun `view exposes copies that cannot mutate aggregate`() {
        val m = startedMatch()
        val view = m.view()
        assertEquals(ArenaState.INGAME, view.state)
        assertEquals(listOf(alice, bob), view.participants)
        // view のコレクションを書き換えても集約には影響しない
        @Suppress("UNCHECKED_CAST")
        (view.wins as? MutableMap<UUID, Int>)?.set(alice.id, 99)
        assertEquals(0, m.view().winsOf(alice.id))
    }

    @Test
    fun `restrictions matrix matches arena states`() {
        val m = match()
        val matrix = mapOf(
            ArenaState.ONEMORE to ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                blockBreakCancelled = false,
                commandsBlocked = false
            ),
            ArenaState.COUNTDOWN to ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                blockBreakCancelled = false,
                commandsBlocked = true
            ),
            ArenaState.ROUNDCOUNTDOWN to ParticipantRestrictions(
                horizontalMoveFrozen = true,
                damageCancelled = true,
                blockBreakCancelled = true,
                commandsBlocked = true
            ),
            ArenaState.INGAME to ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                blockBreakCancelled = true,
                commandsBlocked = true
            )
        )
        for ((state, expected) in matrix) {
            assertEquals(expected, ParticipantRestrictions.forState(state), "state=$state")
        }
        assertNull(m.participant(alice.id))
    }
}
