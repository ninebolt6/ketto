package net.ninebolt.ketto.domain

sealed interface JoinOutcome {
    data object FirstJoined : JoinOutcome
    data object MatchReady : JoinOutcome

    // The enabled check is on the application side.
    data object Rejected : JoinOutcome
}
