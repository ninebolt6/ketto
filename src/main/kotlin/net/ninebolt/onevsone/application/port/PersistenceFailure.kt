package net.ninebolt.onevsone.application.port

/**
 * Failure of persistence or external-reference processing. Indicates corrupt
 * files or I/O errors. Ordinary user-facing rejections (cannot join, etc.) are
 * expressed as use-case results, not this.
 */
class PersistenceFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
