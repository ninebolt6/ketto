package net.ninebolt.onevsone.domain

sealed interface DefeatOutcome {
    /** Ordinary rejection (wrong state, not full, duplicate notification while resolving) */
    data object Rejected : DefeatOutcome
    /** Only the round is decided. round is the finished round number (total wins).
     *  The resolution guard is released against the post-transition match's epoch. */
    data class RoundWon(
        val round: Int,
        val winner: Participant,
        val loser: Participant
    ) : DefeatOutcome
    /** Match ends on reaching the required wins. Keeps the current behavior of not adding the final kill to the win count. */
    data class MatchFinished(val winner: Participant, val loser: Participant) : DefeatOutcome
}
