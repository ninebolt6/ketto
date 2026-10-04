package net.ninebolt.onevsone.domain

sealed interface ForfeitOutcome {
    data object NotParticipant : ForfeitOutcome
    data class WaitingExit(val participant: Participant) : ForfeitOutcome
    data class MatchEnded(val winner: Participant, val loser: Participant) : ForfeitOutcome
}
