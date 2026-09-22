package net.ninebolt.onevsone.infrastructure.paper.fixtures

import java.util.logging.Logger

internal class RecordingLogger : Logger("test", null) {
    val warnings = mutableListOf<String>()

    override fun warning(msg: String) {
        warnings += msg
    }
}
