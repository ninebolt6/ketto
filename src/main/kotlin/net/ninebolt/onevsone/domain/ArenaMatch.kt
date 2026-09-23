package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/**
 * Aggregate owning the participants and progression state of one arena.
 * Immutable: every operation returns a Transition carrying the new state; this
 * instance never changes. Holds no Bukkit, scheduler, or persistence — it only
 * advances a generation (epoch) for timing control.
 * participants order = join order = spawn slot number.
 */
data class ArenaMatch private constructor(
    val arenaId: Arena.Id,
    val requiredWins: Int,
    val state: ArenaState = ArenaState.WAITING,
    val participants: List<Participant> = emptyList(),
    val wins: Map<Uuid, Int> = emptyMap(),
    /**
     * Duplicate-resolution guard held until the defeat resolution
     * (respawn/re-equip) completes. Prevents double scoring within the same
     * resolution window.
     */
    val resolving: Boolean = false,
    val epoch: Long = 0L
) {
    companion object {
        // participants[i] teleports to the spawn slot with index i, so capacity equals the spawn slot count
        val MAX_PARTICIPANTS = SpawnSlot.entries.size

        /** A fresh aggregate (WAITING, 0 participants). */
        fun new(arenaId: Arena.Id, requiredWins: Int): ArenaMatch {
            require(requiredWins >= 1) { "requiredWins must be >= 1 (was $requiredWins)" }
            return ArenaMatch(arenaId, requiredWins)
        }

        /**
         * Full-state reconstruction from a snapshot etc. The invariants the
         * transition functions maintain (state<->participant count, unique
         * participants, wins only for participants, resolving only in
         * ROUNDCOUNTDOWN) are validated here.
         */
        fun restored(
            arenaId: Arena.Id,
            requiredWins: Int,
            state: ArenaState,
            participants: List<Participant>,
            wins: Map<Uuid, Int>,
            resolving: Boolean = false,
            epoch: Long = 0L
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

    val joinable: Boolean get() = state.isJoinable()

    val full: Boolean get() = participants.size == MAX_PARTICIPANTS

    /** Whether a fall at/below the world minimum height resolves as a defeat (a fall-accepting state with 2 participants). */
    val resolvesVoidFall: Boolean
        get() = state.acceptsDefeat(DefeatCause.FALL) && full

    val canBeginMatch: Boolean get() = state == ArenaState.COUNTDOWN && full

    val canResumeRound: Boolean get() = state == ArenaState.ROUNDCOUNTDOWN && full

    /** A match is in progress (ROUNDCOUNTDOWN/INGAME with both participants). Used for matchup display etc. */
    val inProgress: Boolean get() =
        (state == ArenaState.INGAME || state == ArenaState.ROUNDCOUNTDOWN) && full

    /** The two opponents in join order, present only while the match is in progress. */
    fun matchup(): Pair<Participant, Participant>? =
        if (inProgress) participants[SpawnSlot.FIRST.index] to participants[SpawnSlot.SECOND.index] else null

    fun participant(id: Uuid): Participant? = participants.firstOrNull { it.id == id }

    /** A participant's spawn slot (join order). null if not participating. */
    fun slotOf(id: Uuid): SpawnSlot? =
        SpawnSlot.ofIndex(participants.indexOfFirst { it.id == id })

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

    /** Leaving does not touch the inventory (the match has not started). */
    fun leaveWaiting(id: Uuid): Transition<LeaveOutcome> {
        if (state != ArenaState.ONEMORE) return Transition(this, LeaveOutcome.NotWaiting)
        val participant = participant(id) ?: return Transition(this, LeaveOutcome.NotWaiting)
        return Transition(
            copy(
                participants = participants.filterNot { it.id == id },
                state = ArenaState.WAITING,
                epoch = epoch + 1
            ),
            LeaveOutcome.Left(participant)
        )
    }

    /**
     * Before the match starts this only unregisters; in progress it ends the
     * match as a forfeit with the opponent as winner. The initial COUNTDOWN is
     * pre-match (no teleport, backup, or scoring), so it does not become a
     * forfeit — the remaining player keeps waiting in ONEMORE.
     */
    fun forfeit(id: Uuid): Transition<QuitOutcome> {
        val participant = participant(id) ?: return Transition(this, QuitOutcome.NotParticipant)
        if (state == ArenaState.ONEMORE || state == ArenaState.WAITING || state == ArenaState.COUNTDOWN || !full) {
            val remaining = participants - participant
            return Transition(
                copy(
                    participants = remaining,
                    state = if (remaining.isEmpty()) ArenaState.WAITING else ArenaState.ONEMORE,
                    epoch = epoch + 1
                ),
                QuitOutcome.WaitingExit(participant)
            )
        }
        val winner = participants.first { it.id != id }
        return Transition(finished(), QuitOutcome.MatchEnded(winner, participant))
    }

    /**
     * Defeat notification for death/fall. If accepted, yields RoundWon or
     * MatchFinished. Keeps the current behavior: death is accepted only in
     * INGAME; falls are accepted in INGAME/ROUNDCOUNTDOWN.
     */
    fun recordDefeat(id: Uuid, cause: DefeatCause): Transition<DefeatOutcome> {
        if (!state.acceptsDefeat(cause)) return Transition(this, DefeatOutcome.Rejected)
        if (!full || resolving) return Transition(this, DefeatOutcome.Rejected)
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
            wins = wins + (winner.id to winsOf(winner.id) + 1)
        )
        return Transition(
            next,
            DefeatOutcome.RoundWon(
                round = next.wins.values.sum(),
                winner = winner,
                loser = loser
            )
        )
    }

    /** outcome is true when accepted. */
    fun beginMatch(): Transition<Boolean> =
        if (canBeginMatch) {
            Transition(copy(state = ArenaState.INGAME), true)
        } else {
            Transition(this, false)
        }

    /** Returns to INGAME when ROUNDCOUNTDOWN completes. The resolution guard is also released here. */
    fun resumeRound(): Transition<Boolean> =
        if (state == ArenaState.ROUNDCOUNTDOWN) {
            Transition(copy(state = ArenaState.INGAME, resolving = false), true)
        } else {
            Transition(this, false)
        }

    /**
     * End of the defeat-resolution window (post-respawn re-equip complete, or
     * the next tick of a non-death round). The guard is released only when the
     * epoch matches the one at RoundWon issuance. A generation mismatch
     * (aborted, next round already progressed, etc.) is a no-op.
     */
    fun releaseResolution(epoch: Long): ArenaMatch =
        if (this.epoch == epoch) copy(resolving = false) else this

    /** Running countdowns and pending resolution callbacks are invalidated by the generation advance. */
    fun abort(): Transition<List<Participant>> =
        Transition(
            copy(
                state = ArenaState.WAITING,
                participants = emptyList(),
                wins = emptyMap(),
                resolving = false,
                epoch = epoch + 1
            ),
            participants
        )

    private fun finished(): ArenaMatch =
        copy(
            state = ArenaState.WAITING,
            participants = emptyList(),
            wins = emptyMap(),
            resolving = false,
            epoch = epoch + 1
        )
}
