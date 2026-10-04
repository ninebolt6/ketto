package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.genericDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.function.Consumer
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PaperPlayersTest {

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
    fun `handle returns null for a player who is not online`() {
        assertNull(env.playerPort.handle(Uuid.random()))
    }

    @Test
    fun `offline id resolution falls back to the async lookup for uncached names`() {
        var resolved: Uuid? = null
        var called = false
        env.playerPort.resolveOfflineId("Ghost") {
            called = true
            resolved = it
        }
        assertFalse(called)

        env.runOneShots()

        assertTrue(called)
        assertNotNull(resolved)
    }

    @Test
    fun `offline id resolution skips callback if plugin is disabled before the async result`() {
        var called = false
        var asyncResult: Consumer<ScheduledTask>? = null
        every { env.asyncScheduler.runNow(any(), any<Consumer<ScheduledTask>>()) } answers {
            asyncResult = arg(1)
            mockk(relaxed = true)
        }

        assertTrue(env.plugin.isEnabled)
        env.playerPort.resolveOfflineId("Ghost") { called = true }
        val result = requireNotNull(asyncResult)
        env.server.pluginManager.disablePlugin(env.plugin)
        assertFalse(env.plugin.isEnabled)

        result.accept(mockk(relaxed = true))

        assertFalse(called)
    }

    @Test
    fun `reset vitals restores health food and extinguishes fire`() {
        val p = env.player("Alice")
        p.health = 4.0
        p.foodLevel = 3
        p.fireTicks = 100

        env.playerPort.handle(p.uuid)!!.resetVitals()

        assertEquals(20.0, p.health)
        assertEquals(20, p.foodLevel)
        assertEquals(0, p.fireTicks)
    }

    @Test
    fun `reset vitals leaves a dead player untouched`() {
        val p = env.player("Alice")
        p.simulateDamage(100.0, genericDamage())

        env.playerPort.handle(p.uuid)!!.resetVitals()

        assertEquals(0.0, p.health)
        assertTrue(p.isDead)
    }

    @Test
    fun `teleport to an unloaded world warns and skips`() {
        val p = env.player("Alice")
        val warnings = mutableListOf<String>()
        env.logger.addHandler(
            object : Handler() {
                override fun publish(record: LogRecord) {
                    warnings += record.message
                }

                override fun flush() {}

                override fun close() {}
            },
        )
        val from = p.location

        env.playerPort.handle(p.uuid)!!.teleport(WorldPosition.new("missing-world", 1.0, 64.0, 1.0))

        assertEquals(from, p.location)
        assertTrue(warnings.any { "missing-world" in it })
    }
}
