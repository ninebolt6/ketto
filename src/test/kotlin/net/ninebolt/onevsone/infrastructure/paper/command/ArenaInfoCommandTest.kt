package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.fallIntoVoid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

class ArenaInfoCommandTest {

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
    fun `arena info shows state and players during match`() {
        val viewer = env.player("Viewer")
        env.run(viewer, "arena", "missing")
        assertTrue(viewer.drainMessages().any { it.contains("そのアリーナは存在しません") })

        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        fallIntoVoid(p2)

        env.run(viewer, "arena", "arena1", "info")
        val msgs = viewer.drainMessages()
        assertTrue(msgs.any { it.contains("=== Arena[arena1] ===") })
        assertTrue(msgs.any { it.contains("状態: Ingame") })
        assertTrue(msgs.any { it.contains("[Alice] vs [Bob]") })
        assertTrue(msgs.any { it.contains("勝数: 1-0") })
    }

    @Test
    fun `arena name alone defaults to info`() {
        val viewer = env.player("Viewer")
        env.newArena()
        env.run(viewer, "arena", "arena1")
        assertTrue(viewer.drainMessages().any { it.contains("Arena[arena1]") })
    }

    @Test
    fun `extra args are a syntax error`() {
        val viewer = env.player("Viewer")
        env.newArena()
        env.run(viewer, "arena", "arena1", "info", "extra")
        assertTrue(viewer.drainMessages().any { it.contains("Incorrect argument") })
    }
}
