package net.ninebolt.ketto.application

import net.ninebolt.ketto.application.fixtures.TestApp
import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.ArenaState
import net.ninebolt.ketto.domain.BlockPosition
import net.ninebolt.ketto.domain.fixtures.arenaId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaSignServiceTest {

    @Test
    fun `sign lifecycle`() {
        val app = TestApp()
        app.newArena()
        val sign = BlockPosition.new("world", 3, 64, 3)
        assertTrue(app.signService.setSign(arenaId("arena1"), sign))
        assertEquals(sign, app.arenaRepository.findSignLocation(arenaId("arena1")))
        assertEquals(arenaId("arena1"), app.signService.findSignOwner(BlockPosition.new("world", 3, 64, 3)))
        assertEquals(
            Triple(app.sessions.findArena(arenaId("arena1"))!!, sign, ArenaState.Kind.WAITING),
            app.presentation.signUpdates.last(),
        )

        assertFalse(app.signService.setSign(arenaId("missing"), sign))
        assertTrue(app.signService.clearSign(arenaId("arena1")))
        assertNull(app.arenaRepository.findSignLocation(arenaId("arena1")))
        assertFalse(app.signService.clearSign(arenaId("missing")))
    }

    @Test
    fun `enable refreshes the sign with the enabled arena`() {
        val app = TestApp()
        app.newArena("arena1", enabled = false)
        val sign = BlockPosition.new("world", 3, 64, 3)
        assertTrue(app.signService.setSign(arenaId("arena1"), sign))
        val (arenaAtRegistration) = app.presentation.signUpdates.last()
        assertIs<Arena.Disabled>(arenaAtRegistration)

        assertNull(app.administration.enable("arena1"))

        val (arenaAfterEnable, position, state) = app.presentation.signUpdates.last()
        assertIs<Arena.Enabled>(arenaAfterEnable)
        assertEquals(sign, position)
        assertEquals(ArenaState.Kind.WAITING, state)
    }

    @Test
    fun `refreshing with a superseded match does not write the sign`() {
        val app = TestApp()
        app.newArena()
        val sign = BlockPosition.new("world", 3, 64, 3)
        app.signService.setSign(arenaId("arena1"), sign)
        val stale = app.sessions.findMatch(arenaId("arena1"))!!

        val p1 = app.players.add("Alice")
        app.participation.join(p1.id, p1.name, arenaId("arena1"))
        val writes = app.presentation.signUpdates.size

        app.signService.refreshSign(stale)

        assertEquals(writes, app.presentation.signUpdates.size)
    }
}
