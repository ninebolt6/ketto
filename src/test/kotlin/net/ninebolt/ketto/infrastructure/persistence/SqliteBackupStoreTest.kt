package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.application.port.BackupRef
import net.ninebolt.ketto.application.port.PersistenceException
import net.ninebolt.ketto.domain.MatchId
import net.ninebolt.ketto.domain.Participant
import net.ninebolt.ketto.infrastructure.paper.PaperInventorySnapshot
import net.ninebolt.ketto.infrastructure.persistence.fixtures.withStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class SqliteBackupStoreTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `backup round trip and delete by backup id`() = withStore(folder) { store ->
        val backups = SqliteBackupStore(store)
        val p = Participant.new("Alice")
        val ref = BackupRef.new(MatchId.new(), p.id, p.name)
        backups.saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot(items = listOf(null)))))

        backups.deleteBackup(BackupRef.new(MatchId.new(), p.id, p.name))
        assertEquals(1, backups.pendingRefs().size)

        backups.deleteBackup(ref)
        assertEquals(0, backups.pendingRefs().size)
        assertNull(backups.backupFor(ref))
    }

    @Test
    fun `same name backups coexist because backup_id is the identity`() = withStore(folder) { store ->
        val backups = SqliteBackupStore(store)
        val first = Participant.new("Alice")
        val second = Participant.new("Alice")
        val match = MatchId.new()
        backups.saveBackups(
            listOf(
                PersistedBackup(BackupRef.new(match, first.id, first.name), PaperInventorySnapshot()),
                PersistedBackup(BackupRef.new(match, second.id, second.name), PaperInventorySnapshot()),
            ),
        )
        assertEquals(2, backups.pendingRefs().size)
    }

    @Test
    fun `a second backup for the same player replaces the pending row`() = withStore(folder) { store ->
        val backups = SqliteBackupStore(store)
        val p = Participant.new("Alice")
        val first = BackupRef.new(MatchId.new(), p.id, p.name)
        val second = BackupRef.new(MatchId.new(), p.id, p.name)
        backups.saveBackups(listOf(PersistedBackup(first, PaperInventorySnapshot())))
        backups.saveBackups(listOf(PersistedBackup(second, PaperInventorySnapshot())))

        assertEquals(second, backups.findPending(p.id))
        assertEquals(listOf(second), backups.pendingRefs())
        assertNull(backups.backupFor(first))
    }

    @Test
    fun `codec failure surfaces as PersistenceException`() {
        withStore(folder) { store ->
            store.exec(
                "INSERT INTO backups(backup_id, match_id, player_uuid, player_name, payload) VALUES (?, ?, ?, ?, ?)",
                Uuid.random().toString(),
                Uuid.random().toString(),
                Uuid.random().toString(),
                "Alice",
                "not: [valid",
            )
            val ref = SqliteBackupStore(store).pendingRefs().single()
            assertFailsWith<PersistenceException> { SqliteBackupStore(store).backupFor(ref) }
        }
    }

    @Test
    fun `pendingRefs skips a row with an unparseable backup id and keeps valid rows`() = withStore(folder) { store ->
        store.exec(
            "INSERT INTO backups(backup_id, match_id, player_uuid, player_name, payload) VALUES (?, ?, ?, ?, ?)",
            "not-a-uuid",
            Uuid.random().toString(),
            Uuid.random().toString(),
            "Alice",
            InventoryPayloadCodec.encode(PaperInventorySnapshot()),
        )
        val backups = SqliteBackupStore(store)
        val p = Participant.new("Bob")
        val ref = BackupRef.new(MatchId.new(), p.id, p.name)
        backups.saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))

        assertEquals(listOf(ref), backups.pendingRefs())
    }

    @Test
    fun `pendingFor surfaces a corrupt row as PersistenceException`() {
        withStore(folder) { store ->
            val playerId = Uuid.random()
            store.exec(
                "INSERT INTO backups(backup_id, match_id, player_uuid, player_name, payload) VALUES (?, ?, ?, ?, ?)",
                Uuid.random().toString(),
                "bad",
                playerId.toString(),
                "Alice",
                InventoryPayloadCodec.encode(PaperInventorySnapshot()),
            )
            assertFailsWith<PersistenceException> { SqliteBackupStore(store).findPending(playerId) }
        }
    }

    @Test
    fun `backup rows with an unparseable player uuid are skipped`() = withStore(folder) { store ->
        store.exec(
            "INSERT INTO backups(backup_id, match_id, player_uuid, player_name, payload) VALUES (?, ?, ?, ?, ?)",
            Uuid.random().toString(),
            Uuid.random().toString(),
            "not-a-uuid",
            "Alice",
            InventoryPayloadCodec.encode(PaperInventorySnapshot()),
        )
        assertEquals(0, SqliteBackupStore(store).pendingRefs().size)
    }
}
