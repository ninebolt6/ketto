package net.ninebolt.onevsone.domain

sealed interface QuitOutcome {
    data object NotParticipant : QuitOutcome
    data class WaitingExit(val participant: Participant) : QuitOutcome
    data class MatchEnded(val winner: Participant, val loser: Participant) : QuitOutcome
}
