package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.Component
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperPresentationTest {

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

    private fun captureLog(): MutableList<String> {
        val messages = mutableListOf<String>()
        env.logger.addHandler(
            object : Handler() {
                override fun publish(record: LogRecord) {
                    messages += record.message
                }

                override fun flush() {}

                override fun close() {}
            },
        )
        return messages
    }

    @Test
    fun `sign update for an unloaded world warns and leaves other worlds untouched`() {
        val arena = env.newArena()
        val sign = env.signBlock(1, 64, 1)
        val warnings = captureLog()

        env.presentation.updateSign(arena, BlockPosition.new("missing-world", 1, 64, 1), ArenaState.ONEMORE)

        assertTrue(warnings.any { "missing-world" in it })
        assertEquals(Component.empty(), (sign.state as Sign).getSide(Side.FRONT).line(0))
    }

    @Test
    fun `sign update on a non sign block is ignored`() {
        val arena = env.newArena()
        val block = env.plainBlock()
        val warnings = captureLog()

        env.presentation.updateSign(arena, BlockPosition.new("world", 9, 64, 9), ArenaState.ONEMORE)

        assertFalse(block.state is Sign)
        assertTrue(warnings.none { "not loaded" in it })
    }

    @Test
    fun `scoreboard update skips an offline participant and still updates the online one`() {
        val (p1, p2) = env.twoPlayerIngame()
        val match = env.service.matchOf("arena1")!!
        env.disconnectWithoutQuitHandler(p2)

        env.presentation.updateScoreboard(match)

        assertSame(env.boards.last(), p1.scoreboard)
    }
}
