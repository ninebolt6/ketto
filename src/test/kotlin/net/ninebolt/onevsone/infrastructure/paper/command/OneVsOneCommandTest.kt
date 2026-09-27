package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.runCommand
import net.ninebolt.onevsone.infrastructure.paper.fixtures.tabComplete
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.command.CommandSourceStackMock
import org.mockbukkit.mockbukkit.command.brigadier.PaperCommandsMock
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        env.runCommand(p)
        assertTrue(p.drainMessages().any { it.contains("/1vs1 stats [player] | /1vs1 leave") })
    }

    @Test
    fun `unknown input is a syntax error`() {
        val p = env.player("Alice")
        env.runCommand(p, "bogus")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
        env.runCommand(p, "arena", "bogus")
        assertTrue(p.drainMessages().any { it.contains("That arena does not exist") })
        env.runCommand(p, "arena", "bogus", "bogus")
        assertTrue(p.drainMessages().any { it.contains("Incorrect argument") })
    }

    @Test
    fun `bare arena usage depends on op`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena create <arena>") })

        val p = env.player("Alice")
        env.runCommand(p, "arena")
        val msgs = p.drainMessages()
        assertTrue(msgs.any { it.contains("/1vs1 arena <arena>") })
        assertTrue(msgs.none { it.contains("create") })
    }

    @Test
    fun `explicit permission grants admin ops to non op`() {
        val p = env.player("Alice")
        p.addAttachment(env.plugin, ADMIN_PERMISSION, true)
        env.newArena()
        env.runCommand(p, "arena", "arena1", "enable")
        assertTrue(p.drainMessages().any { it.contains("already enabled") })
    }

    @Test
    fun `non ascii and quoted arena names are accepted`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "create", "闘技場")
        assertTrue(op.drainMessages().any { it.contains("Created arena: 闘技場") })
        env.runCommand(op, "arena", "create", "\"my arena\"")
        assertTrue(op.drainMessages().any { it.contains("Created arena: my arena") })
        assertTrue(env.admin.arenaNames().containsAll(listOf("闘技場", "my arena")))
    }

    @Test
    fun `tab completion keeps the registered name case`() {
        val op = env.opPlayer("Op")
        env.newArena("Arena1")
        assertTrue(env.tabComplete(op, "").containsAll(listOf("stats", "leave", "lobby", "arena")))
        assertEquals(listOf("Arena1", "create").sorted(), env.tabComplete(op, "arena", "").sorted())
        assertEquals(listOf("Arena1"), env.tabComplete(op, "arena", "arena"))
        assertEquals(
            listOf("info", "remove", "enable", "disable", "spawn", "kit", "sign").sorted(),
            env.tabComplete(op, "arena", "Arena1", "").sorted(),
        )
        assertEquals(listOf("sign", "spawn"), env.tabComplete(op, "arena", "Arena1", "s").sorted())
        assertEquals(listOf("1", "2"), env.tabComplete(op, "arena", "Arena1", "spawn", "set", ""))
    }

    @Test
    fun `ops-only nodes are restricted for non ops`() {
        val p = env.player("Alice")
        val op = env.opPlayer("Op")
        env.newArena("Arena1")
        env.tabComplete(p) // the command tree registers on first dispatch
        val nonOp = CommandSourceStackMock.from(p)
        val asOp = CommandSourceStackMock.from(op)
        val root = PaperCommandsMock.INSTANCE.dispatcherInternal.root.getChild("1vs1")
        val arena = root.getChild("arena")
        val arenaArg = arena.getChild("arena")
        assertTrue(root.getChild("stats").canUse(nonOp) && root.getChild("leave").canUse(nonOp))
        assertFalse(root.getChild("lobby").canUse(nonOp))
        assertFalse(arena.getChild("create").canUse(nonOp))
        arenaArg.children.forEach { assertEquals(it.name == "info", it.canUse(nonOp), it.name) }
        assertTrue(root.getChild("lobby").canUse(asOp))
        assertTrue(arena.getChild("create").canUse(asOp))
        arenaArg.children.forEach { assertTrue(it.canUse(asOp), it.name) }
        assertTrue(env.tabComplete(p, "arena", "").contains("Arena1"))
    }

    @Test
    fun `bare lobby shows usage`() {
        val p = env.opPlayer("Alice")
        env.runCommand(p, "lobby")
        assertTrue(p.drainMessages().any { it.contains("/1vs1 lobby set") })
    }

    @Test
    fun `bare sign shows ops usage`() {
        val op = env.opPlayer("Op")
        env.runCommand(op, "arena", "x", "sign")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena <arena> sign <set|remove>") })
    }
}
