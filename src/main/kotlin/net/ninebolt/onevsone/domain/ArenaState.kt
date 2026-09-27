package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

sealed interface ArenaState {
    val kind: Kind
    val participants: List<Participant>

    enum class Kind {
        WAITING,
        ONEMORE,
        COUNTDOWN,
        ROUNDCOUNTDOWN,
        INGAME,
        ;

        fun isJoinable(): Boolean = this == WAITING || this == ONEMORE
    }

    data object Waiting : ArenaState {
        override val kind = Kind.WAITING
        override val participants: List<Participant> = emptyList()
    }

    data class OneMore(val participant: Participant) : ArenaState {
        override val kind = Kind.ONEMORE
        override val participants: List<Participant> get() = listOf(participant)
    }

    // participants order maps to spawn slots: first teleports to FIRST, second to SECOND
    sealed interface Paired : ArenaState {
        val first: Participant
        val second: Participant

        val pair: Pair<Participant, Participant> get() = first to second
        override val participants: List<Participant> get() = listOf(first, second)
    }

    data class Countdown private constructor(
        override val first: Participant,
        override val second: Participant,
    ) : Paired {
        override val kind = Kind.COUNTDOWN

        companion object {
            fun of(first: Participant, second: Participant): Countdown {
                require(first.id != second.id) { "duplicate participant ids" }
                return Countdown(first, second)
            }
        }
    }

    sealed interface Active : Paired {
        val firstWins: Int
        val secondWins: Int

        fun winsOf(id: Uuid): Int = when (id) {
            first.id -> firstWins
            second.id -> secondWins
            else -> 0
        }
    }

    data class RoundCountdown private constructor(
        override val first: Participant,
        override val second: Participant,
        override val firstWins: Int,
        override val secondWins: Int,
        val resolving: Boolean,
    ) : Active {
        override val kind = Kind.ROUNDCOUNTDOWN

        fun released(): RoundCountdown = copy(resolving = false)

        companion object {
            fun of(
                first: Participant,
                second: Participant,
                firstWins: Int,
                secondWins: Int,
                resolving: Boolean,
            ): RoundCountdown {
                require(first.id != second.id) { "duplicate participant ids" }
                require(firstWins >= 0 && secondWins >= 0) {
                    "wins must be >= 0 (was $firstWins, $secondWins)"
                }
                return RoundCountdown(first, second, firstWins, secondWins, resolving)
            }
        }
    }

    data class InGame private constructor(
        override val first: Participant,
        override val second: Participant,
        override val firstWins: Int,
        override val secondWins: Int,
    ) : Active {
        override val kind = Kind.INGAME

        companion object {
            fun of(
                first: Participant,
                second: Participant,
                firstWins: Int,
                secondWins: Int,
            ): InGame {
                require(first.id != second.id) { "duplicate participant ids" }
                require(firstWins >= 0 && secondWins >= 0) {
                    "wins must be >= 0 (was $firstWins, $secondWins)"
                }
                return InGame(first, second, firstWins, secondWins)
            }
        }
    }
}
