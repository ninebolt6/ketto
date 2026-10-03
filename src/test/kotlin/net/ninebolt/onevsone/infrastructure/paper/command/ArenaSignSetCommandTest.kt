package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.runCommand
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArenaSignSetCommandTest {

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
    fun `arena sign set requires looking at sign`() {
        val op = env.opPlayer("Op")
        env.newArena()
        env.runCommand(op, "arena", "arena1", "sign", "set")
        assertTrue(op.drainMessages().any { it.contains("Look at a sign and run the command") })
    }

    @Test
    fun `arena sign set registers sign and reports taken`() {
        val op = env.opPlayer("Op")
        val second = env.opPlayer("Op2")
        env.newArena("arena1")
        env.newArena("arena2")

        val sign = env.signBlock(4, 64, 4)
        op.targetBlock = sign
        second.targetBlock = sign

        env.runCommand(op, "arena", "arena1", "sign", "set")
        assertEquals(arenaId("arena1"), env.signRepo.signOwner(BlockPosition.new("world", 4, 64, 4)))

        env.runCommand(second, "arena", "arena2", "sign", "set")
        assertTrue(second.drainMessages().any { it.contains("That sign is already registered") })
    }

    @Test
    fun `arena sign set reports not found for an unknown arena`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "missing", "sign", "set")
        assertTrue(op.drainMessages().any { it.contains("That arena does not exist") })
    }

    @Test
    fun `arena sign set warns when the target is not a sign`() {
        val op = env.opPlayer("Op")
        env.newArena()
        op.targetBlock = env.plainBlock(4, 64, 4)

        env.runCommand(op, "arena", "arena1", "sign", "set")
        assertTrue(op.drainMessages().any { it.contains("Look at a sign and run the command") })
    }

    @Test
    fun `arena sign set on a sign owned by the same arena is allowed`() {
        val op = env.opPlayer("Op")
        env.newArena()
        val sign = env.signBlock(4, 64, 4)
        env.signRepo.setSign(arenaId("arena1"), BlockPosition.new("world", 4, 64, 4))
        op.targetBlock = sign

        env.runCommand(op, "arena", "arena1", "sign", "set")
        assertTrue(op.drainMessages().none { it.contains("already registered") })
        assertEquals(arenaId("arena1"), env.signRepo.signOwner(BlockPosition.new("world", 4, 64, 4)))
    }
}
