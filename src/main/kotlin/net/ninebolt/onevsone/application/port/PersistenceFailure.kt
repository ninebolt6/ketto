package net.ninebolt.onevsone.application.port

import java.util.logging.Level
import java.util.logging.Logger

// corrupt files and I/O errors; user-facing rejections are use-case results, not this
class PersistenceFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal inline fun <T> Logger.warnOnFailure(message: String, block: () -> T): T? {
    try {
        return block()
    } catch (e: PersistenceFailure) {
        log(Level.WARNING, message, e)
        return null
    }
}
