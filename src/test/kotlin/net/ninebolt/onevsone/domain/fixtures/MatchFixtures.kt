package net.ninebolt.onevsone.domain.fixtures

import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Participant
import java.util.UUID

/** ArenaMatch テストの共有フィクスチャ。Participant は immutable なので共有する。 */

internal val alice = Participant(UUID.randomUUID(), "Alice")
internal val bob = Participant(UUID.randomUUID(), "Bob")
internal val carol = Participant(UUID.randomUUID(), "Carol")

internal fun match(requiredWins: Int = 3) = ArenaMatch(ArenaId("arena1"), requiredWins)

internal fun startedMatch(requiredWins: Int = 3): ArenaMatch {
    var m = match(requiredWins)
    m = m.join(alice).match
    m = m.join(bob).match
    val began = m.beginMatch()
    check(began.outcome)
    return began.match
}
