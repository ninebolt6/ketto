package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
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
import kotlin.test.assertTrue

class PaperSchedulerTest {

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
    fun `a throwing repeating action is logged and the task is cancelled`() {
        val records = capturePluginLog()
        var runs = 0
        env.schedulerPort.repeat(0, 1) {
            runs++
            throw IllegalStateException("boom")
        }
        env.server.scheduler.performTicks(1)
        env.server.scheduler.performTicks(3)
        assertEquals(1, runs)
        val failure = records.single { it.message.contains("Repeating task failed") }
        assertEquals(Level.SEVERE, failure.level)
        assertTrue(failure.thrown is IllegalStateException)
    }

    @Test
    fun `a throwing one shot action is logged without propagating`() {
        val records = capturePluginLog()
        env.schedulerPort.schedule(0) { throw IllegalStateException("boom") }
        env.server.scheduler.performTicks(1)
        val failure = records.single { it.message.contains("Scheduled task failed") }
        assertEquals(Level.SEVERE, failure.level)
        assertTrue(failure.thrown is IllegalStateException)
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
