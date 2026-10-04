package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.JoinOutcome
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ArenaSessionsTest {

    private fun ArenaSessions.install(name: String) = installArena(
        Arena.Enabled.restored(
            arenaId(name),
            WorldPosition.new("world", 1.0, 64.0, 1.0),
            WorldPosition.new("world", 2.0, 64.0, 2.0),
        ),
    )

    private fun match(name: String, state: ArenaState) = ArenaMatch.restored(
        arenaId(name),
        3,
        state,
    )

    @Test
    fun `stale removal diff does not unregister a player who moved arenas`() {
        val sessions = ArenaSessions(3)
        sessions.install("a1")
        sessions.install("a2")
        val p = Participant.new("Alice")
        sessions.putMatch(match("a1", ArenaState.OneMore(p)))
        sessions.putMatch(match("a2", ArenaState.OneMore(p)))
        assertEquals(arenaId("a2"), sessions.arenaIdOf(p.id))

        sessions.putMatch(match("a1", ArenaState.Waiting))
        assertEquals(arenaId("a2"), sessions.arenaIdOf(p.id))
        assertTrue(sessions.isJoined(p.id))
    }

    @Test
    fun `rejected transition leaves the match untouched`() {
        val sessions = ArenaSessions(3)
        sessions.install("a1")
        val p = Participant.new("Alice")
        sessions.putMatch(match("a1", ArenaState.OneMore(p)))

        val rejected = sessions.transact(arenaId("a1")) { it.join(p) }
        assertEquals(JoinOutcome.Rejected, rejected?.outcome)
        assertEquals(listOf(p), sessions.match(arenaId("a1"))!!.participants)
    }

    @Test
    fun `reads on unknown ids and names return null`() {
        val sessions = ArenaSessions(3)
        val id = arenaId("nope")
        assertNull(sessions.arena(id))
        assertNull(sessions.match(id))
        assertNull(sessions.entry(id))
        assertNull(sessions.resolveArenaId("nope"))
        assertNull(sessions.resolveArena("nope"))
        assertNull(sessions.enabledArena(id))
        assertNull(sessions.arenaIdOf(Uuid.random()))
        assertFalse(sessions.isJoined(Uuid.random()))
    }

    @Test
    fun `resolveArenaId tolerates names that are not valid arena ids`() {
        val sessions = ArenaSessions(3)
        sessions.install("a1")
        assertNull(sessions.resolveArenaId("create"))
        assertNull(sessions.resolveArenaId("  "))
    }

    @Test
    fun `resolveArenaId falls back to a case-insensitive match`() {
        val sessions = ArenaSessions(3)
        sessions.install("a1")
        assertEquals(arenaId("a1"), sessions.resolveArenaId("A1"))
        assertEquals(arenaId("a1"), sessions.resolveArena("A1")?.id)
    }

    @Test
    fun `arenaIdOf returns null for a player outside every match`() {
        val sessions = ArenaSessions(3)
        sessions.install("a1")
        assertNull(sessions.arenaIdOf(Uuid.random()))
        val p = Participant.new("Alice")
        sessions.putMatch(match("a1", ArenaState.OneMore(p)))
        assertNull(sessions.arenaIdOf(Uuid.random()))
        assertFalse(sessions.isJoined(Uuid.random()))
        assertEquals(arenaId("a1"), sessions.arenaIdOf(Uuid.parse(p.id.toString())))
    }

    @Test
    fun `enabledArena returns null for a disabled arena`() {
        val sessions = ArenaSessions(3)
        val id = arenaId("a1")
        sessions.installArena(Arena.Disabled.new(id))
        assertNull(sessions.enabledArena(id))
    }

    @Test
    fun `mutators on an unknown arena are no-ops`() {
        val sessions = ArenaSessions(3)
        val id = arenaId("nope")
        sessions.removeArena(id)
        sessions.replaceArena(Arena.Disabled.new(id))
        sessions.putMatch(match("nope", ArenaState.Waiting))
        assertNull(sessions.transact(id) { it.abort() })
        assertTrue(sessions.arenaIds().isEmpty())
    }

    @Test
    fun `putting the identical match instance is a no-op`() {
        val sessions = ArenaSessions(3)
        sessions.install("a1")
        val current = sessions.match(arenaId("a1"))!!
        sessions.putMatch(current)
        assertSame(current, sessions.match(arenaId("a1")))
    }

    @Test
    fun `reinstalling an arena resets the match and drops the participant index`() {
        val sessions = ArenaSessions(3)
        sessions.install("a1")
        val p = Participant.new("Alice")
        sessions.putMatch(match("a1", ArenaState.OneMore(p)))

        sessions.install("a1")

        assertFalse(sessions.isJoined(p.id))
        assertEquals(ArenaState.Kind.WAITING, sessions.match(arenaId("a1"))!!.state.kind)
    }

    @Test
    fun `removing an arena unregisters its participants`() {
        val sessions = ArenaSessions(3)
        sessions.install("a1")
        val p = Participant.new("Alice")
        sessions.putMatch(match("a1", ArenaState.OneMore(p)))

        sessions.removeArena(arenaId("a1"))

        assertNull(sessions.arena(arenaId("a1")))
        assertFalse(sessions.isJoined(p.id))
        assertNull(sessions.arenaIdOf(p.id))
    }
}
