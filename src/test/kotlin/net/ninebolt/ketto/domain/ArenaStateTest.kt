package net.ninebolt.ketto.domain

import net.ninebolt.ketto.domain.fixtures.arenaId
import net.ninebolt.ketto.domain.fixtures.carol
import net.ninebolt.ketto.domain.fixtures.stateForKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ArenaStateTest {

    @Test
    fun `isJoinable matches whether a fresh participant can join`() {
        ArenaState.Kind.entries.forEach { kind ->
            val match = ArenaMatch.restored(arenaId("a1"), requiredWins = 3, state = stateForKind(kind))
            val joined = match.join(carol).outcome != JoinOutcome.Rejected
            assertEquals(kind.isJoinable(), joined, kind.name)
        }
    }
}
