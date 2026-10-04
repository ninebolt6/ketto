package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.runCommand
import org.bukkit.Location
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArenaSpawnSetCommandTest {

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
    fun `arena spawn set saves fractional location`() {
        val op = env.opPlayer("Op")
        env.newArena()
        op.setLocation(Location(env.world(), 1.5, 65.25, -3.0, 33.3f, 12.5f))
        env.runCommand(op, "arena", "arena1", "spawn", "set", "1")
        assertTrue(op.drainMessages().any { it.contains("Set spawn 1") })
        val spawn1 = env.arenaRepository.loadAll().first { it.name == "arena1" }.spawn1!!
        assertEquals(33.3f, spawn1.yaw, 0.001f)
        assertEquals(65.25, spawn1.y)
    }

    @Test
    fun `spawn set requires a slot of 1 or 2`() {
        val op = env.opPlayer("Op")
        env.newArena()
        env.runCommand(op, "arena", "arena1", "spawn", "set")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena <arena> spawn set <1|2>") })
        env.runCommand(op, "arena", "arena1", "spawn", "set", "3")
        assertTrue(op.drainMessages().any { it.contains("must not be more than 2") })
        env.runCommand(op, "arena", "arena1", "spawn", "set", "1", "extra")
        assertTrue(op.drainMessages().any { it.contains("Incorrect argument") })
    }

    @Test
    fun `spawn set reports not found for an unknown arena`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "missing", "spawn", "set", "1")
        assertTrue(op.drainMessages().any { it.contains("That arena does not exist") })
    }
}
