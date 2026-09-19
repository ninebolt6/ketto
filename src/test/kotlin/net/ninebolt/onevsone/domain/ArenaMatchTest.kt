package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
        var m = match(requiredWins)
        m = m.join(alice).match
        m = m.join(bob).match
        val began = m.beginMatch()
        check(began.outcome)
        return began.match
    }

    @Test
    fun `join transitions WAITING to ONEMORE to COUNTDOWN`() {
        val m0 = match()
        assertEquals(ArenaState.WAITING, m0.state)
        val s1 = m0.join(alice)
        assertEquals(JoinOutcome.FirstJoined, s1.outcome)
        assertEquals(ArenaState.ONEMORE, s1.match.state)
        val s2 = s1.match.join(bob)
        assertEquals(JoinOutcome.MatchReady, s2.outcome)
        assertEquals(ArenaState.COUNTDOWN, s2.match.state)
    }

    @Test
    fun `join rejects third player and duplicate`() {
        var m = match()
        m = m.join(alice).match
        m = m.join(bob).match
        assertEquals(JoinOutcome.Rejected, m.join(carol).outcome)
        assertEquals(JoinOutcome.Rejected, m.join(alice).outcome)
        assertEquals(2, m.participants.size)
    }

    @Test
    fun `rejected join returns the same instance`() {
        val m = match().join(alice).match
        val dup = m.join(alice)
        assertEquals(JoinOutcome.Rejected, dup.outcome)
        assertSame(m, dup.match)
    }

    @Test
    fun `join rejected while ingame`() {
        val m = startedMatch()
        assertEquals(JoinOutcome.Rejected, m.join(carol).outcome)
    }

    @Test
    fun `leaveWaiting only allowed while ONEMORE`() {
        var m = match()
        m = m.join(alice).match
        m = m.join(bob).match
        // COUNTDOWN では退出不可
        assertEquals(LeaveOutcome.NotWaiting, m.leaveWaiting(alice.id).outcome)
        assertEquals(2, m.participants.size)

        var waiting = match()
        waiting = waiting.join(alice).match
        val step = waiting.leaveWaiting(alice.id)
        assertTrue(step.outcome is LeaveOutcome.Left)
        assertEquals(alice, (step.outcome as LeaveOutcome.Left).participant)
        assertEquals(ArenaState.WAITING, step.match.state)
        assertEquals(0, step.match.participants.size)
    }

    @Test
    fun `beginMatch requires COUNTDOWN with two participants`() {
        var m = match()
        assertFalse(m.beginMatch().outcome)
        m = m.join(alice).match
        assertFalse(m.beginMatch().outcome)
        m = m.join(bob).match
        val began = m.beginMatch()
        assertTrue(began.outcome)
        assertEquals(ArenaState.INGAME, began.match.state)
        assertFalse(began.match.beginMatch().outcome)
    }

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
        for ((i, loser) in sequence.withIndex()) {
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
        val after = m.copy(epoch = m.epoch + 1)
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

    @Test
    fun `forfeit while waiting exits without match end`() {
        var m = match()
        m = m.join(alice).match
        val step = m.forfeit(alice.id)
        assertTrue(step.outcome is QuitOutcome.WaitingExit)
        assertEquals(ArenaState.WAITING, step.match.state)
        assertEquals(0, step.match.participants.size)
        assertEquals(QuitOutcome.NotParticipant, step.match.forfeit(alice.id).outcome)
    }

    @Test
    fun `forfeit during countdown or ingame ends match for opponent`() {
        var countdown = match()
        countdown = countdown.join(alice).match
        countdown = countdown.join(bob).match
        val step = countdown.forfeit(alice.id)
        assertTrue(step.outcome is QuitOutcome.MatchEnded)
        assertEquals(bob, (step.outcome as QuitOutcome.MatchEnded).winner)
        assertEquals(alice, step.outcome.loser)
        assertEquals(ArenaState.WAITING, step.match.state)

        val ingame = startedMatch()
        val finished = ingame.forfeit(alice.id)
        assertTrue(finished.outcome is QuitOutcome.MatchEnded)
        assertEquals(0, finished.match.participants.size)
    }

    @Test
    fun `abort clears state and returns participants`() {
        val m = startedMatch()
        val step = m.abort()
        assertEquals(listOf(alice, bob), step.outcome)
        assertEquals(ArenaState.WAITING, step.match.state)
        assertEquals(0, step.match.participants.size)
        assertTrue(step.match.wins.isEmpty())
    }

    @Test
    fun `token advances on transitions and abort`() {
        var m = match()
        val t0 = m.token
        m = m.join(alice).match
        m = m.join(bob).match
        m = m.beginMatch().match
        assertEquals(t0, m.token)
        m = m.recordDefeat(bob.id, DefeatCause.FALL).match
        val t1 = m.token
        assertNotEquals(t0, t1)
        m = m.abort().match
        assertNotEquals(t1, m.token)
    }

    @Test
    fun `held snapshot is not mutated by later transitions`() {
        val m = startedMatch()
        val snapshot = m
        val after = m.recordDefeat(bob.id, DefeatCause.FALL).match
        // 取得済みスナップショットは後続遷移の影響を受けない
        assertEquals(ArenaState.INGAME, snapshot.state)
        assertEquals(0, snapshot.winsOf(alice.id))
        assertEquals(2, snapshot.participants.size)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, after.state)
        assertEquals(1, after.winsOf(alice.id))
    }

    @Test
    fun `slotOf maps join order to spawn slots`() {
        var m = match()
        m = m.join(alice).match
        m = m.join(bob).match
        assertEquals(0, m.slotOf(alice.id))
        assertEquals(1, m.slotOf(bob.id))
        assertNull(m.slotOf(carol.id))
        assertEquals(alice, m.participantAt(0))
        assertEquals(bob, m.participantAt(1))
        assertNull(m.participantAt(2))
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
