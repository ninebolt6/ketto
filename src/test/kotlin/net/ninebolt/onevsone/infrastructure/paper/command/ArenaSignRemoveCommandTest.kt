package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
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

class ArenaSignRemoveCommandTest {

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
    fun `arena sign remove unregisters sign`() {
        val op = env.opPlayer("Op")
        env.newArena()
        env.signRepository.setSign(arenaId("arena1"), BlockPosition.new("world", 4, 64, 4))

        env.runCommand(op, "arena", "arena1", "sign", "remove")
        assertTrue(op.drainMessages().any { it.contains("Unregistered sign for arena") })
        assertNull(env.signRepository.findSignOwner(BlockPosition.new("world", 4, 64, 4)))
        assertNull(env.signRepository.findSignLocation(arenaId("arena1")))

        env.runCommand(op, "arena", "arena1", "sign", "remove")
        assertTrue(op.drainMessages().any { it.contains("No sign is registered for that arena") })

        env.runCommand(op, "arena", "missing", "sign", "remove")
        assertTrue(op.drainMessages().any { it.contains("That arena does not exist") })
    }
}
