package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

data class ArenaMatch private constructor(
    val arenaId: Arena.Id,
    val requiredWins: Int,
    val state: ArenaState = ArenaState.Waiting,
    val epoch: Long = 0L,
) {
    init {
        require(requiredWins >= 1) { "requiredWins must be >= 1 (was $requiredWins)" }
        require(epoch >= 0) { "epoch must be >= 0 (was $epoch)" }
    }

    companion object {
        fun new(arenaId: Arena.Id, requiredWins: Int): ArenaMatch = ArenaMatch(arenaId, requiredWins)

        fun restored(arenaId: Arena.Id, requiredWins: Int, state: ArenaState, epoch: Long = 0L): ArenaMatch = ArenaMatch(arenaId, requiredWins, state, epoch)
    }

    val participants: List<Participant> get() = state.participants

    val resolvesVoidFall: Boolean get() = state is ArenaState.Active

    fun matchup(): Pair<Participant, Participant>? = (state as? ArenaState.Active)?.pair

    fun participant(id: Uuid): Participant? = participants.firstOrNull { it.id == id }

    fun winsOf(id: Uuid): Int = (state as? ArenaState.Active)?.winsOf(id) ?: 0

    // Advancing the epoch invalidates running countdowns and pending resolution callbacks.
    private fun advance(next: ArenaState) = copy(state = next, epoch = epoch + 1)

    fun join(participant: Participant): Transition<JoinOutcome> = when (val s = state) {
        ArenaState.Waiting ->
            Transition(advance(ArenaState.OneMore(participant)), JoinOutcome.FirstJoined)

        is ArenaState.OneMore ->
            if (s.participant.id == participant.id) {
                Transition(this, JoinOutcome.Rejected)
            } else {
                Transition(
                    advance(ArenaState.Countdown.of(s.participant, participant)),
                    JoinOutcome.MatchReady,
                )
            }

        else -> Transition(this, JoinOutcome.Rejected)
    }

    fun leaveWaiting(id: Uuid): Transition<LeaveOutcome> {
        val s = state as? ArenaState.OneMore ?: return Transition(this, LeaveOutcome.NotWaiting)
        if (s.participant.id != id) return Transition(this, LeaveOutcome.NotWaiting)
        return Transition(advance(ArenaState.Waiting), LeaveOutcome.Left(s.participant))
    }

    // COUNTDOWN is still pre-match (no teleport, backup, or scoring), so quitting unregisters instead of forfeiting.
    fun forfeit(id: Uuid): Transition<QuitOutcome> {
        val participant = participant(id) ?: return Transition(this, QuitOutcome.NotParticipant)
        val s = state
        if (s is ArenaState.Active) {
            val winner = s.participants.first { it.id != id }
            return Transition(finished(), QuitOutcome.MatchEnded(winner, participant))
        }
        val next: ArenaState = when (s) {
            is ArenaState.Countdown -> ArenaState.OneMore(if (s.first.id == id) s.second else s.first)
            else -> ArenaState.Waiting
        }
        return Transition(advance(next), QuitOutcome.WaitingExit(participant))
    }

    fun recordDefeat(id: Uuid, cause: DefeatCause): Transition<DefeatOutcome> = when (val s = state) {
        is ArenaState.InGame -> defeat(id, s)

        is ArenaState.RoundCountdown ->
            if (cause == DefeatCause.FALL) {
                defeat(id, s)
            } else {
                Transition(this, DefeatOutcome.Rejected)
            }

        else -> Transition(this, DefeatOutcome.Rejected)
    }

    private fun defeat(id: Uuid, state: ArenaState.Active): Transition<DefeatOutcome> {
        val loser = state.participants.firstOrNull { it.id == id }
            ?: return Transition(this, DefeatOutcome.Rejected)
        val winner = state.participants.first { it.id != id }
        // End is judged on the win count before adding; the final kill is not added to the count.
        if (state.winsOf(winner.id) >= requiredWins - 1) {
            return Transition(finished(), DefeatOutcome.MatchFinished(winner, loser))
        }
        val next = ArenaState.RoundCountdown.of(
            first = state.first,
            second = state.second,
            firstWins = state.firstWins + if (state.first.id == winner.id) 1 else 0,
            secondWins = state.secondWins + if (state.second.id == winner.id) 1 else 0,
        )
        val winnerSlot = if (state.first.id == winner.id) SpawnSlot.FIRST else SpawnSlot.SECOND
        val loserSlot = if (state.first.id == loser.id) SpawnSlot.FIRST else SpawnSlot.SECOND
        return Transition(
            advance(next),
            DefeatOutcome.RoundWon(
                round = next.firstWins + next.secondWins,
                winner = SlottedParticipant(winner, winnerSlot),
                loser = SlottedParticipant(loser, loserSlot),
            ),
        )
    }

    fun beginMatch(): Transition<Boolean> = when (val s = state) {
        is ArenaState.Countdown ->
            Transition(advance(ArenaState.InGame.of(s.first, s.second, 0, 0)), true)

        else -> Transition(this, false)
    }

    fun resumeRound(): Transition<Boolean> = when (val s = state) {
        is ArenaState.RoundCountdown ->
            Transition(advance(ArenaState.InGame.of(s.first, s.second, s.firstWins, s.secondWins)), true)

        else -> Transition(this, false)
    }

    fun abort(): Transition<List<Participant>> = Transition(advance(ArenaState.Waiting), participants)

    private fun finished(): ArenaMatch = advance(ArenaState.Waiting)
}
