package net.ninebolt.onevsone.application

sealed interface LeaveReply {
    data object Left : LeaveReply
    /** ONEMORE 以外では退出不可。 */
    data object NotWaiting : LeaveReply
    data object NotJoined : LeaveReply
}
