package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArenaSignServiceTest {

    @Test
    fun `sign lifecycle`() {
        val app = TestApp()
        app.newArena()
        val sign = BlockPosition.new("world", 3, 64, 3)
        assertNull(app.signs.setSign("arena1", sign))
        assertEquals(sign, app.signs.signLocation("arena1"))
        assertEquals("arena1", app.signs.signOwner(BlockPosition.new("world", 3, 64, 3)))
        assertEquals(Triple(Arena.Id.new("arena1"), sign, ArenaState.WAITING), app.presentation.signUpdates.last())

        assertEquals(SetSignError.NotFound, app.signs.setSign("missing", sign))
        assertNull(app.signs.clearSign("arena1"))
        assertNull(app.signs.signLocation("arena1"))
        assertEquals(ClearSignError.NotFound, app.signs.clearSign("missing"))
    }
}
