package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
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
        assertTrue(app.signs.setSign(arenaId("arena1"), sign))
        assertEquals(sign, app.arenas.signLocation("arena1"))
        assertEquals("arena1", app.signs.signOwner(BlockPosition.new("world", 3, 64, 3)))
        assertEquals(
            Triple(app.registry.arena(arenaId("arena1"))!!, sign, ArenaState.Kind.WAITING),
            app.presentation.signUpdates.last(),
        )

        assertFalse(app.signs.setSign(arenaId("missing"), sign))
        assertTrue(app.signs.clearSign(arenaId("arena1")))
        assertNull(app.arenas.signLocation("arena1"))
        assertFalse(app.signs.clearSign(arenaId("missing")))
    }

    @Test
    fun `enable refreshes the sign with the enabled arena`() {
        val app = TestApp()
        app.newArena("arena1", enabled = false)
        val sign = BlockPosition.new("world", 3, 64, 3)
        assertTrue(app.signs.setSign(arenaId("arena1"), sign))
        val (arenaAtRegistration) = app.presentation.signUpdates.last()
        assertIs<Arena.Disabled>(arenaAtRegistration)

        assertNull(app.admin.enable("arena1"))

        val (arenaAfterEnable, position, state) = app.presentation.signUpdates.last()
        assertIs<Arena.Enabled>(arenaAfterEnable)
        assertEquals(sign, position)
        assertEquals(ArenaState.Kind.WAITING, state)
    }
}
