package net.ninebolt.onevsone.application.port

/**
 * Failure notification for saves, restores, etc. The logging implementation
 * lives outside (infrastructure). Ordinary user-facing rejections are
 * expressed as use-case results, not here.
 */
interface FailureReporter {
    fun warn(message: String)
    fun report(context: String, error: Throwable)
}

/** Shared failure path that swallows PersistenceFailure into a warn. */
internal inline fun FailureReporter.warnOnFailure(message: String, block: () -> Unit) {
    try {
        block()
    } catch (e: PersistenceFailure) {
        warn(message)
    }
}
