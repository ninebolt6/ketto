package net.ninebolt.onevsone.domain

sealed interface JoinOutcome {
    /** First player: transitions to ONEMORE and waits */
    data object FirstJoined : JoinOutcome
    /** Second player: transitions to COUNTDOWN; the initial countdown is required */
    data object MatchReady : JoinOutcome
    /** Join rejected (in match / full / duplicate). The enabled check is on the application side. */
    data object Rejected : JoinOutcome
}
