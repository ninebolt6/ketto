package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Join sign registration lifecycle: set, locate, and clear. */
class ArenaSignServiceTest {

    @Test
    fun `sign lifecycle`() {
        val app = TestApp()
        app.newArena()
        val sign = BlockPosition.new("world", 3, 64, 3)
        assertTrue(app.signs.setSign("arena1", sign))
        assertEquals(sign, app.signs.signLocation("arena1"))
        assertEquals("arena1", app.signs.signOwner(BlockPosition.new("world", 3, 64, 3)))
        assertEquals(Arena.Id.new("arena1") to ArenaState.WAITING, app.presentation.signUpdates.last())

        assertFalse(app.signs.setSign("missing", sign))
        // clearSign is idempotent: it succeeds whenever the arena exists; only an unregistered arena fails
        assertTrue(app.signs.clearSign("arena1"))
        assertNull(app.signs.signLocation("arena1"))
        assertFalse(app.signs.clearSign("missing"))
    }
}
