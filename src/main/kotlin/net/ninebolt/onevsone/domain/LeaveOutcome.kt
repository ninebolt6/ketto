package net.ninebolt.onevsone.domain

sealed interface LeaveOutcome {
    data class Left(val participant: Participant) : LeaveOutcome
    data object NotWaiting : LeaveOutcome
}
