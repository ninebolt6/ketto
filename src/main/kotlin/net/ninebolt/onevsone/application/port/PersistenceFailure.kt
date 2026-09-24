package net.ninebolt.onevsone.application.port

import java.util.logging.Logger

// corrupt files and I/O errors; user-facing rejections are use-case results, not this
class PersistenceFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal inline fun Logger.warnOnFailure(message: String, block: () -> Unit) {
    try {
        block()
    } catch (e: PersistenceFailure) {
        warning(message)
    }
}
