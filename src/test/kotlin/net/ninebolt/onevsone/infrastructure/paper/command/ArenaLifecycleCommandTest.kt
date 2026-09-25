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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaLifecycleCommandTest {

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
    fun `arena create remove lifecycle`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "create", "newarena")
        assertTrue(op.drainMessages().any { it.contains("Created arena: newarena") })
        assertEquals(false, env.service.arena("newarena")!!.enabled)

        env.runCommand(op, "arena", "create", "newarena")
        assertTrue(op.drainMessages().any { it.contains("That arena already exists") })

        env.runCommand(op, "arena", "newarena", "remove")
        assertTrue(op.drainMessages().any { it.contains("Removed arena: newarena") })
        assertNull(env.service.arena("newarena"))

        env.runCommand(op, "arena", "newarena", "remove")
        assertTrue(op.drainMessages().any { it.contains("That arena does not exist") })
    }

    @Test
    fun `arena enable disable`() {
        val op = env.opPlayer("Op")
        env.newArena("a2", enabled = false)
        env.runCommand(op, "arena", "a2", "enable")
        assertTrue(op.drainMessages().any { it.contains("Enabled arena") })
        env.runCommand(op, "arena", "a2", "enable")
        assertTrue(op.drainMessages().any { it.contains("That arena is already enabled!") })
        env.runCommand(op, "arena", "a2", "disable")
        assertTrue(op.drainMessages().any { it.contains("Disabled arena") })
        env.runCommand(op, "arena", "a2", "disable")
        assertTrue(op.drainMessages().any { it.contains("That arena is already disabled!") })
        assertEquals(false, env.service.arena("a2")!!.enabled)
        assertFalse(env.arenaRepo.find("a2")!!.enabled)
    }

    @Test
    fun `enable reports not found for an unknown arena`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "missing", "enable")
        assertTrue(op.drainMessages().any { it.contains("That arena does not exist") })
    }

    @Test
    fun `create requires exactly one name arg`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "create")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena create <arena>") })
        env.runCommand(op, "arena", "create", "other", "extra")
        assertTrue(op.drainMessages().any { it.contains("Incorrect argument") })
    }

    @Test
    fun `reserved name create is rejected`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "create", "create")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena create <arena>") })
    }

    @Test
    fun `console can create arena`() {
        val console = env.server.consoleSender
        env.runCommand(console, "arena", "create", "consolearena")
        assertTrue(console.drainMessages().any { it.contains("Created arena: consolearena") })
    }
}
