package net.ninebolt.ketto.domain.fixtures

import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.ArenaMatch
import net.ninebolt.ketto.domain.ArenaState
import net.ninebolt.ketto.domain.Participant

internal val alice = Participant.new("Alice")
internal val bob = Participant.new("Bob")
internal val carol = Participant.new("Carol")
internal val dave = Participant.new("Dave")

internal fun arenaId(name: String): Arena.Id = Arena.Id.of(name) ?: error("invalid arena name: '$name'")

internal fun match(requiredWins: Int = 3) = ArenaMatch.new(arenaId("arena1"), requiredWins)

internal fun startedMatch(requiredWins: Int = 3): ArenaMatch {
    var m = match(requiredWins)
    m = m.join(alice).match
    m = m.join(bob).match
    val began = m.beginMatch()
    check(began.outcome)
    return began.match
}

internal fun stateForKind(kind: ArenaState.Kind): ArenaState = when (kind) {
    ArenaState.Kind.WAITING -> ArenaState.Waiting
    ArenaState.Kind.ONEMORE -> ArenaState.OneMore(alice)
    ArenaState.Kind.COUNTDOWN -> ArenaState.Countdown.of(alice, bob)
    ArenaState.Kind.ROUNDCOUNTDOWN -> ArenaState.RoundCountdown.of(alice, bob, firstWins = 0, secondWins = 0)
    ArenaState.Kind.INGAME -> ArenaState.InGame.of(alice, bob, firstWins = 0, secondWins = 0)
}
