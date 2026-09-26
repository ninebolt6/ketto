package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import net.ninebolt.onevsone.infrastructure.persistence.fixtures.countRows
import net.ninebolt.onevsone.infrastructure.persistence.fixtures.withStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteMatchStateRepositoryTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `status row persists state players and wins`() = withStore(folder) { store ->
        SqliteArenaRepository(store).save(Arena.Disabled.new(arenaId("a1")))
        val p1 = Participant.new("Alice")
        val p2 = Participant.new("Bob")
        val match = ArenaMatch.restored(
            arenaId("a1"),
            requiredWins = 3,
            state = ArenaState.InGame.of(p1, p2, firstWins = 2, secondWins = 0),
        )
        SqliteMatchStateRepository(store).saveStatus(match)

        val row = store.queryOne("SELECT state, players, wins FROM match_status WHERE arena_name = 'a1'") {
            Triple(it.getString("state"), it.getString("players"), it.getString("wins"))
        }!!
        assertEquals("INGAME", row.first)
        assertEquals("Alice,Bob", row.second)
        assertTrue(row.third.contains("Alice:2"))
    }

    @Test
    fun `persistMatch writes uuid keyed registrations`() = withStore(folder) { store ->
        SqliteArenaRepository(store).save(Arena.Disabled.new(arenaId("a1")))
        val p1 = Participant.new("Alice")
        val p2 = Participant.new("Alice")
        val match = ArenaMatch.restored(
            arenaId("a1"),
            requiredWins = 3,
            state = ArenaState.Countdown.of(p1, p2),
        )
        SqliteMatchStateRepository(store).persistMatch(match)

        val rows = store.query("SELECT player_uuid, player_name FROM registrations WHERE arena_name = 'a1'") {
            it.getString("player_uuid") to it.getString("player_name")
        }
        assertEquals(setOf(p1.id.toString(), p2.id.toString()), rows.map { it.first }.toSet())
    }

    @Test
    fun `persistMatch converges rows left behind by a failed call`() = withStore(folder) { store ->
        SqliteArenaRepository(store).save(Arena.Disabled.new(arenaId("a1")))
        store.exec("INSERT INTO registrations(player_uuid, player_name, arena_name) VALUES (?, ?, ?)", "stale-uuid", "Stale", "a1")

        val p = Participant.new("Alice")
        val match = ArenaMatch.restored(
            arenaId("a1"),
            requiredWins = 3,
            state = ArenaState.OneMore(p),
        )
        SqliteMatchStateRepository(store).persistMatch(match)

        val rows = store.query("SELECT player_uuid FROM registrations WHERE arena_name = 'a1'") { it.getString(1) }
        assertEquals(listOf(p.id.toString()), rows)
    }

    @Test
    fun `persistMatch moves a registration when the player joined another arena`() = withStore(folder) { store ->
        val arenas = SqliteArenaRepository(store)
        arenas.save(Arena.Disabled.new(arenaId("a1")))
        arenas.save(Arena.Disabled.new(arenaId("a2")))
        val repo = SqliteMatchStateRepository(store)
        val p = Participant.new("Alice")
        repo.persistMatch(
            ArenaMatch.restored(arenaId("a1"), 3, ArenaState.OneMore(p)),
        )
        repo.persistMatch(
            ArenaMatch.restored(arenaId("a2"), 3, ArenaState.OneMore(p)),
        )
        val rows = store.query("SELECT arena_name FROM registrations WHERE player_uuid = ?", p.id.toString()) {
            it.getString(1)
        }
        assertEquals(listOf("a2"), rows)
    }

    @Test
    fun `clearRegistrations preserves backups`() = withStore(folder) { store ->
        SqliteArenaRepository(store).save(Arena.Disabled.new(arenaId("a1")))
        val p = Participant.new("Alice")
        val match = ArenaMatch.restored(arenaId("a1"), 3, ArenaState.OneMore(p))
        val matchState = SqliteMatchStateRepository(store)
        matchState.persistMatch(match)
        val ref = BackupRef.new(MatchId.new(), p.id, p.name)
        SqliteBackupStore(store).saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))

        matchState.clearRegistrations()
        assertEquals(0, countRows(store, "registrations"))
        val pending = SqliteBackupStore(store).persistedBackups()
        assertEquals(1, pending.size)
        assertEquals(p.id, pending[0].ref.playerId)
        assertEquals(ref.backupId, pending[0].ref.backupId)
    }
}
