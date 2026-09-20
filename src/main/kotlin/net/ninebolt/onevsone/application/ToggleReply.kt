package net.ninebolt.onevsone.application

sealed interface ToggleReply {
    data object Changed : ToggleReply
    data object AlreadyEnabled : ToggleReply
    data object AlreadyDisabled : ToggleReply
    data object NotFound : ToggleReply
}
