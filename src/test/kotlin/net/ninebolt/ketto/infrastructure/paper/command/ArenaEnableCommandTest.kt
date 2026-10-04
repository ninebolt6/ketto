package net.ninebolt.ketto.infrastructure.paper.command

import net.ninebolt.ketto.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.ketto.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.ketto.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.ketto.infrastructure.paper.fixtures.runCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArenaEnableCommandTest {

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
    fun `enable flips a disabled arena and reports a repeat`() {
        val op = env.opPlayer("Op")
        env.newArena("a2", enabled = false)
        env.runCommand(op, "arena", "a2", "enable")
        assertTrue(op.drainMessages().any { it.contains("Enabled arena") })
        assertTrue(env.sessions.findArena("a2")!!.enabled)
        assertTrue(env.arenaRepository.loadAll().first { it.name == "a2" }.enabled)

        env.runCommand(op, "arena", "a2", "enable")
        assertTrue(op.drainMessages().any { it.contains("That arena is already enabled!") })
    }

    @Test
    fun `enable without spawns reports the missing slots`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "create", "newarena")
        env.runCommand(op, "arena", "newarena", "enable")
        assertTrue(op.drainMessages().any { it.contains("Set spawn 1, 2 for arena newarena first") })
        assertFalse(env.sessions.findArena("newarena")!!.enabled)
    }

    @Test
    fun `enable reports not found for an unknown arena`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "missing", "enable")
        assertTrue(op.drainMessages().any { it.contains("That arena does not exist") })
    }
}
