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

class ArenaRegistryTest {

    private fun ArenaRegistry.install(name: String) = installArena(
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
        val registry = ArenaRegistry(3)
        registry.install("a1")
        registry.install("a2")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.OneMore(p)))
        registry.putMatch(match("a2", ArenaState.OneMore(p)))
        assertEquals(arenaId("a2"), registry.arenaOf(p.id))

        registry.putMatch(match("a1", ArenaState.Waiting))
        assertEquals(arenaId("a2"), registry.arenaOf(p.id))
        assertTrue(registry.isJoined(p.id))
    }

    @Test
    fun `rejected transition leaves the match untouched`() {
        val registry = ArenaRegistry(3)
        registry.install("a1")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.OneMore(p)))

        val rejected = registry.transact(arenaId("a1")) { it.join(p) }
        assertEquals(JoinOutcome.Rejected, rejected?.outcome)
        assertEquals(listOf(p), registry.match(arenaId("a1"))!!.participants)
    }

    @Test
    fun `reads on unknown ids and names return null`() {
        val registry = ArenaRegistry(3)
        val id = arenaId("nope")
        assertNull(registry.arena(id))
        assertNull(registry.match(id))
        assertNull(registry.entry(id))
        assertNull(registry.resolveArenaId("nope"))
        assertNull(registry.resolveArena("nope"))
        assertNull(registry.resolveEntry("nope"))
        assertNull(registry.arenaOf(Uuid.random()))
        assertFalse(registry.isJoined(Uuid.random()))
    }

    @Test
    fun `mutators on an unknown arena are no-ops`() {
        val registry = ArenaRegistry(3)
        val id = arenaId("nope")
        registry.removeArena(id)
        registry.replaceArena(Arena.Disabled.new(id))
        registry.putMatch(match("nope", ArenaState.Waiting))
        assertNull(registry.updateMatch(id) { it })
        assertNull(registry.transact(id) { it.abort() })
        assertTrue(registry.arenaIds().isEmpty())
    }

    @Test
    fun `putting the identical match instance is a no-op`() {
        val registry = ArenaRegistry(3)
        registry.install("a1")
        val current = registry.match(arenaId("a1"))!!
        registry.putMatch(current)
        assertSame(current, registry.match(arenaId("a1")))
    }

    @Test
    fun `reinstalling an arena resets the match and drops the participant index`() {
        val registry = ArenaRegistry(3)
        registry.install("a1")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.OneMore(p)))

        registry.install("a1")

        assertFalse(registry.isJoined(p.id))
        assertEquals(ArenaState.Kind.WAITING, registry.match(arenaId("a1"))!!.state.kind)
    }

    @Test
    fun `removing an arena unregisters its participants`() {
        val registry = ArenaRegistry(3)
        registry.install("a1")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.OneMore(p)))

        registry.removeArena(arenaId("a1"))

        assertNull(registry.arena(arenaId("a1")))
        assertFalse(registry.isJoined(p.id))
        assertNull(registry.arenaOf(p.id))
    }
}
