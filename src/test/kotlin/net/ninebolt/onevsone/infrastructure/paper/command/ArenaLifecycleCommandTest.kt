package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
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
        env.run(op, "arena", "create", "newarena")
        assertTrue(op.drainMessages().any { it.contains("アリーナ: newarena を作成しました") })
        assertEquals(false, env.service.arena("newarena")!!.enabled)

        env.run(op, "arena", "create", "newarena")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナはすでに存在しています") })

        env.run(op, "arena", "newarena", "remove")
        assertTrue(op.drainMessages().any { it.contains("アリーナ: newarena を削除しました") })
        assertNull(env.service.arena("newarena"))

        env.run(op, "arena", "newarena", "remove")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナは存在しません") })
    }

    @Test
    fun `arena enable disable`() {
        val op = env.opPlayer("Op")
        env.newArena("a2", enabled = false)
        env.run(op, "arena", "a2", "enable")
        assertTrue(op.drainMessages().any { it.contains("を有効にしました") })
        env.run(op, "arena", "a2", "enable")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナはすでに有効になっています！") })
        env.run(op, "arena", "a2", "disable")
        assertTrue(op.drainMessages().any { it.contains("を無効にしました") })
        env.run(op, "arena", "a2", "disable")
        assertTrue(op.drainMessages().any { it.contains("そのアリーナはすでに無効です！") })
        assertEquals(false, env.service.arena("a2")!!.enabled)
    }

    @Test
    fun `create requires exactly one name arg`() {
        val op = env.opPlayer("Op")
        env.run(op, "arena", "create")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena create <arena>") })
        env.run(op, "arena", "create", "other", "extra")
        assertTrue(op.drainMessages().any { it.contains("Incorrect argument") })
    }

    @Test
    fun `reserved name create is rejected`() {
        val op = env.opPlayer("Op")
        env.run(op, "arena", "create", "create")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena create <arena>") })
    }

    @Test
    fun `console can create arena`() {
        val console = env.server.consoleSender
        env.run(console, "arena", "create", "consolearena")
        assertTrue(console.drainMessages().any { it.contains("アリーナ: consolearena を作成しました") })
    }
}
