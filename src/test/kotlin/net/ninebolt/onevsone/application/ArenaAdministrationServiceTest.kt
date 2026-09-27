package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.SpawnSlot
import net.ninebolt.onevsone.domain.WorldPosition
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaAdministrationServiceTest {

    private lateinit var app: TestApp

    @BeforeEach
    fun setup() {
        app = TestApp()
    }

    @Test
    fun `create persists arena and rejects duplicates and invalid names`() {
        assertNull(app.admin.create("arena1"))
        assertEquals(false, app.arenas.find("arena1").enabled)
        assertEquals(ArenaState.Kind.WAITING, app.state())

        assertEquals(CreateError.AlreadyExists, app.admin.create("Arena1"))
        assertEquals(CreateError.InvalidName, app.admin.create("bad/name"))
        assertEquals(CreateError.InvalidName, app.admin.create("players"))
        assertNull(app.service.arena("bad/name"))
    }

    @Test
    fun `remove aborts running match and clears registrations`() {
        val arena = app.newArena()
        val (p1, p2) = app.joinedTwo()
        app.signs.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        assertNull(app.admin.remove("arena1"))
        assertNull(app.service.arena("arena1"))
        assertNull(app.registry.arenaOf(p1.id))
        assertNull(app.registry.arenaOf(p2.id))
        assertNull(app.signs.signLocation("arena1"))
        assertNull(app.arenas.signs["arena1"])
        assertFalse(app.arenas.names.contains("arena1"))
        assertEquals(listOf(arena), app.equipment.forgottenKits)

        assertEquals(RemoveError.NotFound, app.admin.remove("arena1"))
        assertEquals(RemoveError.NotFound, app.admin.remove("bad name!"))
    }

    @Test
    fun `enable and disable toggle and persist, disable aborts countdown`() {
        app.newArena()
        app.joinedTwo()
        assertEquals(ArenaState.Kind.COUNTDOWN, app.state())

        assertNull(app.admin.disable("arena1"))
        assertFalse(app.service.arena("arena1")!!.enabled)
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertFalse(app.arenas.find("arena1").enabled)

        assertEquals(DisableError.AlreadyDisabled, app.admin.disable("arena1"))
        assertNull(app.admin.enable("arena1"))
        assertEquals(EnableError.AlreadyEnabled, app.admin.enable("arena1"))
        assertEquals(EnableError.NotFound, app.admin.enable("missing"))
        assertEquals(EnableError.NotFound, app.admin.enable("bad name!"))
    }

    @Test
    fun `enable requires both spawns and reports the missing slots`() {
        assertNull(app.admin.create("arena1"))
        assertEquals(
            EnableError.MissingSpawns(listOf(SpawnSlot.FIRST, SpawnSlot.SECOND)),
            app.admin.enable("arena1"),
        )
        assertFalse(app.arenas.find("arena1").enabled)

        val pos = WorldPosition.new("world", 9.5, 70.0, -2.5)
        assertNull(app.admin.setSpawn("arena1", SpawnSlot.FIRST, pos))
        assertEquals(
            EnableError.MissingSpawns(listOf(SpawnSlot.SECOND)),
            app.admin.enable("arena1"),
        )

        assertNull(app.admin.setSpawn("arena1", SpawnSlot.SECOND, pos))
        assertNull(app.admin.enable("arena1"))
        assertTrue(app.arenas.find("arena1").enabled)
    }

    @Test
    fun `setSpawn writes slot and persists`() {
        app.newArena()
        val pos = WorldPosition.new("world", 9.5, 70.0, -2.5, 33.3f, 12.5f)
        assertNull(app.admin.setSpawn("arena1", SpawnSlot.FIRST, pos))
        assertEquals(pos, app.arenas.find("arena1").spawn1)
        assertNull(app.admin.setSpawn("arena1", SpawnSlot.SECOND, pos))
        assertEquals(pos, app.arenas.find("arena1").spawn2)
        assertEquals(SetSpawnError.NotFound, app.admin.setSpawn("missing", SpawnSlot.FIRST, pos))
    }

    @Test
    fun `setKit delegates to kit port`() {
        app.newArena()
        val p = app.players.add("Alice")
        assertNull(app.admin.setKit("arena1", p.id))
        assertEquals(1, app.equipment.savedKits.size)
        assertEquals(SetKitError.NotFound, app.admin.setKit("missing", p.id))
    }

    @Test
    fun `arena name lookup ignores case`() {
        app.newArena("Arena1")
        assertEquals("Arena1", app.admin.arena("arena1")?.name)
        assertEquals("Arena1", app.service.arena("ARENA1")?.name)
        assertEquals(ArenaState.Kind.WAITING, app.service.matchOf("ArEnA1")?.state?.kind)

        assertNull(app.admin.disable("ARENA1"))
        assertFalse(app.service.arena("Arena1")!!.enabled)
        assertEquals(DisableError.AlreadyDisabled, app.admin.disable("arena1"))

        assertNull(app.admin.setSpawn("ARENA1", SpawnSlot.FIRST, WorldPosition.new("world", 1.0, 64.0, 1.0)))
        assertNull(app.signs.setSign("arena1", BlockPosition.new("world", 3, 64, 3)))
        assertEquals("Arena1", app.signs.signOwner(BlockPosition.new("world", 3, 64, 3)))

        assertNull(app.admin.remove("aReNa1"))
        assertNull(app.service.arena("Arena1"))
        assertNull(app.signs.signLocation("Arena1"))
        assertNull(app.arenas.signs["Arena1"])
    }

    @Test
    fun `remove during ingame forfeits and unregisters`() {
        val (p1, p2) = app.startMatch()
        app.service.defeat(p2.id, DefeatCause.FALL)
        assertNull(app.admin.remove("arena1"))
        assertNull(app.registry.arenaOf(p1.id))
        assertNull(app.registry.arenaOf(p2.id))
    }

    @Test
    fun `authoritative persist failure propagates and leaves the registry unchanged`() {
        app.arenas.failOnSave = true
        assertFailsWith<PersistenceFailure> { app.admin.create("arena1") }
        assertNull(app.service.arena("arena1"))

        app.arenas.failOnSave = false
        app.newArena()
        app.arenas.failOnSave = true
        assertFailsWith<PersistenceFailure> { app.admin.disable("arena1") }
        assertTrue(app.service.arena("arena1")!!.enabled)
    }

    @Test
    fun `enable persist failure leaves the arena disabled and the sign untouched`() {
        app.newArena("arena1", enabled = false)
        app.signs.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val signWrites = app.presentation.signUpdates.size
        app.arenas.failOnSave = true
        assertFailsWith<PersistenceFailure> { app.admin.enable("arena1") }
        assertFalse(app.service.arena("arena1")!!.enabled)
        assertEquals(signWrites, app.presentation.signUpdates.size)
    }
}
