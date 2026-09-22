package net.ninebolt.onevsone.application

/**
 * Use-case result. Conversion to message text happens on the caller's side
 * (infrastructure).
 */
sealed interface JoinReply {
    /** Registered as the first player; now waiting in ONEMORE. */
    data object JoinedWaiting : JoinReply
    /** Registered as the second player; the initial countdown has started. */
    data object JoinedStarting : JoinReply
    data object AlreadyJoined : JoinReply
    data object NotEnabled : JoinReply
    /** Cannot join: match in progress / full / holder of an unrestored backup is dead, etc. */
    data object InMatch : JoinReply
    data object NotFound : JoinReply
}
