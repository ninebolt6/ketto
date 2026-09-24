package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.ArenaMatch

// the ledger is rewritten wholesale per call, so a failed call self-heals on the next
class SqliteMatchStateRepository(private val store: SqliteStore) : MatchStateRepository {

    override fun persistMatch(match: ArenaMatch) {
        store.atomic {
            store.exec("DELETE FROM registrations WHERE arena_name = ?", match.arenaId.name)
            match.participants.forEach { participant ->
                store.exec(
                    "INSERT OR REPLACE INTO registrations(player_uuid, player_name, arena_name) VALUES (?, ?, ?)",
                    participant.id.toString(), participant.name, match.arenaId.name
                )
            }
            writeStatus(match)
        }
    }

    override fun saveStatus(match: ArenaMatch) {
        store.atomic { writeStatus(match) }
    }

    private fun writeStatus(match: ArenaMatch) {
        // forensic strings only: this projection is never read back at runtime
        val players = match.participants.joinToString(",") { it.name }
        val wins = match.participants.joinToString(",") { "${it.name}:${match.winsOf(it.id)}" }
        store.exec(
            "INSERT OR REPLACE INTO match_status(arena_name, state, players, wins) VALUES (?, ?, ?, ?)",
            match.arenaId.name, match.state.name, players, wins
        )
    }

    // backups are deliberately untouched
    override fun clearRegistrations() {
        store.exec("DELETE FROM registrations")
    }
}
