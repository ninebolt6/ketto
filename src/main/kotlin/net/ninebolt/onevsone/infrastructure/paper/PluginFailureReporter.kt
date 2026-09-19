package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.FailureReporter
import java.util.logging.Level
import java.util.logging.Logger

class PluginFailureReporter(private val logger: () -> Logger) : FailureReporter {
    override fun warn(message: String) {
        logger().warning(message)
    }

    override fun report(context: String, error: Throwable) {
        logger().log(Level.SEVERE, context, error)
    }
}
