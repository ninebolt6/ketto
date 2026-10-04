package net.ninebolt.ketto.infrastructure.paper.fixtures

import java.util.logging.Logger

internal class RecordingLogger : Logger("test", null) {
    val warnings = mutableListOf<String>()
    val infos = mutableListOf<String>()

    override fun warning(msg: String) {
        warnings += msg
    }

    override fun info(msg: String) {
        infos += msg
    }
}
