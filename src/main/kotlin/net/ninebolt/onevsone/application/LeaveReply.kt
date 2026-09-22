package net.ninebolt.onevsone.application

sealed interface LeaveReply {
    data object Left : LeaveReply
    /** Leaving is not allowed outside ONEMORE. */
    data object NotWaiting : LeaveReply
    data object NotJoined : LeaveReply
}
