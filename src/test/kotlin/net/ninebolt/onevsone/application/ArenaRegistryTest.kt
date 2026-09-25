package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.JoinOutcome
import net.ninebolt.onevsone.domain.Participant
import org.junit.jupiter.api.Test
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaRegistryTest {

    private val logger = Logger.getLogger("test")

    private fun ArenaRegistry.install(name: String) = installArena(Arena.new(Arena.Id.new(name), enabled = true), persist = {})

    private fun match(name: String, state: ArenaState, participants: List<Participant>) = ArenaMatch.restored(
        Arena.Id.new(name),
        3,
        state,
        participants,
        emptyMap(),
    )

    @Test
    fun `stale removal diff does not unregister a player who moved arenas`() {
        val registry = ArenaRegistry(3, logger)
        registry.install("a1")
        registry.install("a2")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.ONEMORE, listOf(p)), persist = {})
        registry.putMatch(match("a2", ArenaState.ONEMORE, listOf(p)), persist = {})
        assertEquals(Arena.Id.new("a2"), registry.arenaOf(p.id))

        registry.putMatch(match("a1", ArenaState.WAITING, emptyList()), persist = {})
        assertEquals(Arena.Id.new("a2"), registry.arenaOf(p.id))
        assertTrue(registry.isJoined(p.id))
    }

    @Test
    fun `rejected transition skips the persist hook`() {
        val registry = ArenaRegistry(3, logger)
        registry.install("a1")
        val p = Participant.new("Alice")
        registry.putMatch(match("a1", ArenaState.ONEMORE, listOf(p)), persist = {})

        var persists = 0
        val rejected = registry.transact(Arena.Id.new("a1"), persist = { persists++ }) { it.join(p) }
        assertEquals(JoinOutcome.Rejected, rejected?.outcome)
        assertEquals(0, persists)

        registry.transact(Arena.Id.new("a1"), persist = { persists++ }) { it.join(Participant.new("Bob")) }
        assertEquals(1, persists)
    }

    @Test
    fun `persist failure leaves registry untouched`() {
        val registry = ArenaRegistry(3, logger)
        assertFailsWith<PersistenceFailure> {
            registry.installArena(Arena.new(Arena.Id.new("a1"))) { throw PersistenceFailure("disk") }
        }
        assertNull(registry.arena(Arena.Id.new("a1")))
        assertTrue(registry.arenaIds().isEmpty())
    }
}
