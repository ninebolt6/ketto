package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Verifies /1vs1 leave reply paths driven through the command. */
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
    fun `console cannot run leave`() {
        val console = env.server.consoleSender
        env.run(console, "leave")
        assertTrue(console.drainMessages().any { it.contains("このコマンドはプレイヤーのみ実行可能です") })
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

    @Test
    fun `leave during countdown is rejected`() {
        val arena = env.newArena()
        env.join(env.player("Alice"), arena)
        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.run(p2, "leave")
        assertTrue(p2.drainMessages().any { it.contains("カウントダウン中はアリーナから退出できません！") })
    }
}
