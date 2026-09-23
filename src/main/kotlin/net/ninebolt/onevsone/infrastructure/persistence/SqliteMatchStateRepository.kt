package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.domain.ArenaMatch

/**
 * The write-only match projection: the participant ledger (registrations)
 * plus the status snapshot (match_status), one transaction per call.
 * Identity in the ledger is the player uuid; the arena's registration rows
 * are rewritten wholesale so a failed call self-heals on the next one.
 */
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
        // Forensic strings only: the projection is never read back at runtime
        val players = match.participants.joinToString(",") { it.name }
        val wins = match.participants.joinToString(",") { "${it.name}:${match.winsOf(it.id)}" }
        store.exec(
            "INSERT OR REPLACE INTO match_status(arena_name, state, players, wins) VALUES (?, ?, ?, ?)",
            match.arenaId.name, match.state.name, players, wins
        )
    }

    /** Backups are a different context and are deliberately untouched. */
    override fun clearRegistrations() {
        store.exec("DELETE FROM registrations")
    }
}
