package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaSignCommandTest {

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
        env.run(op, "arena", "arena1", "sign", "set")
        assertTrue(op.drainMessages().any { it.contains("看板を見て実行してください") })
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

        env.run(op, "arena", "arena1", "sign", "set")
        assertEquals("arena1", env.signRepo.signOwner(BlockPosition.new("world", 4, 64, 4)))

        env.run(second, "arena", "arena2", "sign", "set")
        assertTrue(second.drainMessages().any { it.contains("その看板はすでに登録されています") })
    }

    @Test
    fun `arena sign remove unregisters sign`() {
        val op = env.opPlayer("Op")
        env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 4, 64, 4))

        env.run(op, "arena", "arena1", "sign", "remove")
        assertTrue(op.drainMessages().any { it.contains("の看板登録を解除しました") })
        assertNull(env.signRepo.signOwner(BlockPosition.new("world", 4, 64, 4)))
        assertNull(env.signRepo.signLocation("arena1"))

        env.run(op, "arena", "arena1", "sign", "remove")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナには看板が登録されていません") })

        env.run(op, "arena", "missing", "sign", "remove")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナは存在しません") })
    }

    @Test
    fun `bare sign shows ops usage`() {
        val op = env.opPlayer("Op")
        env.run(op, "arena", "x", "sign")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena <arena> sign <set|remove>") })
    }

    @Test
    fun `console cannot set sign`() {
        val console = env.server.consoleSender
        env.newArena()
        env.run(console, "arena", "arena1", "sign", "set")
        assertTrue(console.drainMessages().any { it.contains("このコマンドはプレイヤーのみ実行可能です") })
    }
}
