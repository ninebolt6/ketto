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
        assertNull(env.service.arena("newarena"))

        env.runCommand(op, "arena", "newarena", "remove")
        assertTrue(op.drainMessages().any { it.contains("That arena does not exist") })
    }
}
