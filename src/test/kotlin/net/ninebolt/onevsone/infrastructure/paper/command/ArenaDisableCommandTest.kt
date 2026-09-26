package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.runCommand
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
        assertFalse(env.service.arena("a2")!!.enabled)
        assertFalse(env.arenaRepo.find("a2")!!.enabled)

        env.runCommand(op, "arena", "a2", "disable")
        assertTrue(op.drainMessages().any { it.contains("That arena is already disabled!") })
    }
}
