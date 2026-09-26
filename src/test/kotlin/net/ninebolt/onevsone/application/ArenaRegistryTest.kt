package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.JoinOutcome
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import org.junit.jupiter.api.Test
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ArenaRegistryTest {

    private val logger = Logger.getLogger("test")

    private fun ArenaRegistry.install(name: String) = installArena(
        Arena.Enabled.restored(
            arenaId(name),
            WorldPosition.new("world", 1.0, 64.0, 1.0),
            WorldPosition.new("world", 2.0, 64.0, 2.0),
        ),
        persist = {},
    )

    private fun match(name: String, state: ArenaState) = ArenaMatch.restored(
        arenaId(name),
        3,
        state,
    )

    @Test
    fun `stale removal diff does not unregister a player who moved arenas`() {
        val registry = ArenaRegistry(3, logger)
        registry.install("a1")
        registry.install("a2")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.OneMore(p)), persist = {})
        registry.putMatch(match("a2", ArenaState.OneMore(p)), persist = {})
        assertEquals(arenaId("a2"), registry.arenaOf(p.id))

        registry.putMatch(match("a1", ArenaState.Waiting), persist = {})
        assertEquals(arenaId("a2"), registry.arenaOf(p.id))
        assertTrue(registry.isJoined(p.id))
    }

    @Test
    fun `rejected transition skips the persist hook`() {
        val registry = ArenaRegistry(3, logger)
        registry.install("a1")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.OneMore(p)), persist = {})

        var persists = 0
        val rejected = registry.transact(arenaId("a1"), persist = { persists++ }) { it.join(p) }
        assertEquals(JoinOutcome.Rejected, rejected?.outcome)
        assertEquals(0, persists)

        registry.transact(arenaId("a1"), persist = { persists++ }) { it.join(Participant.new("Bob")) }
        assertEquals(1, persists)
    }

    @Test
    fun `reads on unknown ids and names return null`() {
        val registry = ArenaRegistry(3, logger)
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
        val registry = ArenaRegistry(3, logger)
        val id = arenaId("nope")
        var persists = 0
        registry.removeArena(id, persist = { persists++ })
        assertNull(registry.updateArena(id, persist = { persists++ }) { it })
        registry.putMatch(match("nope", ArenaState.Waiting), persist = { persists++ })
        assertNull(registry.updateMatch(id, persist = { persists++ }) { it })
        assertNull(registry.transact(id, persist = { persists++ }) { it.abort() })
        assertEquals(0, persists)
        assertTrue(registry.arenaIds().isEmpty())
    }

    @Test
    fun `putting the identical match instance skips the persist hook`() {
        val registry = ArenaRegistry(3, logger)
        registry.install("a1")
        val current = registry.match(arenaId("a1"))!!
        var persists = 0
        registry.putMatch(current, persist = { persists++ })
        assertEquals(0, persists)
    }

    @Test
    fun `reinstalling an arena resets the match and drops the participant index`() {
        val registry = ArenaRegistry(3, logger)
        registry.install("a1")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.OneMore(p)), persist = {})

        registry.install("a1")

        assertFalse(registry.isJoined(p.id))
        assertEquals(ArenaState.Kind.WAITING, registry.match(arenaId("a1"))!!.state.kind)
    }

    @Test
    fun `removing an arena unregisters its participants`() {
        val registry = ArenaRegistry(3, logger)
        registry.install("a1")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.OneMore(p)), persist = {})

        registry.removeArena(arenaId("a1"), persist = {})

        assertNull(registry.arena(arenaId("a1")))
        assertFalse(registry.isJoined(p.id))
        assertNull(registry.arenaOf(p.id))
    }

    @Test
    fun `persist failure leaves registry untouched`() {
        val registry = ArenaRegistry(3, logger)
        assertFailsWith<PersistenceFailure> {
            registry.installArena(Arena.Disabled.new(arenaId("a1"))) { throw PersistenceFailure("disk") }
        }
        assertNull(registry.arena(arenaId("a1")))
        assertTrue(registry.arenaIds().isEmpty())
    }
}
