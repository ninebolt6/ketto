package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import net.ninebolt.onevsone.infrastructure.paper.fixtures.tab
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneVsOneCommandTest {

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
    fun `no args shows usage`() {
        val p = env.player("Alice")
        env.run(p)
        assertTrue(p.drainMessages().any { it.contains("/1vs1 stats [player] | /1vs1 leave") })
    }

    @Test
    fun `unknown input is a syntax error`() {
        val p = env.player("Alice")
        env.run(p, "bogus")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
        env.run(p, "arena", "bogus")
        assertTrue(p.drainMessages().any { it.contains("そのアリーナは存在しません") })
        env.run(p, "arena", "bogus", "bogus")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
    }

    @Test
    fun `subcommands are case sensitive`() {
        val p = env.player("Alice")
        env.run(p, "ARENA")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
    }

    @Test
    fun `bare arena usage depends on op`() {
        val op = env.opPlayer("Op")
        env.run(op, "arena")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena create <arena>") })

        val p = env.player("Alice")
        env.run(p, "arena")
        val msgs = p.drainMessages()
        assertTrue(msgs.any { it.contains("/1vs1 arena <arena>") })
        assertTrue(msgs.none { it.contains("create") })
    }

    @Test
    fun `non op admin ops denied`() {
        val p = env.player("Alice")
        env.newArena()
        env.run(p, "lobby", "set")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
        env.run(p, "arena", "create", "x")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
        env.run(p, "arena", "arena1", "spawn", "set", "1")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
        env.run(p, "arena", "arena1", "sign")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
        env.run(p, "arena", "arena1", "remove")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
    }

    @Test
    fun `explicit permission grants admin ops to non op`() {
        val p = env.player("Alice")
        p.addAttachment(env.plugin, ADMIN_PERMISSION, true)
        env.newArena()
        env.run(p, "arena", "arena1", "enable")
        assertTrue(p.drainMessages().any { it.contains("すでに有効") })
    }

    @Test
    fun `non ascii and quoted arena names are accepted`() {
        val op = env.opPlayer("Op")
        env.run(op, "arena", "create", "闘技場")
        assertTrue(op.drainMessages().any { it.contains("闘技場 を作成しました") })
        env.run(op, "arena", "create", "\"my arena\"")
        assertTrue(op.drainMessages().any { it.contains("my arena を作成しました") })
        assertTrue(env.admin.arenaNames().containsAll(listOf("闘技場", "my arena")))
    }

    @Test
    fun `tab completion keeps the registered name case`() {
        val op = env.opPlayer("Op")
        env.newArena("Arena1")
        assertTrue(env.tab(op, "").containsAll(listOf("stats", "leave", "lobby", "arena")))
        assertEquals(listOf("Arena1", "create").sorted(), env.tab(op, "arena", "").sorted())
        assertEquals(listOf("Arena1"), env.tab(op, "arena", "arena"))
        assertEquals(
            listOf("info", "remove", "enable", "disable", "spawn", "kit", "sign"),
            env.tab(op, "arena", "Arena1", ""),
        )
        assertEquals(listOf("spawn", "sign"), env.tab(op, "arena", "Arena1", "s"))
        assertEquals(listOf("1", "2"), env.tab(op, "arena", "Arena1", "spawn", "set", ""))
    }

    @Test
    fun `tab completion hides ops-only namespaces from non ops`() {
        val p = env.player("Alice")
        env.newArena("Arena1")
        assertTrue(env.tab(p, "").none { it == "lobby" })
        assertEquals(listOf("Arena1"), env.tab(p, "arena", ""))
        assertEquals(listOf("info"), env.tab(p, "arena", "Arena1", ""))
    }
}
