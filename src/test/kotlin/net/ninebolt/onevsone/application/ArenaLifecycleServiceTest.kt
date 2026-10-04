package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaLifecycleServiceTest {

    @Test
    fun `load skips every arena when definitions are unreadable`() {
        val app = TestApp()
        app.arenas.save(
            Arena.Enabled.restored(
                arenaId("a1"),
                WorldPosition.new("world", 1.0, 64.0, 1.0),
                WorldPosition.new("world", 2.0, 64.0, 2.0),
            ),
        )
        app.arenas.failOnLoad = true
        app.lifecycle.load()
        assertTrue(app.sessions.arenaIds().isEmpty())
        assertTrue(app.logger.warnings.any { it.contains("unreadable") })
    }

    @Test
    fun `sign refresh failure during load does not block the arena install`() {
        val app = TestApp()
        app.arenas.save(
            Arena.Enabled.restored(
                arenaId("a1"),
                WorldPosition.new("world", 1.0, 64.0, 1.0),
                WorldPosition.new("world", 2.0, 64.0, 2.0),
            ),
        )
        app.arenas.failOnSignRead = true
        app.lifecycle.load()
        assertTrue(arenaId("a1") in app.sessions.arenaIds())
        assertTrue(app.logger.warnings.any { it.contains("Could not update sign") })
    }

    @Test
    fun `shutdown aborts matches and restores online pendings`() {
        val app = TestApp()
        val (p1, _) = app.startMatch()
        app.lifecycle.shutdown()
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertTrue(app.sessions.resolveArena("arena1")!!.enabled)
        assertNull(app.sessions.arenaIdOf(p1.id))
        assertEquals(2, app.equipment.restored.size)
    }
}
