package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

data class ArenaMatch private constructor(
    val arenaId: Arena.Id,
    val requiredWins: Int,
    val state: ArenaState = ArenaState.WAITING,
    val participants: List<Participant> = emptyList(),
    val wins: Map<Uuid, Int> = emptyMap(),
    val resolving: Boolean = false,
    val epoch: Long = 0L,
) {
    companion object {
        // participants[i] teleports to the spawn slot with index i, so capacity equals the spawn slot count
        val MAX_PARTICIPANTS = SpawnSlot.entries.size

        fun new(arenaId: Arena.Id, requiredWins: Int): ArenaMatch {
            require(requiredWins >= 1) { "requiredWins must be >= 1 (was $requiredWins)" }
            return ArenaMatch(arenaId, requiredWins)
        }

        fun restored(
            arenaId: Arena.Id,
            requiredWins: Int,
            state: ArenaState,
            participants: List<Participant>,
            wins: Map<Uuid, Int>,
            resolving: Boolean = false,
            epoch: Long = 0L,
        ): ArenaMatch {
            require(requiredWins >= 1) { "requiredWins must be >= 1 (was $requiredWins)" }
            require(participants.size == expectedParticipants(state)) {
                "state $state expects ${expectedParticipants(state)} participants (was ${participants.size})"
            }
            require(participants.distinctBy { it.id }.size == participants.size) {
                "duplicate participant ids"
            }
            val ids = participants.mapTo(HashSet()) { it.id }
            require(wins.keys.all { it in ids }) { "wins recorded for non-participant" }
            require(!resolving || state == ArenaState.ROUNDCOUNTDOWN) {
                "resolving is only valid in ROUNDCOUNTDOWN (was $state)"
            }
            require(epoch >= 0) { "epoch must be >= 0 (was $epoch)" }
            return ArenaMatch(arenaId, requiredWins, state, participants, wins, resolving, epoch)
        }

        private fun expectedParticipants(state: ArenaState): Int = when (state) {
            ArenaState.WAITING -> 0
            ArenaState.ONEMORE -> 1
            ArenaState.COUNTDOWN, ArenaState.ROUNDCOUNTDOWN, ArenaState.INGAME -> MAX_PARTICIPANTS
        }
    }

    val full: Boolean get() = participants.size == MAX_PARTICIPANTS

    val resolvesVoidFall: Boolean
        get() = state.acceptsDefeat(DefeatCause.FALL) && full

    val canBeginMatch: Boolean get() = state == ArenaState.COUNTDOWN && full

    val canResumeRound: Boolean get() = state == ArenaState.ROUNDCOUNTDOWN && full

    val inProgress: Boolean get() =
        (state == ArenaState.INGAME || state == ArenaState.ROUNDCOUNTDOWN) && full

    fun matchup(): Pair<Participant, Participant>? = if (inProgress) fullMatchup() else null

    fun fullMatchup(): Pair<Participant, Participant>? = if (full) participants[SpawnSlot.FIRST.index] to participants[SpawnSlot.SECOND.index] else null

    fun participant(id: Uuid): Participant? = participants.firstOrNull { it.id == id }

    fun slotOf(id: Uuid): SpawnSlot? = SpawnSlot.ofIndex(participants.indexOfFirst { it.id == id })

    fun participantAt(slot: SpawnSlot): Participant? = participants.getOrNull(slot.index)

    fun winsOf(id: Uuid): Int = wins[id] ?: 0

    fun join(participant: Participant): Transition<JoinOutcome> {
        if (!state.isJoinable() || full || participants.any { it.id == participant.id }) {
            return Transition(this, JoinOutcome.Rejected)
        }
        val joined = participants + participant
        return if (joined.size == 1) {
            Transition(copy(participants = joined, state = ArenaState.ONEMORE), JoinOutcome.FirstJoined)
        } else {
            Transition(copy(participants = joined, state = ArenaState.COUNTDOWN), JoinOutcome.MatchReady)
        }
    }

    fun leaveWaiting(id: Uuid): Transition<LeaveOutcome> {
        if (state != ArenaState.ONEMORE) return Transition(this, LeaveOutcome.NotWaiting)
        val participant = participant(id) ?: return Transition(this, LeaveOutcome.NotWaiting)
        return Transition(
            copy(
                participants = participants.filterNot { it.id == id },
                state = ArenaState.WAITING,
                epoch = epoch + 1,
            ),
            LeaveOutcome.Left(participant),
        )
    }

    // COUNTDOWN is still pre-match (no teleport, backup, or scoring), so quitting unregisters instead of forfeiting.
    fun forfeit(id: Uuid): Transition<QuitOutcome> {
        val participant = participant(id) ?: return Transition(this, QuitOutcome.NotParticipant)
        if (!inProgress) {
            val remaining = participants - participant
            return Transition(
                copy(
                    participants = remaining,
                    state = if (remaining.isEmpty()) ArenaState.WAITING else ArenaState.ONEMORE,
                    epoch = epoch + 1,
                ),
                QuitOutcome.WaitingExit(participant),
            )
        }
        val winner = participants.first { it.id != id }
        return Transition(finished(), QuitOutcome.MatchEnded(winner, participant))
    }

    fun recordDefeat(id: Uuid, cause: DefeatCause): Transition<DefeatOutcome> {
        if (!state.acceptsDefeat(cause)) return Transition(this, DefeatOutcome.Rejected)
        // Defeat-accepting states are entered only with a full lobby
        if (resolving) return Transition(this, DefeatOutcome.Rejected)
        val loser = participant(id) ?: return Transition(this, DefeatOutcome.Rejected)
        val winner = participants.first { it.id != id }
        // End is judged on the win count before adding; the final kill is not added to the count.
        if (winsOf(winner.id) >= requiredWins - 1) {
            return Transition(finished(), DefeatOutcome.MatchFinished(winner, loser))
        }
        val next = copy(
            state = ArenaState.ROUNDCOUNTDOWN,
            resolving = true,
            epoch = epoch + 1,
            wins = wins + (winner.id to winsOf(winner.id) + 1),
        )
        return Transition(
            next,
            DefeatOutcome.RoundWon(
                round = next.wins.values.sum(),
                winner = winner,
                loser = loser,
            ),
        )
    }

    fun beginMatch(): Transition<Boolean> = if (canBeginMatch) {
        Transition(copy(state = ArenaState.INGAME), true)
    } else {
        Transition(this, false)
    }

    fun resumeRound(): Transition<Boolean> = if (state == ArenaState.ROUNDCOUNTDOWN) {
        Transition(copy(state = ArenaState.INGAME, resolving = false), true)
    } else {
        Transition(this, false)
    }

    // A release callback is stale once the match has advanced, so an epoch mismatch must be a no-op.
    fun releaseResolution(epoch: Long): ArenaMatch = if (this.epoch == epoch) copy(resolving = false) else this

    // Advancing the epoch invalidates running countdowns and pending resolution callbacks.
    fun abort(): Transition<List<Participant>> = Transition(
        copy(
            state = ArenaState.WAITING,
            participants = emptyList(),
            wins = emptyMap(),
            resolving = false,
            epoch = epoch + 1,
        ),
        participants,
    )

    private fun finished(): ArenaMatch = copy(
        state = ArenaState.WAITING,
        participants = emptyList(),
        wins = emptyMap(),
        resolving = false,
        epoch = epoch + 1,
    )
}
