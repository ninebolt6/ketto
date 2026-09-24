package net.ninebolt.onevsone.domain

import net.ninebolt.onevsone.domain.fixtures.alice
import net.ninebolt.onevsone.domain.fixtures.bob
import net.ninebolt.onevsone.domain.fixtures.carol
import net.ninebolt.onevsone.domain.fixtures.match
import net.ninebolt.onevsone.domain.fixtures.startedMatch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArenaMatchTest {

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
        assertEquals(LeaveOutcome.NotWaiting, m.leaveWaiting(alice.id).outcome)
        assertEquals(2, m.participants.size)

        var waiting = match()
        waiting = waiting.join(alice).match
        val step = waiting.leaveWaiting(alice.id)
        assertTrue(step.outcome is LeaveOutcome.Left)
        assertEquals(alice, step.outcome.participant)
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
    fun `forfeit during initial countdown unregisters and keeps opponent waiting`() {
        var countdown = match()
        countdown = countdown.join(alice).match
        countdown = countdown.join(bob).match
        val step = countdown.forfeit(alice.id)
        assertTrue(step.outcome is QuitOutcome.WaitingExit)
        assertEquals(alice, step.outcome.participant)
        assertEquals(ArenaState.ONEMORE, step.match.state)
        assertEquals(listOf(bob), step.match.participants)
        assertTrue(step.match.epoch > countdown.epoch)
    }

    @Test
    fun `forfeit during ingame ends match for opponent`() {
        val ingame = startedMatch()
        val finished = ingame.forfeit(alice.id)
        assertTrue(finished.outcome is QuitOutcome.MatchEnded)
        assertEquals(bob, finished.outcome.winner)
        assertEquals(0, finished.match.participants.size)
        assertEquals(ArenaState.WAITING, finished.match.state)
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
    fun `epoch advances on transitions and abort`() {
        var m = match()
        val t0 = m.epoch
        m = m.join(alice).match
        m = m.join(bob).match
        m = m.beginMatch().match
        assertEquals(t0, m.epoch)
        m = m.recordDefeat(bob.id, DefeatCause.FALL).match
        val t1 = m.epoch
        assertNotEquals(t0, t1)
        m = m.abort().match
        assertNotEquals(t1, m.epoch)
    }

    @Test
    fun `held snapshot is not mutated by later transitions`() {
        val snapshot = startedMatch()
        val after = snapshot.recordDefeat(bob.id, DefeatCause.FALL).match
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
        assertEquals(SpawnSlot.FIRST, m.slotOf(alice.id))
        assertEquals(SpawnSlot.SECOND, m.slotOf(bob.id))
        assertNull(m.slotOf(carol.id))
        assertNull(m.participant(carol.id))
        assertEquals(alice, m.participantAt(SpawnSlot.FIRST))
        assertEquals(bob, m.participantAt(SpawnSlot.SECOND))
    }

    @Test
    fun `restrictions matrix matches arena states`() {
        fun assertRestrictions(
            state: ArenaState,
            horizontalMoveFrozen: Boolean,
            damageCancelled: Boolean,
            opponentDamageOnly: Boolean,
            teleportRestriction: TeleportRestriction,
            blockBreakCancelled: Boolean,
            blockPlaceCancelled: Boolean,
            itemDropCancelled: Boolean,
            inventoryTransferCancelled: Boolean,
            itemPickupCancelled: Boolean,
            commandsBlocked: Boolean,
        ) {
            val r = ParticipantRestrictions.forState(state)
            assertEquals(horizontalMoveFrozen, r.horizontalMoveFrozen, "$state.horizontalMoveFrozen")
            assertEquals(damageCancelled, r.damageCancelled, "$state.damageCancelled")
            assertEquals(opponentDamageOnly, r.opponentDamageOnly, "$state.opponentDamageOnly")
            assertEquals(teleportRestriction, r.teleportRestriction, "$state.teleportRestriction")
            assertEquals(blockBreakCancelled, r.blockBreakCancelled, "$state.blockBreakCancelled")
            assertEquals(blockPlaceCancelled, r.blockPlaceCancelled, "$state.blockPlaceCancelled")
            assertEquals(itemDropCancelled, r.itemDropCancelled, "$state.itemDropCancelled")
            assertEquals(
                inventoryTransferCancelled,
                r.inventoryTransferCancelled,
                "$state.inventoryTransferCancelled",
            )
            assertEquals(itemPickupCancelled, r.itemPickupCancelled, "$state.itemPickupCancelled")
            assertEquals(commandsBlocked, r.commandsBlocked, "$state.commandsBlocked")
        }

        assertRestrictions(
            ArenaState.WAITING,
            horizontalMoveFrozen = false,
            damageCancelled = false,
            opponentDamageOnly = false,
            teleportRestriction = TeleportRestriction.UNRESTRICTED,
            blockBreakCancelled = false,
            blockPlaceCancelled = false,
            itemDropCancelled = false,
            inventoryTransferCancelled = false,
            itemPickupCancelled = false,
            commandsBlocked = true,
        )
        assertRestrictions(
            ArenaState.ONEMORE,
            horizontalMoveFrozen = false,
            damageCancelled = false,
            opponentDamageOnly = false,
            teleportRestriction = TeleportRestriction.UNRESTRICTED,
            blockBreakCancelled = false,
            blockPlaceCancelled = false,
            itemDropCancelled = false,
            inventoryTransferCancelled = false,
            itemPickupCancelled = false,
            commandsBlocked = false,
        )
        assertRestrictions(
            ArenaState.COUNTDOWN,
            horizontalMoveFrozen = false,
            damageCancelled = false,
            opponentDamageOnly = false,
            teleportRestriction = TeleportRestriction.ENDER_PEARL_ONLY,
            blockBreakCancelled = false,
            blockPlaceCancelled = false,
            itemDropCancelled = false,
            inventoryTransferCancelled = false,
            itemPickupCancelled = false,
            commandsBlocked = true,
        )
        assertRestrictions(
            ArenaState.ROUNDCOUNTDOWN,
            horizontalMoveFrozen = true,
            damageCancelled = true,
            opponentDamageOnly = false,
            teleportRestriction = TeleportRestriction.PLUGIN_ONLY,
            blockBreakCancelled = true,
            blockPlaceCancelled = true,
            itemDropCancelled = true,
            inventoryTransferCancelled = true,
            itemPickupCancelled = true,
            commandsBlocked = true,
        )
        assertRestrictions(
            ArenaState.INGAME,
            horizontalMoveFrozen = false,
            damageCancelled = false,
            opponentDamageOnly = true,
            teleportRestriction = TeleportRestriction.ENDER_PEARL_ONLY,
            blockBreakCancelled = true,
            blockPlaceCancelled = true,
            itemDropCancelled = true,
            inventoryTransferCancelled = true,
            itemPickupCancelled = true,
            commandsBlocked = true,
        )
    }

    @Test
    fun `factories reject invalid construction`() {
        assertFailsWith<IllegalArgumentException> {
            ArenaMatch.new(Arena.Id.new("a1"), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ArenaMatch.restored(Arena.Id.new("a1"), 3, ArenaState.WAITING, listOf(alice), emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            ArenaMatch.restored(Arena.Id.new("a1"), 3, ArenaState.INGAME, listOf(alice), emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            ArenaMatch.restored(
                Arena.Id.new("a1"),
                3,
                ArenaState.INGAME,
                listOf(alice, bob),
                wins = mapOf(carol.id to 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ArenaMatch.restored(
                Arena.Id.new("a1"),
                3,
                ArenaState.INGAME,
                listOf(alice, bob),
                wins = emptyMap(),
                resolving = true,
            )
        }
    }
}
