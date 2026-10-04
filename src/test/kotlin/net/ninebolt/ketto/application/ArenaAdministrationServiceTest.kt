package net.ninebolt.ketto.application

import net.ninebolt.ketto.application.fixtures.TestApp
import net.ninebolt.ketto.application.port.PersistenceException
import net.ninebolt.ketto.domain.ArenaState
import net.ninebolt.ketto.domain.BlockPosition
import net.ninebolt.ketto.domain.DefeatCause
import net.ninebolt.ketto.domain.SpawnSlot
import net.ninebolt.ketto.domain.WorldPosition
import net.ninebolt.ketto.domain.fixtures.arenaId
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
        assertNull(app.administration.create("arena1"))
        assertEquals(false, app.arenaRepository.find("arena1").enabled)
        assertEquals(ArenaState.Kind.WAITING, app.state())

        assertEquals(CreateError.AlreadyExists, app.administration.create("Arena1"))
        assertEquals(CreateError.InvalidName, app.administration.create(" bad"))
        assertEquals(CreateError.InvalidName, app.administration.create("create"))
        assertNull(app.administration.create("players"))
        assertNull(app.sessions.findArena("bad/name"))
    }

    @Test
    fun `remove aborts running match and clears registrations`() {
        val arena = app.newArena()
        val (p1, p2) = app.joinedTwo()
        app.signService.setSign(arenaId("arena1"), BlockPosition.new("world", 3, 64, 3))
        assertNull(app.administration.remove("arena1"))
        assertNull(app.sessions.findArena("arena1"))
        assertNull(app.sessions.findArenaIdOf(p1.id))
        assertNull(app.sessions.findArenaIdOf(p2.id))
        assertNull(app.arenaRepository.findSignLocation(arenaId("arena1")))
        assertNull(app.arenaRepository.signs[arenaId("arena1")])
        assertFalse(app.arenaRepository.names.contains("arena1"))
        assertEquals(listOf(arena), app.equipment.forgottenKits)

        assertEquals(RemoveError.NotFound, app.administration.remove("arena1"))
        assertEquals(RemoveError.NotFound, app.administration.remove("bad name!"))
    }

    @Test
    fun `enable and disable toggle and persist, disable aborts countdown`() {
        app.newArena()
        app.joinedTwo()
        assertEquals(ArenaState.Kind.COUNTDOWN, app.state())

        assertNull(app.administration.disable("arena1"))
        assertFalse(app.sessions.findArena("arena1")!!.enabled)
        assertEquals(ArenaState.Kind.WAITING, app.state())
        assertFalse(app.arenaRepository.find("arena1").enabled)

        assertEquals(DisableError.AlreadyDisabled, app.administration.disable("arena1"))
        assertNull(app.administration.enable("arena1"))
        assertEquals(EnableError.AlreadyEnabled, app.administration.enable("arena1"))
        assertEquals(EnableError.NotFound, app.administration.enable("missing"))
        assertEquals(EnableError.NotFound, app.administration.enable("bad name!"))
    }

    @Test
    fun `enable requires both spawns and reports the missing slots`() {
        assertNull(app.administration.create("arena1"))
        assertEquals(
            EnableError.MissingSpawns(listOf(SpawnSlot.FIRST, SpawnSlot.SECOND)),
            app.administration.enable("arena1"),
        )
        assertFalse(app.arenaRepository.find("arena1").enabled)

        val pos = WorldPosition.new("world", 9.5, 70.0, -2.5)
        app.administration.setSpawn(arenaId("arena1"), SpawnSlot.FIRST, pos)
        assertEquals(
            EnableError.MissingSpawns(listOf(SpawnSlot.SECOND)),
            app.administration.enable("arena1"),
        )

        app.administration.setSpawn(arenaId("arena1"), SpawnSlot.SECOND, pos)
        assertNull(app.administration.enable("arena1"))
        assertTrue(app.arenaRepository.find("arena1").enabled)
    }

    @Test
    fun `setSpawn writes slot and persists`() {
        app.newArena()
        val pos = WorldPosition.new("world", 9.5, 70.0, -2.5, 33.3f, 12.5f)
        app.administration.setSpawn(arenaId("arena1"), SpawnSlot.FIRST, pos)
        assertEquals(pos, app.arenaRepository.find("arena1").spawn1)
        app.administration.setSpawn(arenaId("arena1"), SpawnSlot.SECOND, pos)
        assertEquals(pos, app.arenaRepository.find("arena1").spawn2)
        assertNull(app.administration.findArenaId("missing"))
    }

    @Test
    fun `setKit delegates to kit port`() {
        app.newArena()
        val p = app.players.add("Alice")
        app.administration.setKit(arenaId("arena1"), p.id)
        assertEquals(1, app.equipment.savedKits.size)
        assertNull(app.administration.findArenaId("missing"))
    }

    @Test
    fun `setSpawn and setKit ignore an unknown arena id`() {
        val missing = arenaId("missing")
        app.administration.setSpawn(missing, SpawnSlot.FIRST, WorldPosition.new("world", 0.0, 64.0, 0.0))
        app.administration.setKit(missing, app.players.add("Alice").id)
        assertTrue(app.equipment.savedKits.isEmpty())
        assertFalse("missing" in app.arenaRepository.names)
    }

    @Test
    fun `arena name lookup ignores case`() {
        app.newArena("Arena1")
        assertEquals("Arena1", app.administration.findArenaId("arena1")?.name)
        assertEquals("Arena1", app.sessions.findArena("ARENA1")?.name)
        assertEquals(ArenaState.Kind.WAITING, app.participation.findMatchIn("ArEnA1")?.state?.kind)

        assertNull(app.administration.disable("ARENA1"))
        assertFalse(app.sessions.findArena("Arena1")!!.enabled)
        assertEquals(DisableError.AlreadyDisabled, app.administration.disable("arena1"))

        app.administration.setSpawn(app.administration.findArenaId("ARENA1")!!, SpawnSlot.FIRST, WorldPosition.new("world", 1.0, 64.0, 1.0))
        app.signService.setSign(app.administration.findArenaId("arena1")!!, BlockPosition.new("world", 3, 64, 3))
        assertEquals("Arena1", app.signService.findSignOwner(BlockPosition.new("world", 3, 64, 3))?.name)

        assertNull(app.administration.remove("aReNa1"))
        assertNull(app.sessions.findArena("Arena1"))
        assertNull(app.arenaRepository.findSignLocation(arenaId("Arena1")))
        assertNull(app.arenaRepository.signs[arenaId("Arena1")])
    }

    @Test
    fun `remove during ingame forfeits and unregisters`() {
        val (p1, p2) = app.startMatch()
        app.participation.defeat(p2.id, DefeatCause.FALL)
        assertNull(app.administration.remove("arena1"))
        assertNull(app.sessions.findArenaIdOf(p1.id))
        assertNull(app.sessions.findArenaIdOf(p2.id))
    }

    @Test
    fun `authoritative persist failure propagates and leaves the sessions unchanged`() {
        app.arenaRepository.failOnSave = true
        assertFailsWith<PersistenceException> { app.administration.create("arena1") }
        assertNull(app.sessions.findArena("arena1"))

        app.arenaRepository.failOnSave = false
        app.newArena()
        app.arenaRepository.failOnSave = true
        assertFailsWith<PersistenceException> { app.administration.disable("arena1") }
        assertTrue(app.sessions.findArena("arena1")!!.enabled)
    }

    @Test
    fun `enable persist failure leaves the arena disabled and the sign untouched`() {
        app.newArena("arena1", enabled = false)
        app.signService.setSign(arenaId("arena1"), BlockPosition.new("world", 3, 64, 3))
        val signWrites = app.presentation.signUpdates.size
        app.arenaRepository.failOnSave = true
        assertFailsWith<PersistenceException> { app.administration.enable("arena1") }
        assertFalse(app.sessions.findArena("arena1")!!.enabled)
        assertEquals(signWrites, app.presentation.signUpdates.size)
    }
}
