package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger
import kotlin.uuid.Uuid

class SqlitePersistenceTest {

    @TempDir
    lateinit var folder: File

    private fun store() = SqliteStore(folder, Logger.getLogger("test"))

    private fun <T> withStore(block: (SqliteStore) -> T): T = store().use(block)

    private fun count(store: SqliteStore, table: String, where: String = "", vararg params: Any?): Int =
        store.queryOne("SELECT COUNT(*) AS c FROM $table $where", *params) { it.getInt("c") }!!

    @Test
    fun `database file and schema are created`() = withStore { store ->
        assertTrue(File(folder, "data.db").exists())
        val tables = store.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(1) }
        assertTrue(
            tables.containsAll(
                listOf("arenas", "arena_kits", "arena_signs", "match_status", "lobby", "registrations", "backups", "player_stats")
            )
        )
        assertEquals("wal", store.queryOne("PRAGMA journal_mode") { it.getString(1) })
    }

    @Test
    fun `arena round trip keeps fractional yaw pitch and enabled`() = withStore { store ->
        val repo = SqliteArenaRepository(store)
        val def = Arena.new(
            Arena.Id.new("a1"),
            enabled = true,
            spawn1 = WorldPosition.new("world", 1.5, 64.25, -3.75, 12.34f, -56.78f)
        )
        repo.save(def)

        val loaded = repo.find("a1")!!
        assertTrue(loaded.enabled)
        val spawn1 = loaded.spawn1!!
        assertEquals(1.5, spawn1.x)
        assertEquals(12.34f, spawn1.yaw, 0.001f)
        assertEquals(-56.78f, spawn1.pitch, 0.001f)
    }

    @Test
    fun `missing arena row loads disabled defaults`() = withStore { store ->
        val arena = SqliteArenaRepository(store).find("ghost")!!
        assertFalse(arena.enabled)
        assertNull(arena.spawn1)
    }

    @Test
    fun `loadAll follows insertion order and updates keep the slot`() = withStore { store ->
        val repo = SqliteArenaRepository(store)
        repo.save(Arena.new(Arena.Id.new("b1")))
        repo.save(Arena.new(Arena.Id.new("a1"), enabled = true))
        repo.save(Arena.new(Arena.Id.new("b1"), enabled = true))

        val loaded = repo.loadAll()
        assertEquals(listOf("b1", "a1"), loaded.map { it.name })
        assertTrue(loaded[0].enabled)

        repo.delete("b1")
        assertEquals(listOf("a1"), repo.loadAll().map { it.name })
    }

    @Test
    fun `arena names collide case insensitively`() = withStore { store ->
        val repo = SqliteArenaRepository(store)
        repo.save(Arena.new(Arena.Id.new("Arena1")))
        repo.save(Arena.new(Arena.Id.new("arena1"), enabled = true))
        val loaded = repo.loadAll()
        assertEquals(1, loaded.size)
        assertTrue(loaded[0].enabled)
    }

    @Test
    fun `arena delete cascades kit sign and status but not registrations`() = withStore { store ->
        val arenas = SqliteArenaRepository(store)
        arenas.save(Arena.new(Arena.Id.new("a1"), enabled = true))
        SqliteKitStore(store).saveArenaKit("a1", PaperInventorySnapshot())
        SqliteArenaSignRepository(store).setSign("a1", BlockPosition.new("world", 1, 2, 3))
        SqliteMatchStateRepository(store).saveStatus(ArenaMatch.new(Arena.Id.new("a1"), requiredWins = 3))
        store.exec(
            "INSERT INTO registrations(player_uuid, player_name, arena_name) VALUES (?, ?, ?)",
            Uuid.random().toString(), "Alice", "a1"
        )

        arenas.delete("a1")
        assertEquals(0, count(store, "arena_kits"))
        assertEquals(0, count(store, "arena_signs"))
        assertEquals(0, count(store, "match_status"))
        // registrations deliberately has no FK; the abort flow clears it before delete
        assertEquals(1, count(store, "registrations"))
    }

    @Test
    fun `stats record win and lose per uuid`() = withStore { store ->
        val repo = SqlitePlayerStatsRepository(store)
        val uuid = Uuid.random()
        assertNull(repo.find(uuid))
        repo.recordWin(uuid)
        repo.recordLoss(uuid)
        repo.recordLoss(uuid)
        val loaded = repo.find(uuid)!!
        assertEquals(1, loaded.wins)
        assertEquals(2, loaded.losses)
    }

    @Test
    fun `status row persists state players and wins`() = withStore { store ->
        SqliteArenaRepository(store).save(Arena.new(Arena.Id.new("a1")))
        val p1 = Participant.new("Alice")
        val p2 = Participant.new("Bob")
        val match = ArenaMatch.restored(
            Arena.Id.new("a1"),
            requiredWins = 3,
            state = ArenaState.INGAME,
            participants = listOf(p1, p2),
            wins = mapOf(p1.id to 2)
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
    fun `persistMatch writes uuid keyed registrations`() = withStore { store ->
        SqliteArenaRepository(store).save(Arena.new(Arena.Id.new("a1")))
        val p1 = Participant.new("Alice")
        val p2 = Participant.new("Alice") // same name, different identity
        val match = ArenaMatch.restored(
            Arena.Id.new("a1"), requiredWins = 3, state = ArenaState.COUNTDOWN,
            participants = listOf(p1, p2), wins = emptyMap()
        )
        SqliteMatchStateRepository(store).persistMatch(match)

        val rows = store.query("SELECT player_uuid, player_name FROM registrations WHERE arena_name = 'a1'") {
            it.getString("player_uuid") to it.getString("player_name")
        }
        assertEquals(setOf(p1.id.toString(), p2.id.toString()), rows.map { it.first }.toSet())
    }

    @Test
    fun `persistMatch converges rows left behind by a failed call`() = withStore { store ->
        SqliteArenaRepository(store).save(Arena.new(Arena.Id.new("a1")))
        // A previous lenient failure left a stale row for this arena and one
        // that re-pointed another arena's player at a1
        store.exec("INSERT INTO registrations(player_uuid, player_name, arena_name) VALUES (?, ?, ?)", "stale-uuid", "Stale", "a1")

        val p = Participant.new("Alice")
        val match = ArenaMatch.restored(
            Arena.Id.new("a1"), requiredWins = 3, state = ArenaState.ONEMORE,
            participants = listOf(p), wins = emptyMap()
        )
        SqliteMatchStateRepository(store).persistMatch(match)

        val rows = store.query("SELECT player_uuid FROM registrations WHERE arena_name = 'a1'") { it.getString(1) }
        assertEquals(listOf(p.id.toString()), rows)
    }

    @Test
    fun `persistMatch moves a registration when the player joined another arena`() = withStore { store ->
        val arenas = SqliteArenaRepository(store)
        arenas.save(Arena.new(Arena.Id.new("a1")))
        arenas.save(Arena.new(Arena.Id.new("a2")))
        val repo = SqliteMatchStateRepository(store)
        val p = Participant.new("Alice")
        repo.persistMatch(
            ArenaMatch.restored(Arena.Id.new("a1"), 3, ArenaState.ONEMORE, listOf(p), emptyMap())
        )
        // The same uuid rejoins elsewhere: the uuid PK moves the row
        repo.persistMatch(
            ArenaMatch.restored(Arena.Id.new("a2"), 3, ArenaState.ONEMORE, listOf(p), emptyMap())
        )
        val rows = store.query("SELECT arena_name FROM registrations WHERE player_uuid = ?", p.id.toString()) {
            it.getString(1)
        }
        assertEquals(listOf("a2"), rows)
    }

    @Test
    fun `clearRegistrations preserves backups`() = withStore { store ->
        SqliteArenaRepository(store).save(Arena.new(Arena.Id.new("a1")))
        val p = Participant.new("Alice")
        val match = ArenaMatch.restored(Arena.Id.new("a1"), 3, ArenaState.ONEMORE, listOf(p), emptyMap())
        val matchState = SqliteMatchStateRepository(store)
        matchState.persistMatch(match)
        val ref = BackupRef.new(MatchId.new(), p.id, p.name)
        SqliteBackupStore(store).saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))

        matchState.clearRegistrations()
        assertEquals(0, count(store, "registrations"))
        val pending = SqliteBackupStore(store).persistedBackups()
        assertEquals(1, pending.size)
        assertEquals(p.id, pending[0].ref.playerId)
        assertEquals(ref.backupId, pending[0].ref.backupId)
    }

    @Test
    fun `backup round trip and delete by backup id`() = withStore { store ->
        val backups = SqliteBackupStore(store)
        val p = Participant.new("Alice")
        val ref = BackupRef.new(MatchId.new(), p.id, p.name)
        backups.saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot(items = listOf(null)))))

        // A mismatched ref never deletes someone else's backup
        backups.deleteBackup(BackupRef.new(MatchId.new(), p.id, p.name))
        assertEquals(1, backups.persistedBackups().size)

        backups.deleteBackup(ref)
        assertEquals(0, backups.persistedBackups().size)
        assertNull(backups.backupFor(ref))
    }

    @Test
    fun `same name backups coexist because backup_id is the identity`() = withStore { store ->
        val backups = SqliteBackupStore(store)
        val first = Participant.new("Alice")
        val second = Participant.new("Alice")
        val match = MatchId.new()
        backups.saveBackups(
            listOf(
                PersistedBackup(BackupRef.new(match, first.id, first.name), PaperInventorySnapshot()),
                PersistedBackup(BackupRef.new(match, second.id, second.name), PaperInventorySnapshot())
            )
        )
        assertEquals(2, backups.persistedBackups().size)
    }

    @Test
    fun `atomic rolls back every statement on failure`() = withStore { store ->
        assertFailsWith<PersistenceFailure> {
            store.atomic {
                store.exec("INSERT INTO lobby(id, world, x, y, z, yaw, pitch) VALUES (1, 'w', 0, 0, 0, 0, 0)")
                store.exec("INSERT INTO definitely_not_a_table VALUES (1)")
            }
        }
        assertEquals(0, count(store, "lobby"))
    }

    @Test
    fun `nested atomic joins the outer transaction`() = withStore { store ->
        store.atomic {
            store.exec("INSERT INTO lobby(id, world, x, y, z, yaw, pitch) VALUES (1, 'w', 0, 0, 0, 0, 0)")
            store.atomic {
                store.exec("INSERT INTO registrations(player_uuid, player_name, arena_name) VALUES ('u', 'n', 'a')")
            }
        }
        assertEquals(1, count(store, "lobby"))
        assertEquals(1, count(store, "registrations"))
    }

    @Test
    fun `inner failure marks the transaction rollback only even when caught`() = withStore { store ->
        assertFailsWith<PersistenceFailure> {
            store.atomic {
                store.exec("INSERT INTO lobby(id, world, x, y, z, yaw, pitch) VALUES (1, 'w', 0, 0, 0, 0, 0)")
                try {
                    store.atomic { store.exec("INSERT INTO definitely_not_a_table VALUES (1)") }
                } catch (e: PersistenceFailure) {
                    // Caller continues; the unit must still roll back.
                }
            }
        }
        assertEquals(0, count(store, "lobby"))
    }

    @Test
    fun `newer user_version fails fast without rewriting`() {
        store().use { }
        DriverManager.getConnection("jdbc:sqlite:${File(folder, "data.db").absolutePath}").use { conn ->
            conn.createStatement().use { it.execute("PRAGMA user_version=99") }
        }
        assertFailsWith<PersistenceFailure> { store() }
        DriverManager.getConnection("jdbc:sqlite:${File(folder, "data.db").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    rs.next()
                    assertEquals(99, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `codec failure surfaces as PersistenceFailure`() = withStore { store ->
        store.exec(
            "INSERT INTO backups(backup_id, match_id, player_uuid, player_name, payload) VALUES (?, ?, ?, ?, ?)",
            Uuid.random().toString(), Uuid.random().toString(), null, "Alice", "not: [valid"
        )
        assertFailsWith<PersistenceFailure> { SqliteBackupStore(store).persistedBackups() }
    }

    @Test
    fun `corrupt arena row surfaces as PersistenceFailure`() = withStore { store ->
        store.exec(
            "INSERT INTO arenas(name, enabled, spawn1_world, spawn1_x, spawn1_y, spawn1_z, seq) VALUES ('a1', 1, '', 0, 0, 0, 1)"
        )
        assertFailsWith<PersistenceFailure> { SqliteArenaRepository(store).find("a1") }
        // loadAll skips the corrupt row instead of failing the whole startup
        assertEquals(emptyList(), SqliteArenaRepository(store).loadAll())
    }

    @Test
    fun `lobby round trips`() = withStore { store ->
        val lobby = SqliteLobbyRepository(store)
        assertNull(lobby.lobby())
        lobby.setLobby(WorldPosition.new("lobby", 1.0, 2.0, 3.0, 45.5f, 10.25f))
        val loaded = lobby.lobby()!!
        assertEquals("lobby", loaded.world)
        assertEquals(45.5f, loaded.yaw, 0.001f)
    }

    @Test
    fun `kit round trips and deletes`() = withStore { store ->
        SqliteArenaRepository(store).save(Arena.new(Arena.Id.new("a1")))
        val kits = SqliteKitStore(store)
        assertEquals(PaperInventorySnapshot(), kits.loadArenaKit("a1"))
        kits.saveArenaKit("a1", PaperInventorySnapshot(items = listOf(null, null)))
        assertEquals(2, kits.loadArenaKit("a1").items.size)
        kits.deleteArenaKit("a1")
        assertEquals(PaperInventorySnapshot(), kits.loadArenaKit("a1"))
    }

    @Test
    fun `sign index is rebuilt from the table by a new instance`() = withStore { store ->
        SqliteArenaRepository(store).save(Arena.new(Arena.Id.new("a1")))
        SqliteArenaSignRepository(store).setSign("a1", BlockPosition.new("world", 5, 64, 5))
        // A separate instance has no index yet, so it scans the table once
        val fresh = SqliteArenaSignRepository(store)
        assertEquals("a1", fresh.signOwner(BlockPosition.new("world", 5, 64, 5)))
        assertEquals(5, fresh.signLocation("a1")!!.x)
    }

    @Test
    fun `setSign releases old position and clearSign removes it`() = withStore { store ->
        SqliteArenaRepository(store).save(Arena.new(Arena.Id.new("a1")))
        val repo = SqliteArenaSignRepository(store)
        repo.setSign("a1", BlockPosition.new("world", 5, 64, 5))
        repo.setSign("a1", BlockPosition.new("world", 9, 64, 9))
        assertNull(repo.signOwner(BlockPosition.new("world", 5, 64, 5)))
        assertEquals("a1", repo.signOwner(BlockPosition.new("world", 9, 64, 9)))
        assertEquals(9, repo.signLocation("a1")!!.x)
        repo.clearSign("a1")
        assertNull(repo.signOwner(BlockPosition.new("world", 9, 64, 9)))
        assertEquals(0, count(store, "arena_signs"))
    }

    @Test
    fun `repositories never write config yml`() = withStore { store ->
        val file = File(folder, "config.yml")
        file.writeText("prefix: '&9[X] '\n")
        val before = file.readBytes()

        SqliteLobbyRepository(store).setLobby(WorldPosition.new("lobby", 1.0, 2.0, 3.0))
        SqliteArenaRepository(store).save(Arena.new(Arena.Id.new("a1")))
        SqliteArenaSignRepository(store).setSign("a1", BlockPosition.new("world", 5, 64, 5))
        assertEquals(before.toList(), file.readBytes().toList())
    }
}
