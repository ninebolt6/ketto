package net.ninebolt.onevsone.application.port

// corrupt files and I/O errors; user-facing rejections are use-case results, not this
class PersistenceFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
