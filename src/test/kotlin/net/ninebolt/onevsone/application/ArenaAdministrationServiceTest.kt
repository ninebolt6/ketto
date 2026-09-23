package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.SpawnSlot
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for admin operations (create/remove/enable/spawn/kit). */
class ArenaAdministrationServiceTest {

    private lateinit var app: TestApp

    @BeforeEach
    fun setup() {
        app = TestApp()
    }

    @Test
    fun `create persists arena and rejects duplicates and invalid names`() {
        assertTrue(app.admin.create("arena1"))
        assertEquals(false, app.arenas.find("arena1").enabled)
        assertEquals(ArenaState.WAITING, app.state())

        assertFalse(app.admin.create("Arena1"))
        assertFalse(app.admin.create("bad/name"))
        assertFalse(app.admin.create("players"))
        assertNull(app.service.arena("bad/name"))
    }

    @Test
    fun `remove aborts running match and clears registrations`() {
        val arena = app.newArena()
        val (p1, p2) = app.joinedTwo()
        app.signs.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        assertTrue(app.admin.remove("arena1"))
        assertNull(app.service.arena("arena1"))
        assertNull(app.service.arenaIdOf(p1.id))
        assertNull(app.service.arenaIdOf(p2.id))
        assertNull(app.signs.signLocation("arena1"))
        assertNull(app.arenas.signs["arena1"])
        assertFalse(app.arenas.names.contains("arena1"))
        // The kit cache is dropped so recreating under the same name cannot apply the old kit
        assertEquals(listOf(arena), app.equipment.forgottenKits)

        assertFalse(app.admin.remove("arena1"))
        assertFalse(app.admin.remove("bad name!"))
    }

    @Test
    fun `setEnabled toggles and persists, disable aborts countdown`() {
        app.newArena()
        app.joinedTwo()
        assertEquals(ArenaState.COUNTDOWN, app.state())

        assertEquals(ToggleReply.Changed, app.admin.setEnabled("arena1", false))
        assertFalse(app.service.arena("arena1")!!.enabled)
        assertEquals(ArenaState.WAITING, app.state())
        assertFalse(app.arenas.find("arena1").enabled)

        assertEquals(ToggleReply.AlreadyDisabled, app.admin.setEnabled("arena1", false))
        assertEquals(ToggleReply.Changed, app.admin.setEnabled("arena1", true))
        assertEquals(ToggleReply.AlreadyEnabled, app.admin.setEnabled("arena1", true))
        assertEquals(ToggleReply.NotFound, app.admin.setEnabled("missing", true))
        assertEquals(ToggleReply.NotFound, app.admin.setEnabled("bad name!", true))
    }

    @Test
    fun `setSpawn writes slot and persists`() {
        app.newArena()
        val pos = WorldPosition.new("world", 9.5, 70.0, -2.5, 33.3f, 12.5f)
        assertTrue(app.admin.setSpawn("arena1", SpawnSlot.FIRST, pos))
        assertEquals(pos, app.arenas.find("arena1").spawn1)
        assertTrue(app.admin.setSpawn("arena1", SpawnSlot.SECOND, pos))
        assertEquals(pos, app.arenas.find("arena1").spawn2)
        assertFalse(app.admin.setSpawn("missing", SpawnSlot.FIRST, pos))
    }

    @Test
    fun `setLobby persists position`() {
        val pos = WorldPosition.new("lobby", 5.0, 64.0, 5.0)
        app.admin.setLobby(pos)
        assertEquals(pos, app.arenas.lobbyPosition)
    }

    @Test
    fun `setKit delegates to kit port`() {
        app.newArena()
        val p = app.players.add("Alice")
        assertTrue(app.admin.setKit("arena1", p.id))
        assertEquals(1, app.equipment.savedKits.size)
        assertFalse(app.admin.setKit("missing", p.id))
    }

    @Test
    fun `arena name lookup ignores case`() {
        app.newArena("Arena1")
        assertEquals("Arena1", app.admin.arena("arena1")?.name)
        assertEquals("Arena1", app.service.arena("ARENA1")?.name)
        assertEquals(ArenaState.WAITING, app.service.matchOf("ArEnA1")?.state)

        assertEquals(ToggleReply.Changed, app.admin.setEnabled("ARENA1", false))
        assertFalse(app.service.arena("Arena1")!!.enabled)
        assertEquals(ToggleReply.AlreadyDisabled, app.admin.setEnabled("arena1", false))

        assertTrue(app.admin.setSpawn("ARENA1", SpawnSlot.FIRST, WorldPosition.new("world", 1.0, 64.0, 1.0)))
        assertTrue(app.signs.setSign("arena1", BlockPosition.new("world", 3, 64, 3)))
        assertEquals("Arena1", app.signs.signOwner(BlockPosition.new("world", 3, 64, 3)))

        assertTrue(app.admin.remove("aReNa1"))
        assertNull(app.service.arena("Arena1"))
        assertNull(app.signs.signLocation("Arena1"))
        assertNull(app.arenas.signs["Arena1"])
    }

    @Test
    fun `remove during ingame forfeits and unregisters`() {
        val (p1, p2) = app.startMatch()
        app.service.defeat(p2.id, DefeatCause.FALL)
        assertTrue(app.admin.remove("arena1"))
        assertNull(app.service.arenaIdOf(p1.id))
        assertNull(app.service.arenaIdOf(p2.id))
        // The loser's stats are already settled; the point is that no registrations remain after remove aborts
        assertNull(app.matchState.registrations[p1.name])
        assertNull(app.matchState.registrations[p2.name])
    }
}
