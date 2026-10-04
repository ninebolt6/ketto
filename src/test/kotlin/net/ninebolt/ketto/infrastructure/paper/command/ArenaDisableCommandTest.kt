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

class ArenaDisableCommandTest {

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
    fun `disable flips an enabled arena and reports a repeat`() {
        val op = env.opPlayer("Op")
        env.newArena("a2")
        env.runCommand(op, "arena", "a2", "disable")
        assertTrue(op.drainMessages().any { it.contains("Disabled arena") })
        assertFalse(env.sessions.findArena("a2")!!.enabled)
        assertFalse(env.arenaRepository.loadAll().first { it.name == "a2" }.enabled)

        env.runCommand(op, "arena", "a2", "disable")
        assertTrue(op.drainMessages().any { it.contains("That arena is already disabled!") })
    }

    @Test
    fun `disable reports not found for an unknown arena`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "missing", "disable")
        assertTrue(op.drainMessages().any { it.contains("That arena does not exist") })
    }
}
