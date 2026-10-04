package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.spyk
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.genericDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.view
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaperArenaFailureTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv
    private var logTarget: Pair<Logger, Handler>? = null

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
    }

    @AfterEach
    fun tearDown() {
        logTarget?.let { (logger, handler) -> logger.removeHandler(handler) }
        env.close()
    }

    @Test
    fun `winner stats failure does not prevent final death cleanup`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val spyStats = spyk(env.statsRepository)
        env.rebuildWith(statsRepository = spyStats)
        val records = capturePluginLog()
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        every { spyStats.save(match { it.playerId == p1.uuid }) } throws PersistenceException("disk gone")
        p2.simulateDamage(100.0, genericDamage())
        assertEquals(ArenaState.Kind.WAITING, env.view().state.kind)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.sessions.findArenaIdOf(p1.uuid))
        assertNull(env.sessions.findArenaIdOf(p2.uuid))
        assertSame(env.mainBoard, p1.scoreboard)
        assertNull(env.statsRepository.find(p1.uuid))
        assertEquals(1, env.statsRepository.find(p2.uuid)!!.losses)
        val failedLog = records.single { it.message.contains("Failed to record") }
        assertEquals(Level.SEVERE, failedLog.level)
        assertTrue(failedLog.thrown is PersistenceException)
    }

    private fun capturePluginLog(): MutableList<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val logger = env.plugin.logger
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        logger.addHandler(handler)
        logTarget = logger to handler
        return records
    }
}
