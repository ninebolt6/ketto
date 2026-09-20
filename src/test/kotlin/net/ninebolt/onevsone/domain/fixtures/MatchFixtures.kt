package net.ninebolt.onevsone.domain.fixtures

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant
import kotlin.uuid.Uuid

/** ArenaMatch テストの共有フィクスチャ。Participant は immutable なので共有する。 */

internal val alice = Participant(Uuid.random(), "Alice")
internal val bob = Participant(Uuid.random(), "Bob")
internal val carol = Participant(Uuid.random(), "Carol")

internal fun match(requiredWins: Int = 3) = ArenaMatch(Arena.Id("arena1"), requiredWins)

internal fun startedMatch(requiredWins: Int = 3): ArenaMatch {
    var m = match(requiredWins)
    m = m.join(alice).match
    m = m.join(bob).match
    val began = m.beginMatch()
    check(began.outcome)
    return began.match
}
