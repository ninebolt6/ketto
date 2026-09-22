package net.ninebolt.onevsone.domain.fixtures

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant

/** Participants are immutable, so the same instances are shared across tests. */

internal val alice = Participant.new("Alice")
internal val bob = Participant.new("Bob")
internal val carol = Participant.new("Carol")
internal val dave = Participant.new("Dave")

internal fun match(requiredWins: Int = 3) = ArenaMatch.new(Arena.Id.new("arena1"), requiredWins)

internal fun startedMatch(requiredWins: Int = 3): ArenaMatch {
    var m = match(requiredWins)
    m = m.join(alice).match
    m = m.join(bob).match
    val began = m.beginMatch()
    check(began.outcome)
    return began.match
}
