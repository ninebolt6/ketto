package net.ninebolt.onevsone.domain

sealed interface QuitOutcome {
    data object NotParticipant : QuitOutcome
    /** Pre-match exit (ONEMORE/WAITING/COUNTDOWN/not full): unregister only */
    data class WaitingExit(val participant: Participant) : QuitOutcome
    /** Disconnect while a match is in progress: ends the match as a forfeit */
    data class MatchEnded(val winner: Participant, val loser: Participant) : QuitOutcome
}
