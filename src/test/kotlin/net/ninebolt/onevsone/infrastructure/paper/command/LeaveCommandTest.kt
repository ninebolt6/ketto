package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeaveCommandTest {

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
    fun `leave when not joined reports not joined`() {
        val p = env.player("Alice")
        env.run(p, "leave")
        assertTrue(p.drainMessages().any { it.contains("あなたはアリーナに参加していません！") })
    }

    @Test
    fun `leave while waiting leaves and resets the arena`() {
        val arena = env.newArena()
        val p = env.player("Alice")
        env.join(p, arena)
        env.run(p, "leave")
        assertTrue(p.drainMessages().any { it.contains("アリーナから退出しました") })
        assertEquals(ArenaState.WAITING, env.state("arena1"))
    }
}
