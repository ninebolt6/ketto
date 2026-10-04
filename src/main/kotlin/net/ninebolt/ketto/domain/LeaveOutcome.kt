package net.ninebolt.ketto.domain

sealed interface LeaveOutcome {
    data class Left(val participant: Participant) : LeaveOutcome
    data object NotWaiting : LeaveOutcome
}
