package net.ninebolt.ketto.domain

sealed interface DefeatOutcome {
    data object Rejected : DefeatOutcome
    data class RoundWon(
        val round: Int,
        val winner: SlottedParticipant,
        val loser: SlottedParticipant,
    ) : DefeatOutcome
    data class MatchEnded(val winner: Participant, val loser: Participant) : DefeatOutcome
}
