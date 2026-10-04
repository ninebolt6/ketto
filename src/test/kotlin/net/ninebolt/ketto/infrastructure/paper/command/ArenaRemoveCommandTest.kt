package net.ninebolt.ketto.infrastructure.paper.command

import net.ninebolt.ketto.domain.ArenaState
import net.ninebolt.ketto.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.ketto.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.ketto.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.ketto.infrastructure.paper.fixtures.runCommand
import net.ninebolt.ketto.infrastructure.paper.fixtures.uuid
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaRemoveCommandTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `arena remove deletes the arena and reports a missing one`() {
        val op = env.opPlayer("Op")
        env.newArena("newarena")

        env.runCommand(op, "arena", "newarena", "remove")
        assertTrue(op.drainMessages().any { it.contains("Removed arena: newarena") })
        assertNull(env.sessions.findArena("newarena"))

        env.runCommand(op, "arena", "newarena", "remove")
        assertTrue(op.drainMessages().any { it.contains("That arena does not exist") })
    }

    @Test
    fun `arena remove during INGAME aborts the match and restores participants`() {
        val op = env.opPlayer("Op")
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.state())

        env.runCommand(op, "arena", "arena1", "remove")

        assertTrue(op.drainMessages().any { it.contains("Removed arena: arena1") })
        assertNull(env.state())
        assertNull(env.sessions.findArena("arena1"))
        assertNull(env.sessions.findArenaIdOf(p1.uuid))
        assertNull(env.sessions.findArenaIdOf(p2.uuid))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }
}
