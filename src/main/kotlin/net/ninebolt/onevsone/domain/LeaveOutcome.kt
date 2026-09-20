package net.ninebolt.onevsone.domain

sealed interface LeaveOutcome {
    /** participant は退出者。 */
    data class Left(val participant: Participant) : LeaveOutcome
    /** ONEMORE 以外(または非参加者)では退出できない */
    data object NotWaiting : LeaveOutcome
}
