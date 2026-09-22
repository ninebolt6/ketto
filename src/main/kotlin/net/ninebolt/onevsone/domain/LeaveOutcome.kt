package net.ninebolt.onevsone.domain

sealed interface LeaveOutcome {
    /** participant is the player who left. */
    data class Left(val participant: Participant) : LeaveOutcome
    /** Cannot leave outside ONEMORE (or when not participating) */
    data object NotWaiting : LeaveOutcome
}
